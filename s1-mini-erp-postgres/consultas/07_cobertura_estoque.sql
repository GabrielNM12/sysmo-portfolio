-- Dias de cobertura do estoque por departamento e loja, e capital parado em excesso (> 45 dias).
select loja, departamento,
       count(*)                                                     as skus,
       round(sum(estoque_atual * custo_medio), 2)                   as estoque_a_custo,
       round(percentile_cont(0.5) within group (order by cobertura_dias)::numeric, 1) as cobertura_mediana_dias,
       count(*) filter (where cobertura_dias > 45)                  as skus_excesso,
       round(sum((estoque_atual - venda_media_dia * 45) * custo_medio) filter (where cobertura_dias > 45), 2) as capital_em_excesso
  from erp.vw_produto_loja_situacao
 where venda_media_dia > 0
 group by 1, 2
 order by capital_em_excesso desc nulls last
 limit 20;
