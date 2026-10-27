-- S3: simula um reajuste de preço (+ R$ 2,00) num item de balança da loja 2 que não está em oferta,
-- para demonstrar o relatório de alterações da carga (balanca carga ... --anterior ...)
with alvo as (
    select p.id, p.codigo_balanca
      from erp.produto p
     cross join lateral erp.fn_preco_vigente(2::smallint, p.id) v
     where p.pesavel and not v.em_promocao
     order by p.codigo_balanca
     limit 1
)
update erp.produto_loja pl
   set preco_venda = pl.preco_venda + 2
  from alvo
 where pl.empresa_id = 2 and pl.produto_id = alvo.id
returning alvo.codigo_balanca as plu, pl.preco_venda as novo_preco;
