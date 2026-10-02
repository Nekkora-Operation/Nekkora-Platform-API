"""Sanitizacao do INDICE da caixa do transporte de homologacao (desenho P1a, sec. 4.2) antes de ir para a evidencia.

  ... | python indice_caixa.py > caixa_indice.jsonl

Le o indice.jsonl da caixa pela entrada padrao e so o reemite se TODAS as linhas tiverem EXATAMENTE os campos
esperados, cada um no formato esperado (nenhum token, link, endereco ou texto livre cabe nesses formatos). Qualquer
linha fora do formato recusa o indice inteiro (codigo 1, nada na saida): a caixa e investigada pela ferramenta restrita,
nunca exportada.
"""
import json
import re
import sys

CAMPOS = {'arquivo', 'attempt_id', 'tipo', 'destinatario_sha256', 'desfecho_simulado', 'repeticao', 'simulado'}
TIPOS = {'PASSWORD_RECOVERY', 'NOTICE_RECOVERY_CODE_USED', 'NOTICE_MFA_RESET', 'NOTICE_PASSWORD_CHANGED', 'NOTICE_MFA_RESET_ADMIN_TARGET',
         'NOTICE_MFA_EMERGENCY_HOLDER', 'NOTICE_MFA_EMERGENCY_ADMIN', 'NOTICE_MFA_EMERGENCY_EXECUTED'}
DESFECHOS = {'-', 'ACEITO', 'ENFILEIRADO', 'RECUSADO', 'INCERTO', 'EXCECAO'}
UUID = r'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}'
ARQUIVO = re.compile(rf'^(avisos/{UUID}(\.repeticao-[0-9]{{1,4}})?|outras/[0-9]{{8}}T[0-9]{{9}}Z_[A-Z_]{{3,40}}_{UUID})\.json$')


def valida(obj):
    if not isinstance(obj, dict) or set(obj) != CAMPOS:
        return 'campos inesperados'
    if not isinstance(obj['arquivo'], str) or not ARQUIVO.match(obj['arquivo']):
        return 'arquivo fora do formato'
    if obj['attempt_id'] != '-' and not re.fullmatch(UUID, str(obj['attempt_id'])):
        return 'attempt_id fora do formato'
    if obj['tipo'] not in TIPOS:
        return 'tipo desconhecido'
    if not re.fullmatch(r'[0-9a-f]{64}', str(obj['destinatario_sha256'])):
        return 'hash do destinatario fora do formato'
    if obj['desfecho_simulado'] not in DESFECHOS:
        return 'desfecho desconhecido'
    if not isinstance(obj['repeticao'], bool) or obj['simulado'] is not True:
        return 'marcadores invalidos'
    if (obj['attempt_id'] == '-') != obj['arquivo'].startswith('outras/'):
        return 'attempt_id incoerente com o arquivo'
    return None


def main():
    saida = []
    for n, linha in enumerate(sys.stdin.read().splitlines(), 1):
        if not linha.strip():
            continue
        try:
            obj = json.loads(linha)
        except ValueError:
            print(f'indice recusado: linha {n} nao e JSON', file=sys.stderr)
            return 1
        erro = valida(obj)
        if erro:
            print(f'indice recusado: linha {n}: {erro}', file=sys.stderr)
            return 1
        saida.append(json.dumps({k: obj[k] for k in sorted(CAMPOS)}, separators=(',', ':')))
    sys.stdout.write(''.join(l + '\n' for l in saida))
    return 0


if __name__ == '__main__':
    sys.exit(main())
