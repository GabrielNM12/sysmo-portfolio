-- Quebra (perda) de perecíveis por seção e motivo, em valor de custo e % sobre a venda.
with quebra as (
    select p.secao_id, m.motivo, sum(-m.quantidade * pl.custo_medio) as valor_custo
      from erp.estoque_movimento m
      join erp.produto p       on p.id = m.produto_id
      join erp.produto_loja pl on pl.empresa_id = m.empresa_id and pl.produto_id = m.produto_id
     where m.tipo = 'QUEBRA'
     group by 1, 2
), venda as (
    select p.secao_id, sum(v.valor_venda) as venda
      from erp.mv_venda_diaria v join erp.produto p on p.id = v.produto_id
     group by 1
)
select s.nome as secao, q.motivo,
       round(q.valor_custo, 2)                                   as quebra_custo,
       round(100 * q.valor_custo / v.venda, 2)                   as perc_sobre_venda,
       round(100 * sum(q.valor_custo) over (partition by s.nome) / v.venda, 2) as perc_total_secao
  from quebra q
  join erp.secao s on s.id = q.secao_id
  join venda v     on v.secao_id = q.secao_id
 order by perc_total_secao desc, quebra_custo desc;
