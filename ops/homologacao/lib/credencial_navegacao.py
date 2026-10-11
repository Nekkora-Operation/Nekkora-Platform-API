"""Entrega de credencial das contas de NAVEGACAO do ensaio-frontend (F1.5, P-C-T) para a digitacao no navegador, sem
que o valor apareca em linha de comando, saida, log ou relatorio.

Uso: python lib/credencial_navegacao.py --segredos <dir_segredos_do_ambiente> <conta> senha|totp|totp-reuso [--validade S]

  - Contas aceitas: SO as do plano com uso = navegacao (admin, wsadmin, operador, leitor, semacesso, cliente, admin-b).
    Qualquer outra (j-*, primeiro-acesso-*, recuperacao-*, sessoes, expira, desconhecida) e RECUSADA (codigo 2), sem
    ler credencial.
  - Canal: a AREA DE TRANSFERENCIA do Windows, marcada para ficar FORA do historico e da nuvem da area de transferencia
    (formatos ExcludeClipboardContentFromMonitorProcessing, CanIncludeInClipboardHistory=0, CanUploadToCloudClipboard=0).
    A digitacao e por colar (ctrl+v). Sem area de transferencia utilizavel: RECUSA (codigo 3) e a responsavel digita.
  - Entrega ATOMICA (P-C-T-R1): texto e marcacoes sao gravados na MESMA abertura da area de transferencia; qualquer
    falha antes de libera-la esvazia a area ANTES de fecha-la (entrega desfeita: nada fica disponivel). Se esse
    esvaziamento falhar, o resultado e "limpeza NAO confirmada" (codigo 6).
  - Saida: so metadados (conta, tipo, passo TOTP reservado, validade, limpeza). NUNCA o valor nem o segredo-base.
  - Validade e limpeza: ao fim da validade (senha: --validade, padrao 30 s; totp: o menor entre --validade e o fim da
    janela aceita pelo servidor) a area e esvaziada SE ainda contiver o valor entregue (leitura, comparacao e
    esvaziamento na mesma abertura; conteudo colocado depois por outra aplicacao e preservado). A tentativa de limpeza
    esta numa FINALIZACAO: cobre tambem interrupcao (Ctrl+C, codigo 5) e erro durante a espera.
    LIMITACAO: encerramento forcado do processo (kill, fim de tarefa) ou queda da maquina NAO executam a finalizacao;
    nesses casos a limpeza NAO acontece e o valor pode ficar na area de transferencia ate ser substituido.
  - TOTP e anti-replay (servidor: passo aceito em agora-1..agora+1 e so se maior que o ultimo ACEITO pelo servidor):
    o passo entregue e agora+1 (maior validade) e maior que o ultimo_passo da conta. ultimo_passo e o ultimo passo
    RESERVADO/ENTREGUE pelas ferramentas (esta e as jornadas), nao necessariamente aceito pelo servidor; e gravado
    ANTES da entrega e continua reservado mesmo se a entrega falhar (um passo nunca e entregue duas vezes). Se o
    proximo passo ainda nao estiver ao alcance, ESPERA por ele (no maximo 35 s). Nao ha nova tentativa automatica.
  - totp-reuso (F6a-V-T): entrega o codigo de um passo JA ACEITO pelo servidor, para uma recusa DETERMINISTICA do
    step-up pelo anti-replay (passo <= last_used_step = sempre recusado). So com a autorizacao de uso unico gravada por
    testes/validar_f6a.py anti-replay em <segredos>/frontend/f6a/reuso_<conta>.json, que conferiu no banco o
    last_used_step do fator. Exige que o passo autorizado seja EXATAMENTE o ultimo entregue (nenhuma entrega depois da
    conferencia) e <= o last_used_step conferido. A autorizacao e CONSUMIDA (removida) ANTES da entrega; o ultimo_passo
    reservado nao muda. Nunca reserva passo novo.
Codigos: 0 entregue e limpo (ou ja vazio/substituido); 2 recusado; 3 area de transferencia indisponivel; 4 entrega falhou e
foi desfeita (nada disponivel); 5 interrompido (limpeza feita, conforme a mensagem); 6 limpeza NAO confirmada.
"""
import argparse
import base64
import datetime
import hashlib
import hmac
import json
import os
import secrets
import struct
import sys
import time

PASSO = 30
NAVEGACAO = 'navegacao'
MARCAS = ('ExcludeClipboardContentFromMonitorProcessing', 'CanIncludeInClipboardHistory', 'CanUploadToCloudClipboard')


