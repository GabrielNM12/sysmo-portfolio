-- Participação das formas de pagamento (base para conciliação com adquirentes).
select forma,
       coalesce(bandeira, '-')                                    as bandeira,
       count(*)                                                   as transacoes,
       round(sum(valor), 2)                                       as valor,
       round(100.0 * sum(valor) / sum(sum(valor)) over (), 2)     as participacao,
       round(avg(valor), 2)                                       as ticket
  from erp.cupom_pagamento
 group by rollup (forma, bandeira)
having grouping(bandeira) = 1 or forma in ('CREDITO', 'DEBITO')
 order by forma nulls last, valor desc;
