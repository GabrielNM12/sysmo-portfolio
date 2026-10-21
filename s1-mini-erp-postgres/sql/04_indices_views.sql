-- =============================================================================
select set_config('client_min_messages', 'warning', false);
-- S1 · Índices, visões analíticas e materialização para BI
-- Criados DEPOIS da carga em massa (inserir com índices é mais lento).
-- =============================================================================
set search_path = erp, public;

-- PDV: consultas quase sempre filtram loja + período
create index if not exists ix_cupom_empresa_data   on cupom (empresa_id, data_hora) include (valor_total, cancelado);
create index if not exists ix_cupom_transacionador on cupom (transacionador_id) where transacionador_id is not null;
create index if not exists ix_cupom_item_produto   on cupom_item (produto_id);

-- Estoque: extrato do produto na loja (kardex) e varreduras por período
create index if not exists ix_mov_empresa_produto_data on estoque_movimento (empresa_id, produto_id, data_hora);
create index if not exists brin_mov_data on estoque_movimento using brin (data_hora);

create index if not exists ix_produto_secao       on produto (secao_id);
create index if not exists ix_produto_fornecedor  on produto (fornecedor_principal_id);
create index if not exists ix_embalagem_produto   on produto_embalagem (produto_id);
create index if not exists ix_lote_validade       on produto_lote (validade) where quantidade > 0;
create index if not exists ix_pedido_compra_forn  on pedido_compra (fornecedor_id, status);
create index if not exists ix_pedido_venda_data   on pedido_venda (empresa_id, data_pedido);

-- -----------------------------------------------------------------------------
-- Venda diária por produto/loja: base de todos os painéis (refresh 1x por noite)
-- -----------------------------------------------------------------------------
drop materialized view if exists mv_venda_diaria cascade;
create materialized view mv_venda_diaria as
select c.empresa_id,
       i.produto_id,
       c.data_hora::date               as dia,
       sum(i.quantidade)               as quantidade,
       sum(i.valor_total)              as valor_venda,
       sum(i.quantidade * i.custo_unitario)::numeric(14,2) as valor_custo,
       count(distinct c.id)            as cupons,
       bool_or(i.em_promocao)          as teve_promocao
  from cupom c
  join cupom_item i on i.cupom_id = c.id
 where not c.cancelado and not i.cancelado
 group by 1, 2, 3;

create unique index ux_mv_venda_diaria on mv_venda_diaria (empresa_id, produto_id, dia);
create index ix_mv_venda_diaria_dia on mv_venda_diaria (dia, empresa_id);

-- -----------------------------------------------------------------------------
-- Situação atual de cada produto em cada loja (estoque, margem, giro, última venda)
-- -----------------------------------------------------------------------------
create or replace view vw_produto_loja_situacao as
with ultimos_28 as (
    select empresa_id, produto_id,
           sum(quantidade) / 28.0 as venda_media_dia,
           count(*)               as dias_com_venda,
           max(dia)               as ultima_venda
      from mv_venda_diaria
     where dia >= current_date - 28
     group by 1, 2
)
select pl.empresa_id,
       e.fantasia                         as loja,
       pl.produto_id,
       p.descricao,
       s.nome                             as secao,
       d.nome                             as departamento,
       p.pesavel,
       pl.preco_venda,
       pl.custo_medio,
       pl.custo_ultima_entrada,
       round((pl.preco_venda - pl.custo_medio) / nullif(pl.preco_venda, 0) * 100, 2)  as margem_perc,
       pl.estoque_atual,
       pl.estoque_minimo,
       coalesce(u.venda_media_dia, 0)     as venda_media_dia,
       coalesce(u.dias_com_venda, 0)      as dias_com_venda_28d,
       u.ultima_venda,
       current_date - u.ultima_venda      as dias_sem_venda,
       case when coalesce(u.venda_media_dia, 0) > 0
            then round(pl.estoque_atual / u.venda_media_dia, 1) end as cobertura_dias
  from produto_loja pl
  join empresa e      on e.id = pl.empresa_id and e.tipo = 'LOJA'
  join produto p      on p.id = pl.produto_id
  join secao s        on s.id = p.secao_id
  join departamento d on d.id = s.departamento_id
  left join ultimos_28 u on u.empresa_id = pl.empresa_id and u.produto_id = pl.produto_id
 where pl.ativo and p.ativo;

-- Extrato do produto (kardex) com saldo acumulado
create or replace view vw_kardex as
select m.empresa_id, m.produto_id, m.data_hora, m.tipo, m.quantidade, m.custo_unitario, m.motivo, m.documento,
       sum(m.quantidade) over (partition by m.empresa_id, m.produto_id order by m.data_hora, m.id) as saldo
  from estoque_movimento m;

create or replace function fn_atualizar_bi() returns void
language sql set search_path = erp, public as $$
    refresh materialized view concurrently erp.mv_venda_diaria;
$$;

analyze;
