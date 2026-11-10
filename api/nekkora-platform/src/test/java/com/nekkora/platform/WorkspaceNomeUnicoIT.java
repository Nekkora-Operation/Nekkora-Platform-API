package com.nekkora.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

import com.nekkora.core.testsupport.BrowserClient;

/**
 * PLATFORM-V2-001, T03: nome de workspace unico por organizacao (031). Chave = organizacao + lower(btrim(nome)); nome visivel
 * preservado; criacao e renomeacao; conflito legivel (409 workspace_name_taken) com um unico registro; outra organizacao livre e
 * sem revelar nada da primeira; corrida resolvida pelo indice do banco; arquivado continua ocupando o nome.
 */
class WorkspaceNomeUnicoIT extends PlatformTestBase {

    private static final String TAKEN = "{\"error\":\"conflict\",\"reason\":\"workspace_name_taken\"}";

    record Pessoa(BrowserClient b, String o, UUID org, UUID membership) {
    }

    private Pessoa admin(Org org) {
        BrowserClient b = browser();
        firstLogin(b, org.id(), new TestUser(org.adminId(), org.adminEmail(), PASSWORD.toCharArray()));
        return new Pessoa(b, "/api/v1/o/" + org.id(), org.id(), org.adminMembership());
    }

    /** Segunda pessoa da mesma organizacao, tambem ORG_ADMIN (tem workspace.lifecycle), com sessao propria. */
    private Pessoa outraAdmin(Org org) {
        TestUser u = internalUser(org, true, 2);
        UUID m = AccessFixtures.membershipOf(org.id(), u.id());
        AccessFixtures.assignOrgRole(org.id(), m, "ORG_ADMIN", org.adminMembership());
        BrowserClient b = browser();
        firstLogin(b, org.id(), u);
        return new Pessoa(b, "/api/v1/o/" + org.id(), org.id(), m);
    }

    private static BrowserClient.Response criar(Pessoa p, String nome) {
        return p.b().post(p.o() + "/workspaces", "{\"name\":\"" + nome + "\"}");
    }

