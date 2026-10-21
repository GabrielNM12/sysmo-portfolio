-- Segmentação RFM (recência, frequência, valor) dos clientes identificados – SEM dados pessoais:
-- o resultado sai por id do transacionador, pronto para um CRM que tenha base legal para contato.
with rfm as (
    select transacionador_id,
           current_date - max(data_hora)::date as recencia,
           count(*)                            as frequencia,
           sum(valor_total)                    as valor
      from erp.cupom
     where transacionador_id is not null and not cancelado
     group by 1
), notas as (
    select *, ntile(5) over (order by recencia desc) as r,
              ntile(5) over (order by frequencia)    as f,
              ntile(5) over (order by valor)         as m
      from rfm
)
select case
         when r >= 4 and f >= 4 and m >= 4 then '1 - Campeões'
         when r >= 3 and f >= 3             then '2 - Fiéis'
         when r >= 4 and f <= 2             then '3 - Novos/recentes'
         when r <= 2 and f >= 3             then '4 - Em risco (sumindo)'
         else                                    '5 - Ocasionais'
       end as segmento,
       count(*)                       as clientes,
       round(avg(frequencia), 1)      as compras_90d,
       round(avg(valor), 2)           as gasto_medio_90d,
       round(avg(recencia), 1)        as dias_desde_ultima_compra
  from notas
 group by 1
 order by 1;
