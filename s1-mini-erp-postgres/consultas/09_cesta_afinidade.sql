-- Análise de cesta: pares de seções que aparecem juntas no mesmo cupom acima do esperado (lift > 1).
-- lift = P(A e B) / (P(A) × P(B)). Útil para layout de loja e encartes casados.
with cupom_secao as (
    select distinct i.cupom_id, p.secao_id
      from erp.cupom_item i join erp.produto p on p.id = i.produto_id
), total as (select count(distinct cupom_id)::numeric as n from cupom_secao),
suporte as (select secao_id, count(*) / (select n from total) as p from cupom_secao group by 1),
pares as (
    select a.secao_id as s1, b.secao_id as s2, count(*) / (select n from total) as p12
      from cupom_secao a join cupom_secao b on a.cupom_id = b.cupom_id and a.secao_id < b.secao_id
     group by 1, 2
)
select sa.nome as secao_a, sb.nome as secao_b,
       round(100 * p12, 2)                  as perc_cupons_juntos,
       round(p12 / (x.p * y.p), 2)          as lift
  from pares
  join suporte x on x.secao_id = s1
  join suporte y on y.secao_id = s2
  join erp.secao sa on sa.id = s1
  join erp.secao sb on sb.id = s2
 where p12 >= 0.01
 order by lift desc
 limit 15;