    private BrowserClient.Response renomear(Pessoa p, String workspace, String nome) {
        return p.b().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + p.o() + "/workspaces/" + workspace))
                .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"name\":\"" + nome + "\"}")).header("Content-Type", "application/json"));
    }

    private static long comChave(UUID org, String chave) {
        return Long.parseLong(AccessFixtures.value("SELECT count(*) FROM workspaces WHERE organization_id = '" + org + "' AND lower(btrim(name)) = '" + chave + "'"));
    }

    private static long eventos(UUID org, int ato) {
        return Long.parseLong(AccessFixtures.value("SELECT count(*) FROM automation_admin_events WHERE organization_id = '" + org + "' AND act = " + ato));
    }

    @Test
    void mesma_organizacao_recusa_nome_repetido_e_outra_organizacao_aceita_sem_revelar_a_primeira() {
        Org exemplo1 = newOrganization();
        Pessoa ana = admin(exemplo1);
        Pessoa bruno = outraAdmin(exemplo1);

        BrowserClient.Response criado = criar(ana, "Financeiro");
        assertThat(criado.status()).as(criado.body()).isEqualTo(201);
        assertThat(criado.field("name")).isEqualTo("Financeiro");

        // Bruno, outra pessoa e outra sessao da MESMA organizacao: maiusculas e espacos nas extremidades nao distinguem
        BrowserClient.Response repetido = criar(bruno, " financeiro ");
        assertThat(repetido.status()).isEqualTo(409);
        assertThat(repetido.body()).isEqualTo(TAKEN);
        assertThat(comChave(exemplo1.id(), "financeiro")).isEqualTo(1);
        assertThat(eventos(exemplo1.id(), 1)).isEqualTo(1);   // so a criacao aceita foi auditada

        // Exemplo 2: o mesmo nome e livre; a resposta so fala do workspace novo
        Org exemplo2 = newOrganization();
        Pessoa bruno2 = admin(exemplo2);
        BrowserClient.Response outro = criar(bruno2, "Financeiro");
        assertThat(outro.status()).as(outro.body()).isEqualTo(201);
        assertThat(outro.body()).doesNotContain(exemplo1.id().toString()).doesNotContain(criado.field("workspaceId"));
        assertThat(comChave(exemplo2.id(), "financeiro")).isEqualTo(1);
        assertThat(comChave(exemplo1.id(), "financeiro")).isEqualTo(1);
    }

    @Test
    void letras_acentuadas_sao_comparadas_sem_distincao_de_caixa() {
        Pessoa ana = admin(newOrganization());
        assertThat(criar(ana, "Ação Fiscal").status()).isEqualTo(201);
        BrowserClient.Response r = criar(ana, "AÇÃO FISCAL");
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.body()).isEqualTo(TAKEN);
        assertThat(criar(ana, "Ação Fiscal 2").status()).isEqualTo(201);   // outro nome de fato
    }

    @Test
    void renomear_para_nome_ocupado_e_conflito_e_editar_o_proprio_sem_mudar_o_nome_e_permitido() {
        Org org = newOrganization();
        Pessoa ana = admin(org);
        String financeiro = criar(ana, "Financeiro").field("workspaceId");
        String compras = criar(ana, "Compras").field("workspaceId");
        long renomeacoes = eventos(org.id(), 7);

        BrowserClient.Response ocupado = renomear(ana, compras, "  FINANCEIRO");
        assertThat(ocupado.status()).isEqualTo(409);
        assertThat(ocupado.body()).isEqualTo(TAKEN);
        assertThat(AccessFixtures.value("SELECT name FROM workspaces WHERE workspace_id = '" + compras + "'")).isEqualTo("Compras");

        // o proprio nome, com espacos nas extremidades: nada muda, nada e auditado
        BrowserClient.Response mesmo = renomear(ana, financeiro, " Financeiro ");
        assertThat(mesmo.status()).as(mesmo.body()).isEqualTo(200);
        assertThat(mesmo.field("name")).isEqualTo("Financeiro");
        assertThat(eventos(org.id(), 7)).isEqualTo(renomeacoes);

        // so a caixa do proprio nome: permitido (mesma chave, mesma linha), auditado como renomeacao
        BrowserClient.Response caixa = renomear(ana, financeiro, "FINANCEIRO");
        assertThat(caixa.status()).as(caixa.body()).isEqualTo(200);
        assertThat(caixa.field("name")).isEqualTo("FINANCEIRO");
        assertThat(eventos(org.id(), 7)).isEqualTo(renomeacoes + 1);

        // nome livre: renomeia; o antigo fica livre
        assertThat(renomear(ana, compras, "Suprimentos").status()).isEqualTo(200);
        assertThat(criar(ana, "Compras").status()).isEqualTo(201);

        // inexistente ou de outra organizacao: 404 identico, nada muda
        assertThat(renomear(ana, UUID.randomUUID().toString(), "Qualquer").status()).isEqualTo(404);
        Pessoa outra = admin(newOrganization());
        String alheio = criar(outra, "Alheio").field("workspaceId");
        assertThat(renomear(ana, alheio, "Tomado").status()).isEqualTo(404);
        assertThat(AccessFixtures.value("SELECT name FROM workspaces WHERE workspace_id = '" + alheio + "'")).isEqualTo("Alheio");

        // nome invalido
        assertThat(renomear(ana, compras, "   ").status()).isEqualTo(400);
    }

    @Test
    void arquivado_continua_ocupando_o_nome() {
        Org org = newOrganization();
        Pessoa ana = admin(org);
        String w = criar(ana, "Legado").field("workspaceId");
        DB.asSuperuser("UPDATE workspaces SET status = 3 WHERE workspace_id = '" + w + "'");
        assertThat(criar(ana, "legado").status()).isEqualTo(409);
        assertThat(comChave(org.id(), "legado")).isEqualTo(1);
    }

    @Test
    void duas_criacoes_simultaneas_resultam_em_um_sucesso_e_um_conflito() throws Exception {
        for (int rodada = 0; rodada < 3; rodada++) {
            Org org = newOrganization();
            Pessoa ana = admin(org);
            Pessoa bruno = outraAdmin(org);
            String nome = "Paralelo " + rodada;
            CountDownLatch largada = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                List<Callable<Integer>> atos = List.of(
                        () -> { largada.await(); return criar(ana, nome).status(); },
                        () -> { largada.await(); return criar(bruno, nome.toUpperCase()).status(); });
                List<Future<Integer>> fs = new ArrayList<>();
                for (Callable<Integer> a : atos) {
                    fs.add(pool.submit(a));
                }
                largada.countDown();
                List<Integer> st = new ArrayList<>();
                for (Future<Integer> f : fs) {
                    st.add(f.get());
                }
                assertThat(st).containsExactlyInAnyOrder(201, 409);
                assertThat(comChave(org.id(), nome.toLowerCase())).isEqualTo(1);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    void restricao_tambem_vale_para_gravacao_direta_no_banco() {
        Org org = newOrganization();
        Pessoa ana = admin(org);
        criar(ana, "Direto");
        Throwable t = catchThrowable(() -> DB.asSuperuser(
                "INSERT INTO workspaces (organization_id, name, status) VALUES ('" + org.id() + "', ' DIRETO ', 1)"));
        assertThat(t).isNotNull().hasStackTraceContaining("ux_workspaces_organization_name_key");
    }
}
