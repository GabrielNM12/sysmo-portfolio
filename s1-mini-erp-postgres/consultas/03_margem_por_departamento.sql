-- Margem bruta (venda − CMV) por departamento e participação de itens em promoção.
select d.nome                                                       as departamento,
       round(sum(v.valor_venda), 2)                                 as venda,
       round(sum(v.valor_custo), 2)                                 as cmv,
       round(100 * (1 - sum(v.valor_custo) / sum(v.valor_venda)), 2) as margem_perc,
       round(100.0 * sum(v.valor_venda) filter (where v.teve_promocao) / sum(v.valor_venda), 1) as perc_venda_em_promocao,
       round(100.0 * sum(v.valor_venda) / sum(sum(v.valor_venda)) over (), 1) as participacao
  from erp.mv_venda_diaria v
  join erp.produto p      on p.id = v.produto_id
  join erp.secao s        on s.id = p.secao_id
  join erp.departamento d on d.id = s.departamento_id
 group by 1
 order by venda desc;
