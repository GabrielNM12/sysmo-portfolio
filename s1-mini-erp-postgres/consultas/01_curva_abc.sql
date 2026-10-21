-- Curva ABC por faturamento (últimos 90 dias) em cada loja.
-- A = itens que somam 80% da venda, B = próximos 15%, C = restante.
with venda as (
    select empresa_id, produto_id, sum(valor_venda) as valor
      from erp.mv_venda_diaria
     where dia >= current_date - 90
     group by 1, 2
), acumulado as (
    select *, sum(valor) over (partition by empresa_id order by valor desc, produto_id)
              / sum(valor) over (partition by empresa_id) as perc_acum
      from venda
)
select e.fantasia as loja,
       case when perc_acum <= 0.80 then 'A' when perc_acum <= 0.95 then 'B' else 'C' end as curva,
       count(*)                          as skus,
       round(sum(valor), 2)              as faturamento,
       round(100.0 * count(*) / sum(count(*)) over (partition by e.fantasia), 1) as perc_skus,
       round(100.0 * sum(valor) / sum(sum(valor)) over (partition by e.fantasia), 1) as perc_faturamento
  from acumulado a
  join erp.empresa e on e.id = a.empresa_id
 group by 1, 2
 order by 1, 2;
