-- Ruptura suspeita com critério estatístico (em vez de "X dias sem venda" fixo).
-- Se um item vende em média λ unidades/dia (Poisson), a chance de passar d dias seguidos
-- sem nenhuma venda é e^(−λ·d). Abaixo de 1% é muito improvável ser acaso.
--   estoque <= 0  -> ruptura declarada (falta comprar/repor)
--   estoque  > 0  -> estoque fantasma (produto não está na gôndola: depósito, avaria, furto, erro de inventário)
with base as (
    select empresa_id, produto_id,
           sum(quantidade) filter (where dia between current_date - 35 and current_date - 8) / 28.0 as lambda,
           max(dia) as ultima_venda
      from erp.mv_venda_diaria
     where dia >= current_date - 35
     group by 1, 2
)
select e.fantasia                         as loja,
       p.id                               as produto,
       p.descricao,
       round(b.lambda, 2)                 as venda_media_dia,
       current_date - b.ultima_venda - 1  as dias_sem_venda,
       round(exp(-b.lambda * (current_date - b.ultima_venda - 1))::numeric, 5) as prob_acaso,
       pl.estoque_atual,
       case when pl.estoque_atual <= 0 then 'RUPTURA' else 'ESTOQUE FANTASMA' end as diagnostico,
       round(b.lambda * (current_date - b.ultima_venda - 1) * pl.preco_venda, 2) as venda_perdida_estimada
  from base b
  join erp.produto_loja pl on pl.empresa_id = b.empresa_id and pl.produto_id = b.produto_id
  join erp.produto p       on p.id = b.produto_id
  join erp.empresa e       on e.id = b.empresa_id
 where b.lambda > 0
   and exp(-b.lambda * (current_date - b.ultima_venda - 1)) < 0.01
 order by venda_perdida_estimada desc;
