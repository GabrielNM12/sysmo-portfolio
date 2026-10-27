-- S5: notas de entrada gravadas pelo portal e situação dos pedidos de compra
select n.id, n.numero, n.status, n.valor_total, n.pedido_compra_id, n.data_entrada
  from erp.nota_entrada n
 order by n.id desc
 limit 5;

select id, status, empresa_id from erp.pedido_compra where status <> 'ABERTO' order by id;
