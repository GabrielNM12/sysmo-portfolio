-- S5: fornecedores com pedido de compra em aberto (use o CNPJ no login do portal, código 1234)
select t.cpf_cnpj as cnpj, t.nome, count(*) as pedidos_abertos,
       string_agg(distinct e.fantasia, ', ') as destinos
  from erp.pedido_compra pc
  join erp.transacionador t on t.id = pc.fornecedor_id
  join erp.empresa e on e.id = pc.empresa_id
 where pc.status = 'ABERTO'
 group by 1, 2
 order by 3 desc
 limit 5;
