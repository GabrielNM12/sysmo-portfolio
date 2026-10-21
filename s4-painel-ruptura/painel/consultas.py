"""Consultas somente leitura sobre a base do ERP (S1).

Todas leem a visão materializada mv_venda_diaria (atualizada à noite) e o saldo atual de
produto_loja. Nenhuma consulta varre cupom_item: o painel não pode competir com os PDVs.
"""
from __future__ import annotations

from contextlib import contextmanager
from decimal import Decimal

import psycopg
from psycopg.rows import dict_row

PADROES = {"RUPTURA_PROB_MAX": "0.001", "MARGEM_MINIMA_PERC": "8", "VALIDADE_ALERTA_DIAS": "5"}

SQL_LOJAS = "select id, fantasia, cidade, num_pdvs from empresa where tipo = 'LOJA' and ativa order by id"

SQL_PARAMETROS = "select chave, valor from parametro_empresa where empresa_id = %(loja)s"

# Ruptura estatística. Se um item é comprado em média λ vezes por dia (eventos = cupons, não unidades:
# um cliente que leva 6 latas é UMA compra), a chance de passar d dias sem nenhuma compra por acaso
# é e^(−λ·d) (Poisson). λ vem de uma janela "limpa" (−35 a −8 dias) para a própria ruptura não
# puxar a média para baixo. Como milhares de itens são testados todo dia, o limite é rígido (0,1%).
SQL_RUPTURA = """
with base as (
    select produto_id,
           sum(cupons)     filter (where dia between current_date - 35 and current_date - 8) / 28.0 as lambda,
           sum(quantidade) filter (where dia between current_date - 35 and current_date - 8) / 28.0 as unidades_dia,
           max(dia) as ultima_venda
      from mv_venda_diaria
     where empresa_id = %(loja)s and dia >= current_date - 35
     group by produto_id
), avaliado as (
    select b.*, (current_date - 1) - b.ultima_venda as dias_sem_venda
      from base b
     where b.lambda >= %(lambda_min)s
)
select a.produto_id, p.descricao, s.nome as secao,
       round(a.unidades_dia, 2)                    as venda_media_dia,
       round(a.lambda, 2)                          as compras_dia,
       a.dias_sem_venda,
       exp(-a.lambda * a.dias_sem_venda)           as prob_acaso,
       pl.estoque_atual, pl.preco_venda,
       round(a.unidades_dia * pl.preco_venda, 2)   as venda_perdida_dia
  from avaliado a
  join produto_loja pl on pl.empresa_id = %(loja)s and pl.produto_id = a.produto_id
  join produto p on p.id = a.produto_id
  join secao s on s.id = p.secao_id
 where a.dias_sem_venda > 0
   and exp(-a.lambda * a.dias_sem_venda) < %(prob_max)s
 order by venda_perdida_dia desc
"""

SQL_MARGEM = """
select produto_id, descricao, secao, preco_venda, custo_medio, custo_ultima_entrada, margem_perc,
       venda_media_dia, estoque_atual,
       round((preco_venda - custo_ultima_entrada) / nullif(preco_venda, 0) * 100, 2) as margem_reposicao_perc
  from vw_produto_loja_situacao
 where empresa_id = %(loja)s
   and venda_media_dia > 0
   and (margem_perc < %(margem_min)s or custo_ultima_entrada > preco_venda)
 order by margem_perc
"""

# Validade com projeção FEFO: não basta o lote estar perto de vencer (pão vence em 2 dias e vende
# tudo). Alerta só a SOBRA prevista: quantidade acumulada até o lote menos o que deve vender até lá.
SQL_VALIDADE = """
with lotes as (
    select l.*, sum(l.quantidade) over (partition by l.produto_id order by l.validade, l.id) as acumulado
      from produto_lote l
     where l.empresa_id = %(loja)s and l.quantidade > 0
       and l.validade <= current_date + %(dias)s
), media as (
    select produto_id, sum(quantidade) / 14.0 as por_dia
      from mv_venda_diaria
     where empresa_id = %(loja)s and dia >= current_date - 14
     group by produto_id
), projecao as (
    select l.*, coalesce(m.por_dia, 0) as venda_dia,
           greatest(0, least(l.quantidade,
               l.acumulado - coalesce(m.por_dia, 0) * greatest(l.validade - current_date + 1, 0))) as sobra
      from lotes l left join media m using (produto_id)
)
select pr.produto_id, p.descricao, s.nome as secao, pr.lote, pr.validade,
       pr.validade - current_date as dias_para_vencer, pr.quantidade,
       round(pr.venda_dia, 3)     as venda_dia,
       round(pr.sobra, 3)         as sobra_prevista,
       round(pr.sobra * pl.custo_medio, 2) as valor_custo
  from projecao pr
  join produto p       on p.id = pr.produto_id
  join secao s         on s.id = p.secao_id
  join produto_loja pl on pl.empresa_id = %(loja)s and pl.produto_id = pr.produto_id
 where pr.sobra > 0.001
 order by pr.validade, valor_custo desc
"""

