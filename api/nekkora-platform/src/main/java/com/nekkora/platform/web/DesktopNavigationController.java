package com.nekkora.platform.web;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.nekkora.core.web.ApiError;
import com.nekkora.core.web.ApiException;
import com.nekkora.core.web.RequestContextFilter;
import com.nekkora.platform.desktop.DesktopNavigationService;
import com.nekkora.platform.limits.Limits;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Contexto da pessoa para o aplicativo desktop (AGENT-CLIENT-NAVEGACAO-001, contrato desktop-navigation/1). Rotas SO do
 * aplicativo: {@code Authorization: Bearer} da sessao do desktop com o escopo {@code identity navigation}; nenhum cookie e
 * lido, nao ha CSRF (o cliente nativo nao envia Origin) e a resposta nunca e guardada ({@code Cache-Control: no-store}). A
 * mesma chave e o mesmo limitador das demais rotas {@code /api/v1/desktop/}. O aplicativo so envia o token e os ids do
 * caminho: conta, vinculo, papel e permissao sao resolvidos no servidor a cada requisicao ({@link DesktopNavigationService}).
 */
@RestController
public class DesktopNavigationController {

    public static final String ME = "/api/v1/desktop/me";
    public static final String ORGANIZATIONS = "/api/v1/desktop/organizations";
    public static final String WORKSPACES = "/api/v1/desktop/organizations/{org}/workspaces";
    public static final String AUTOMATIONS = "/api/v1/desktop/organizations/{org}/workspaces/{workspace}/automations";
    public static final String VERSIONS = "/api/v1/desktop/organizations/{org}/automations/{automation}/versions";
    public static final String AUTOMATION = "/api/v1/desktop/organizations/{org}/automations/{automation}";

    /** Tudo o que e (ou vier a ser) rota de navegacao: a chave do login desktop fecha o prefixo inteiro. */
    static final Pattern ROUTES = Pattern.compile("^/api/v1/desktop/(me|organizations(/.*)?)$");
    /** A unica escrita: renomear automacao. So esta forma exata fica fora do CSRF do navegador. */
    static final Pattern RENAME = Pattern.compile("^/api/v1/desktop/organizations/[0-9a-fA-F-]{36}/automations/[0-9a-fA-F-]{36}$");

    public record RenameRequest(String name) {
    }

    private final DesktopNavigationService service;
    private final Limits limits;
    private final boolean enabled;

    public DesktopNavigationController(DesktopNavigationService service, Limits limits,
                                       @Value("${nekkora.desktop-login.enabled:false}") boolean desktopEnabled,
                                       @Value("${nekkora.entrada-geral.enabled:false}") boolean entryEnabled) {
        this.service = service;
        this.limits = limits;
        this.enabled = desktopEnabled && entryEnabled;
    }

    /** Chave e limitador, e so entao o token (401 sem ele, antes de qualquer leitura). */
    private String open(HttpServletRequest req) {
        if (!enabled) {
            throw new ApiException(ApiError.NOT_FOUND, "login_desktop_desligado", null);
        }
        limits.identityRoutes(RequestContextFilter.ip(req));
        return DesktopLoginController.bearer(req);
    }

    @GetMapping(ME)
    public ResponseEntity<Map<String, Object>> me(HttpServletRequest req) {
        return noStore(service.me(open(req)));
    }

    @GetMapping(ORGANIZATIONS)
    public ResponseEntity<Map<String, Object>> organizations(HttpServletRequest req) {
        return noStore(service.organizations(open(req)));
    }

    @GetMapping(WORKSPACES)
    public ResponseEntity<Map<String, Object>> workspaces(@PathVariable UUID org, @RequestParam(required = false) String cursor,
                                                          @RequestParam(required = false) Integer limit, HttpServletRequest req) {
        return noStore(service.workspaces(open(req), org, cursor, limit));
    }

    @GetMapping(AUTOMATIONS)
    public ResponseEntity<Map<String, Object>> automations(@PathVariable UUID org, @PathVariable UUID workspace,
                                                           @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit,
                                                           HttpServletRequest req) {
        return noStore(service.automations(open(req), org, workspace, cursor, limit));
    }

    @GetMapping(VERSIONS)
    public ResponseEntity<Map<String, Object>> versions(@PathVariable UUID org, @PathVariable UUID automation,
                                                        @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit,
                                                        HttpServletRequest req) {
        return noStore(service.versions(open(req), org, automation, cursor, limit));
    }

    @PatchMapping(AUTOMATION)
    public ResponseEntity<Map<String, Object>> rename(@PathVariable UUID org, @PathVariable UUID automation,
                                                      @RequestBody(required = false) RenameRequest b, HttpServletRequest req) {
        return noStore(service.renameAutomation(open(req), org, automation, b == null ? null : b.name()));
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Pragma", "no-cache").body(body);
    }
}
