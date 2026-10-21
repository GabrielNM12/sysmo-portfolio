-- Kardex (extrato) de um produto numa loja, com saldo acumulado e evolução do custo médio.
-- Troque o produto/loja nos parâmetros: psql -v produto=123 -v loja=2 -f 11_kardex_custo_medio.sql
\if :{?produto} \else \set produto 0 \endif
\if :{?loja}    \else \set loja 2   \endif
with alvo as (   -- se nenhum produto for informado, pega um que sofreu reajuste de custo
    select coalesce(nullif(:produto, 0), (
        select produto_id from erp.vw_produto_loja_situacao
         where empresa_id = :loja and margem_perc < 0 order by venda_media_dia desc limit 1)) as produto_id
)
select k.data_hora::date as data, k.tipo, k.quantidade, k.custo_unitario, k.saldo, k.motivo, k.documento
  from erp.vw_kardex k, alvo
 where k.empresa_id = :loja and k.produto_id = alvo.produto_id
   and (k.tipo <> 'VENDA' or k.data_hora >= current_date - 21)
 order by k.data_hora desc
 limit 30;