def totp(segredo_b32, passo):
    chave = base64.b32decode(segredo_b32 + '=' * (-len(segredo_b32) % 8))
    d = hmac.new(chave, struct.pack('>Q', passo), hashlib.sha1).digest()
    o = d[-1] & 0x0F
    return f'{(struct.unpack(">I", d[o:o + 4])[0] & 0x7FFFFFFF) % 1000000:06d}'


def grava_atomico(caminho, dados):
    tmp = f'{caminho}.tmp-{os.getpid()}-{secrets.token_hex(4)}'
    with open(tmp, 'x', encoding='utf-8') as h:
        h.write(json.dumps(dados, ensure_ascii=False, indent=1))
        h.flush()
        os.fsync(h.fileno())
    os.replace(tmp, caminho)


class Recusa(Exception):
    pass


class EntregaDesfeita(Exception):
    """A entrega falhou e a area foi esvaziada antes de ser liberada: nada ficou disponivel."""


class LimpezaNaoConfirmada(Exception):
    """Nao foi possivel confirmar o esvaziamento: o valor PODE estar na area de transferencia."""


def conta_de_navegacao(segredos, conta):
    """Entrada do plano da conta, SO se for de navegacao (a recusa nao le nenhuma credencial alem do plano)."""
    p = os.path.join(segredos, 'frontend', 'semeadura.json')
    if not os.path.isfile(p):
        raise Recusa('plano da semeadura ausente')
    with open(p, encoding='utf-8') as h:
        plano = json.load(h)
    c = next((x for x in plano['contas'] if x['chave'] == conta), None)
    if c is None:
        raise Recusa(f'conta desconhecida: {conta}')
    if c.get('uso') != NAVEGACAO:
        raise Recusa(f'conta {conta} nao e de navegacao (uso={c.get("uso")}): recusada')
    return c


def escolhe_passo(ultimo, agora):
    """Passo a entregar (agora+1, maior que o ultimo reservado) ou None se ainda fora do alcance do servidor."""
    p = int(agora) // PASSO
    cand = max(p + 1, ultimo + 1)
    return cand if cand <= p + 1 else None


# ------------------------------------------------------------------------------------------ area de transferencia
class AreaBase:
    """Logica de entrega e limpeza sobre primitivas (_abre, _fecha, _esvazia, _texto, _marca, _le_texto). As
    primitivas do Windows estao em AreaWindows; os testes usam primitivas em memoria com falhas injetaveis."""

    def coloca(self, valor):
        try:
            self._abre()
        except Exception as e:
            raise EntregaDesfeita(f'area de transferencia nao abriu ({e.__class__.__name__}); nada gravado') from None
        try:
            try:
                self._esvazia()
                self._texto(valor)
                for nome in MARCAS:
                    self._marca(nome)
            except BaseException as e:
                # ainda com a area ABERTA: desfaz antes de libera-la (inclusive em interrupcao)
                try:
                    self._esvazia()
                except Exception:
                    raise LimpezaNaoConfirmada(f'falha na entrega ({e.__class__.__name__}) e no esvaziamento') from None
                if isinstance(e, Exception):
                    raise EntregaDesfeita(f'falha na entrega ({e.__class__.__name__}); area esvaziada antes de liberar') from None
                raise
        finally:
            try:
                self._fecha()
            except Exception as e:
                raise LimpezaNaoConfirmada(f'area de transferencia nao fechou ({e.__class__.__name__})') from None

    def limpa_se(self, valor):
        """'limpa' (era o valor entregue: esvaziada), 'vazia' (nada a limpar) ou 'substituida' (outra aplicacao
        colocou algo: preservado). Leitura, comparacao e esvaziamento na MESMA abertura. Falha: LimpezaNaoConfirmada."""
        try:
            self._abre()
        except Exception as e:
            raise LimpezaNaoConfirmada(f'area de transferencia nao abriu ({e.__class__.__name__})') from None
        try:
            try:
                atual = self._le_texto()
                if atual is None:
                    return 'vazia'
                if atual != valor:
                    return 'substituida'
                self._esvazia()
                return 'limpa'
            except Exception as e:
                raise LimpezaNaoConfirmada(f'falha ao conferir/esvaziar ({e.__class__.__name__})') from None
        finally:
            try:
                self._fecha()
            except Exception:
                pass


