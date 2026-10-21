#!/usr/bin/env bash
# Cria o mini-ERP do zero e carrega os dados sintéticos.
#   uso: ./scripts/carregar.sh [escala]        (escala 1 = 20 mil SKUs / ~1 milhão de itens vendidos)
# Conexão via variáveis padrão do PostgreSQL: PGHOST, PGPORT, PGUSER, PGPASSWORD, PGDATABASE
set -euo pipefail
cd "$(dirname "$0")/.."
ESCALA="${1:-1}"
PSQL=(psql -v ON_ERROR_STOP=1 -q -X)

inicio=$(date +%s)
echo "== Mini-ERP Sysmo-like · escala ${ESCALA} =="
for f in sql/00_schema.sql sql/01_funcoes.sql; do
  echo ">> $f"; "${PSQL[@]}" -f "$f" >/dev/null
done
echo ">> sql/02_seed_cadastros.sql"; "${PSQL[@]}" -v escala="$ESCALA" -f sql/02_seed_cadastros.sql >/dev/null
LOG="$(mktemp)"
if ! "${PSQL[@]}" -f sql/03_seed_movimento.sql >"$LOG" 2>&1; then
  grep -E '^>>|ERROR|psql:' "$LOG"; echo "Falha na carga de movimento (log: $LOG)"; exit 1
fi
grep -E '^>>' "$LOG"; rm -f "$LOG"
for f in sql/04_indices_views.sql sql/05_seguranca.sql; do
  echo ">> $f"; "${PSQL[@]}" -f "$f" >/dev/null
done
"${PSQL[@]}" -c "set client_min_messages = warning; drop schema if exists seed cascade" >/dev/null
echo
"${PSQL[@]}" -P footer=off -c "
select 'empresas' as tabela, count(*) as linhas from erp.empresa
union all select 'produtos', count(*) from erp.produto
union all select 'transacionadores', count(*) from erp.transacionador
union all select 'cupons', count(*) from erp.cupom
union all select 'itens vendidos', count(*) from erp.cupom_item
union all select 'movimentos de estoque', count(*) from erp.estoque_movimento
union all select 'pedidos de compra abertos', count(*) from erp.pedido_compra where status = 'ABERTO'
union all select 'pedidos de compra (itens)', count(*) from erp.pedido_compra_item"
echo "Carga concluída em $(( $(date +%s) - inicio ))s"
