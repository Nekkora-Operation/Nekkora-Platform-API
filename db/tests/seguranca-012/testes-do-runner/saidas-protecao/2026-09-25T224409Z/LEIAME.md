# Teste direcionado da proteção operacional de `executa.sh`

1. `01_antes.txt`: dois recursos com nomes reservados pelos testes do runner, criados **por este teste**:
   - o volume `nekkora-mut-m1_nekkora_postgres_data`, com o arquivo `marcador.txt`;
   - o container `nekkora-mut-m4a-postgres`, criado e nunca iniciado.

   A linha `cat: can't open 'D:/marcador.txt'` é um artefato do Git Bash, que converte `/d/...` em caminho do Windows na leitura. A escrita do marcador funcionou, como mostra a leitura em `03_depois.txt`, feita com `MSYS_NO_PATHCONV=1`.
2. `02_execucao.txt`: `executa.sh` recusou na verificação prévia, com **exit 2**, listou os dois recursos e não executou nenhum caso.
3. `03_depois.txt`: volume e container **idênticos** (mesmo id e mesma data de criação); o marcador foi preservado; o container nunca foi iniciado (`StartedAt` zero, logo nada foi executado nele); nenhuma stack `nekkora-mut-*` nova e nenhuma pasta de saída nova.
4. `04_limpeza.txt`: remoção dos dois recursos criados pelo teste.