class AreaWindows(AreaBase):
    """Area de transferencia do Windows por ctypes (sem processo filho: o valor nao passa por linha de comando)."""

    def __init__(self):
        import ctypes
        from ctypes import wintypes
        self.c = ctypes
        self.u = ctypes.WinDLL('user32', use_last_error=True)
        self.k = ctypes.WinDLL('kernel32', use_last_error=True)
        self.u.OpenClipboard.argtypes = [wintypes.HWND]
        self.u.SetClipboardData.argtypes = [wintypes.UINT, wintypes.HANDLE]
        self.u.SetClipboardData.restype = wintypes.HANDLE
        self.u.GetClipboardData.argtypes = [wintypes.UINT]
        self.u.GetClipboardData.restype = wintypes.HANDLE
        self.u.RegisterClipboardFormatW.argtypes = [wintypes.LPCWSTR]
        self.u.RegisterClipboardFormatW.restype = wintypes.UINT
        self.k.GlobalAlloc.argtypes = [wintypes.UINT, ctypes.c_size_t]
        self.k.GlobalAlloc.restype = wintypes.HGLOBAL
        self.k.GlobalLock.argtypes = [wintypes.HGLOBAL]
        self.k.GlobalLock.restype = wintypes.LPVOID
        self.k.GlobalUnlock.argtypes = [wintypes.HGLOBAL]

    def _abre(self):
        for _ in range(20):
            if self.u.OpenClipboard(None):
                return
            time.sleep(0.05)
        raise OSError('area de transferencia ocupada')

    def _fecha(self):
        self.u.CloseClipboard()

    def _esvazia(self):
        if not self.u.EmptyClipboard():
            raise OSError('EmptyClipboard')

    def _global(self, dados):
        h = self.k.GlobalAlloc(0x0002, len(dados))   # GMEM_MOVEABLE
        if not h:
            raise OSError('GlobalAlloc')
        p = self.k.GlobalLock(h)
        self.c.memmove(p, dados, len(dados))
        self.k.GlobalUnlock(h)
        return h

    def _texto(self, valor):
        if not self.u.SetClipboardData(13, self._global((valor + '\0').encode('utf-16-le'))):   # CF_UNICODETEXT
            raise OSError('SetClipboardData texto')

    def _marca(self, nome):
        f = self.u.RegisterClipboardFormatW(nome)
        if not f or not self.u.SetClipboardData(f, self._global(struct.pack('<I', 0))):
            raise OSError(f'formato {nome}')

    def _le_texto(self):
        h = self.u.GetClipboardData(13)
        if not h:
            return None
        p = self.k.GlobalLock(h)
        try:
            return self.c.wstring_at(p)
        finally:
            self.k.GlobalUnlock(h)

    def le(self):
        self._abre()
        try:
            return self._le_texto()
        finally:
            self._fecha()


def area_padrao():
    if os.name != 'nt':
        raise OSError('so Windows')
    return AreaWindows()


# ------------------------------------------------------------------------------------------------------ entrega
def agora_utc(t):
    return datetime.datetime.fromtimestamp(t, datetime.timezone.utc).strftime('%H:%M:%SZ')


