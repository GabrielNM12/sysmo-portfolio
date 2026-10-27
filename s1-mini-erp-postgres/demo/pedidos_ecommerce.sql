-- S2: últimos pedidos de e-commerce integrados e as baixas de estoque que geraram
select id, numero_externo, tipo_entrega, valor_total, observacao
  from erp.pedido_venda
 order by id desc
 limit 5;

select m.produto_id, p.descricao, m.quantidade, m.documento
  from erp.estoque_movimento m
  join erp.produto p on p.id = m.produto_id
 where m.tipo = 'VENDA_ECOMMERCE'
 order by m.id desc
 limit 8;
