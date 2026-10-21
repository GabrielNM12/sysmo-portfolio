-- Indicadores de PDV por loja: cupons/dia, ticket médio, itens por cupom, % com CPF identificado.
with itens as (
    select cupom_id, count(*) as itens from erp.cupom_item group by 1
)
select e.fantasia                                         as loja,
       e.num_pdvs                                         as pdvs,
       round(count(*) / count(distinct c.data_hora::date)::numeric, 0) as cupons_dia,
       round(avg(c.valor_total), 2)                       as ticket_medio,
       round(avg(i.itens), 1)                             as itens_por_cupom,
       round(100.0 * count(c.transacionador_id) / count(*), 1) as perc_cliente_identificado,
       round(100.0 * count(*) filter (where c.cancelado) / count(*), 2) as perc_cancelados
  from erp.cupom c
  join erp.empresa e on e.id = c.empresa_id
  join itens i       on i.cupom_id = c.id
 group by e.fantasia, e.num_pdvs
 order by ticket_medio desc;