def entrega(segredos, conta, tipo, validade, area, escreve=print, relogio=None, dorme=None):
    relogio = relogio or time.time
    dorme = dorme or time.sleep
    c = conta_de_navegacao(segredos, conta)
    if tipo == 'totp-reuso':
        arq = os.path.join(segredos, 'frontend', 'contas', f'{conta}.json')
        aut = os.path.join(segredos, 'frontend', 'f6a', f'reuso_{conta}.json')
        if not os.path.isfile(arq):
            raise Recusa(f'conta {conta} sem MFA cadastrado')
        with open(arq, encoding='utf-8') as h:
            m = json.load(h)
        if m.get('estado') != 'confirmado' or not m.get('totp'):
            raise Recusa(f'conta {conta} sem MFA confirmado')
        if not os.path.isfile(aut):
            raise Recusa('sem autorizacao de reuso (rode validar_f6a.py anti-replay antes)')
        with open(aut, encoding='utf-8') as h:
            r = json.load(h)
        passo, aceito = r.get('passo'), r.get('last_used_step')
        if not isinstance(passo, int) or not isinstance(aceito, int) or passo > aceito:
            raise Recusa('autorizacao de reuso invalida (passo maior que o ultimo aceito pelo servidor)')
        if passo != m.get('ultimo_passo'):
            raise Recusa(f'o ultimo passo entregue ({m.get("ultimo_passo")}) nao e o autorizado ({passo}): houve entrega depois da conferencia')
        try:
            os.remove(aut)                     # USO UNICO: consumida ANTES da entrega
        except OSError as e:
            raise Recusa(f'autorizacao de reuso nao consumida ({e.__class__.__name__}); nada entregue') from None
        valor = totp(m['totp'], passo)
        fim = relogio() + validade
        meta = f'conta={conta} tipo=totp-reuso passo={passo} (ja aceito pelo servidor: last_used_step={aceito}; recusa deterministica)'
    elif tipo == 'senha':
        valor = c['senha']
        fim = relogio() + validade
        meta = f'conta={conta} tipo=senha'
    else:
        arq = os.path.join(segredos, 'frontend', 'contas', f'{conta}.json')
        if not os.path.isfile(arq):
            raise Recusa(f'conta {conta} sem MFA cadastrado')
        with open(arq, encoding='utf-8') as h:
            m = json.load(h)
        if m.get('estado') != 'confirmado' or not m.get('totp'):
            raise Recusa(f'conta {conta} sem MFA confirmado')
        limite = relogio() + 35
        while True:
            t = relogio()
            cand = escolhe_passo(m.get('ultimo_passo', -1), t)
            if cand is not None:
                break
            if t >= limite:
                raise Recusa('proximo passo TOTP fora do alcance em 35 s')
            dorme(1)
        anterior = m.get('ultimo_passo', -1)
        m['ultimo_passo'] = cand              # RESERVADO antes da entrega: nunca entregue duas vezes, mesmo se falhar
        grava_atomico(arq, m)
        valor = totp(m['totp'], cand)
        fim = min(t + validade, (cand + 2) * PASSO)   # o servidor aceita cand ate o seu passo atual ser cand+1
        meta = f'conta={conta} tipo=totp passo reservado={cand} (reservado antes: {anterior})'
    desfeita = False
    try:
        try:
            area.coloca(valor)
        except (EntregaDesfeita, LimpezaNaoConfirmada):
            desfeita = True      # a propria entrega ja esvaziou (ou relata que nao confirmou)
            raise
        escreve(f'ENTREGUE na area de transferencia (fora do historico): {meta}; valido ate {agora_utc(fim)}; colar com ctrl+v')
        while relogio() < fim:
            dorme(min(1, max(0.0, fim - relogio())))
    finally:
        # FINALIZACAO: fim da espera, interrupcao (inclusive logo depois de colocar) ou erro. So esvazia se a area ainda
        # tiver o valor entregue. NAO cobre encerramento forcado do processo nem queda da maquina.
        try:
            if not desfeita:
                r = area.limpa_se(valor)
                escreve({'limpa': f'area de transferencia LIMPA as {agora_utc(relogio())}',
                         'vazia': f'area de transferencia vazia as {agora_utc(relogio())} (nada a limpar)',
                         'substituida': f'area de transferencia ja substituida por outra aplicacao as {agora_utc(relogio())} (preservada; nada a limpar)'}[r])
        except LimpezaNaoConfirmada as e:
            escreve(f'limpeza NAO confirmada as {agora_utc(relogio())}: {e}')
            raise
        finally:
            valor = None
    return 0


def main(argv=None, area_fabrica=None):
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('--segredos', required=True)
    ap.add_argument('conta')
    ap.add_argument('tipo', choices=['senha', 'totp', 'totp-reuso'])
    ap.add_argument('--validade', type=int, default=30)
    a = ap.parse_args(argv)
    if not 5 <= a.validade <= 60:
        print('RECUSADO: --validade entre 5 e 60 s', file=sys.stderr)
        return 2
    try:
        area = (area_fabrica or area_padrao)()
    except OSError as e:
        print(f'RECUSADO: area de transferencia indisponivel ({e}); a responsavel digita', file=sys.stderr)
        return 3
    try:
        return entrega(a.segredos, a.conta, a.tipo, a.validade, area, escreve=lambda x: print(x, flush=True))
    except Recusa as e:
        print(f'RECUSADO: {e}', file=sys.stderr)
        return 2
    except EntregaDesfeita as e:
        print(f'FALHA: {e}; nada ficou disponivel (passo TOTP, se houver, continua reservado)', file=sys.stderr)
        return 4
    except LimpezaNaoConfirmada as e:
        print(f'FALHA: limpeza NAO confirmada ({e}); o valor PODE estar na area de transferencia: esvazie-a antes de '
              'continuar (passo TOTP, se houver, continua reservado)', file=sys.stderr)
        return 6
    except KeyboardInterrupt:
        print('INTERROMPIDO: limpeza tentada na finalizacao (ver a linha anterior)', file=sys.stderr)
        return 5


if __name__ == '__main__':
    sys.exit(main())
