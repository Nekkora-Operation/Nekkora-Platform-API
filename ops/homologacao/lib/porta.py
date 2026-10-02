"""Elegibilidade da porta publicada pelo gateway da homologacao (R2b-1a, achado F-R2b1-1).

  python porta.py elegivel <endereco> <porta>
      PORTA_APTA (codigo 0) so se TODAS as conferencias passarem; PORTA_INDISPONIVEL (codigo 1) se qualquer uma indicar
      indisponibilidade ou nao puder ser feita; codigo 3 para argumentos invalidos. Uma linha por conferencia:
        1. listener: nenhuma conexao TCP aceita em <endereco>:<porta> (porta em uso);
        2. bind: o host consegue REALMENTE abrir <endereco>:<porta> agora (socket exclusivo, liberado em seguida). E a
           conferencia de maior peso: no Windows, uma porta sem listener pode estar numa faixa reservada pelo sistema
           (Hyper-V/WinNAT) e recusar o bind ("forbidden by its access permissions", WSAEACCES) - foi o STOP da R2b-1;
        3. Windows: a porta nao pertence a nenhuma faixa de "netsh interface ipv4 show excludedportrange protocol=tcp".
           Diagnostico e explicacao; se a tabela NAO puder ser lida no Windows (comando ausente, codigo != 0, saida sem a
           tabela), FALHA FECHADA. Fora do Windows: "nao se aplica".
      Nao cria recurso Docker nem altera reservas do sistema.

Origem: a pre-condicao anterior do criar so conferia container publicando a porta e listener (/dev/tcp); na R2b-1 a
porta 56721 estava sem uso, mas dentro da faixa reservada 56626-56725, e o gateway nao subiu (concluir-criacao).
"""
import os
import re
import socket
import subprocess
import sys

WINDOWS = os.name == 'nt'
NETSH = ['netsh', 'interface', 'ipv4', 'show', 'excludedportrange', 'protocol=tcp']


def faixas_excluidas(texto):
    """Faixas (inicio, fim) da saida do netsh, em qualquer idioma. None se a saida nao contem a tabela: exige a linha de
    tracos que separa o cabecalho das linhas (presente mesmo com a tabela vazia)."""
    linhas = texto.replace('\r', '').splitlines()
    sep = [i for i, l in enumerate(linhas) if re.fullmatch(r'\s*-{3,}\s+-{3,}\s*', l)]
    if len(sep) != 1:
        return None
    faixas = []
    for l in linhas[sep[0] + 1:]:
        m = re.fullmatch(r'\s*(\d{1,5})\s+(\d{1,5})(\s+\*)?\s*', l)
        if m:
            a, b = int(m.group(1)), int(m.group(2))
            if not (0 < a <= b <= 65535):
                return None
            faixas.append((a, b))
        elif l.strip() and not l.lstrip().startswith('*'):
            if faixas:   # texto depois das faixas so pode ser a legenda ("* - ...")
                return None
    return faixas


def conf_listener(endereco, porta, timeout=0.5):
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(timeout)
    try:
        s.connect((endereco, porta))
        return False, 'conexao aceita: porta em uso'
    except OSError as e:
        return True, f'sem listener ({e.__class__.__name__})'
    finally:
        s.close()


def conf_bind(endereco, porta):
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        if WINDOWS and hasattr(socket, 'SO_EXCLUSIVEADDRUSE'):
            s.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)   # sem compartilhamento: bind de verdade
        s.bind((endereco, porta))
        s.listen(1)
        return True, 'bind e listen aceitos (liberado em seguida)'
    except OSError as e:
        msg = (e.strerror or '').encode('ascii', 'replace').decode('ascii')   # mensagem do sistema localizada: so ASCII
        return False, f'bind recusado: errno={e.errno} winerror={getattr(e, "winerror", None)} {msg}'
    finally:
        s.close()


def conf_excluidas(porta, executar=None):
    if not WINDOWS and executar is None:
        return True, 'nao se aplica (host nao Windows)'
    try:
        r = (executar or (lambda: subprocess.run(NETSH, capture_output=True, text=True, errors='replace', timeout=20)))()
    except (OSError, subprocess.SubprocessError) as e:
        return False, f'tabela de exclusoes ilegivel ({e.__class__.__name__}): falha fechada'
    if r.returncode != 0:
        return False, f'netsh codigo {r.returncode}: falha fechada'
    faixas = faixas_excluidas(r.stdout)
    if faixas is None:
        return False, 'saida do netsh sem a tabela de exclusoes: falha fechada'
    dentro = [f'{a}-{b}' for a, b in faixas if a <= porta <= b]
    if dentro:
        return False, f'porta na faixa reservada {",".join(dentro)} ({len(faixas)} faixas no total)'
    return True, f'fora das {len(faixas)} faixas reservadas'


def elegivel(endereco, porta, listener=conf_listener, bind=conf_bind, excluidas=conf_excluidas):
    res = [('listener', *listener(endereco, porta)), ('bind', *bind(endereco, porta)), ('excludedportrange', *excluidas(porta))]
    return all(ok for _, ok, _ in res), res


def main(argv):
    if len(argv) != 4 or argv[1] != 'elegivel' or not re.fullmatch(r'\d{1,5}', argv[3]) or not (1 <= int(argv[3]) <= 65535):
        print('uso: porta.py elegivel <endereco> <porta 1-65535>', file=sys.stderr)
        return 3
    try:
        socket.inet_aton(argv[2])
    except OSError:
        print(f'endereco invalido: {argv[2]}', file=sys.stderr)
        return 3
    ok, res = elegivel(argv[2], int(argv[3]))
    for nome, r_ok, det in res:
        print(f'{nome}|{"OK" if r_ok else "FALHA"}|{det}')
    print(f'{"PORTA_APTA" if ok else "PORTA_INDISPONIVEL"} {argv[2]}:{argv[3]}')
    return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main(sys.argv))
