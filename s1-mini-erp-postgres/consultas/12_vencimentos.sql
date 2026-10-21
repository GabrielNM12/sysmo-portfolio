-- Lotes que vencem nos próximos dias (ação: rebaixar preço, transferir, doar ou registrar quebra).
select e.fantasia as loja, s.nome as secao, p.descricao, l.lote, l.validade,
       l.validade - current_date                   as dias_para_vencer,
       l.quantidade,
       round(l.quantidade * pl.custo_medio, 2)     as valor_em_risco,
       case when l.validade < current_date then 'VENCIDO - retirar da área de venda'
            when l.validade - current_date <= 2 then 'Rebaixar preço hoje'
            else 'Planejar ação' end                as acao
  from erp.produto_lote l
  join erp.produto p       on p.id = l.produto_id
  join erp.secao s         on s.id = p.secao_id
  join erp.empresa e       on e.id = l.empresa_id
  join erp.produto_loja pl on pl.empresa_id = l.empresa_id and pl.produto_id = l.produto_id
 where l.quantidade > 0 and l.validade <= current_date + 5
 order by l.validade, valor_em_risco desc
 limit 25;