SQL_KPIS = """
with d as (
    select dia, sum(valor_venda) as venda, sum(valor_custo) as custo, sum(cupons) as itens
      from mv_venda_diaria
     where empresa_id = %(loja)s and dia >= current_date - 35
     group by dia
)
select (select venda from d where dia = current_date - 1)                         as venda_ontem,
       (select venda from d where dia = current_date - 8)                         as venda_semana_passada,
       (select sum(venda) from d where dia >= current_date - 30)                  as venda_30d,
       (select round(100 * (1 - sum(custo) / nullif(sum(venda), 0)), 2) from d where dia >= current_date - 30) as margem_30d,
       (select count(*) from cupom where empresa_id = %(loja)s and data_hora >= current_date - 1
           and data_hora < current_date and not cancelado)                        as cupons_ontem
"""

SQL_SERIE = """
select dia, round(sum(valor_venda), 2) as venda,
       round(100 * (1 - sum(valor_custo) / nullif(sum(valor_venda), 0)), 2) as margem
  from mv_venda_diaria
 where empresa_id = %(loja)s and dia >= current_date - 42
 group by dia order by dia
"""

SQL_DEPARTAMENTOS = """
select d.nome as departamento, round(sum(v.valor_venda), 2) as venda,
       round(100 * (1 - sum(v.valor_custo) / nullif(sum(v.valor_venda), 0)), 2) as margem
  from mv_venda_diaria v
  join produto p      on p.id = v.produto_id
  join secao s        on s.id = p.secao_id
  join departamento d on d.id = s.departamento_id
 where v.empresa_id = %(loja)s and v.dia >= current_date - 30
 group by d.nome order by venda desc
"""


class Repositorio:
    """Acesso ao ERP. Conexão nova por operação: o painel é de baixa frequência e não segura conexão."""

    def __init__(self, database_url: str):
        self.database_url = database_url

    @contextmanager
    def _cursor(self):
        with psycopg.connect(self.database_url, autocommit=True, options="-c search_path=erp") as conn:
            with conn.cursor(row_factory=dict_row) as cur:
                yield cur

    def _todos(self, sql: str, params: dict | None = None) -> list[dict]:
        with self._cursor() as cur:
            cur.execute(sql, params or {})
            return cur.fetchall()

    def lojas(self) -> list[dict]:
        return self._todos(SQL_LOJAS)

    def parametros(self, loja: int) -> dict[str, str]:
        valores = dict(PADROES)
        valores.update({r["chave"]: r["valor"] for r in self._todos(SQL_PARAMETROS, {"loja": loja})})
        return valores

    def ruptura(self, loja: int, prob_max: float = 0.001, lambda_min: float = 0.5) -> list[dict]:
        return self._todos(SQL_RUPTURA, {"loja": loja, "prob_max": prob_max, "lambda_min": lambda_min})

    def margem(self, loja: int, margem_min: Decimal) -> list[dict]:
        return self._todos(SQL_MARGEM, {"loja": loja, "margem_min": margem_min})

    def validade(self, loja: int, dias: int) -> list[dict]:
        return self._todos(SQL_VALIDADE, {"loja": loja, "dias": dias})

    def kpis(self, loja: int) -> dict:
        return self._todos(SQL_KPIS, {"loja": loja})[0]

    def serie(self, loja: int) -> list[dict]:
        return self._todos(SQL_SERIE, {"loja": loja})

    def departamentos(self, loja: int) -> list[dict]:
        return self._todos(SQL_DEPARTAMENTOS, {"loja": loja})
