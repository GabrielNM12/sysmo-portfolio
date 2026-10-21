-- =============================================================================
-- S1 · Carga sintética de movimento: 90 dias de PDV, estoque, quebras, compras
-- Também injeta cenários reais de problema para o painel S4 detectar:
--   * ruptura declarada (estoque zerou e a venda parou)
--   * estoque fantasma (sistema mostra saldo, mas a venda parou: produto não está na gôndola)
--   * margem negativa (reajuste de custo do fornecedor não repassado ao preço)
--   * lotes perto do vencimento
-- =============================================================================
set search_path = erp, public;
set max_parallel_workers_per_gather = 0;  -- random() em paralelo quebra a reprodutibilidade
set client_min_messages = warning;
select setseed(0.2026);

\echo '>> sorteio de produtos (curva de popularidade)'
create temp table slot as
with p as (select produto_id, peso / sum(peso) over () as w from seed.popularidade)
select row_number() over ()::int as idx, produto_id
  from p, generate_series(1, greatest(1, round(w * 400000))::int);
create unique index on slot (idx);
analyze slot;

\echo '>> cupons'
create temp table dia as
select d::date as dia,
       case extract(isodow from d) when 5 then 1.20 when 6 then 1.45 when 7 then 0.65 else 0.90 end
       * case when extract(day from d) between 1 and 10 then 1.10 else 1.0 end as fator   -- salário no início do mês
  from generate_series(current_date - 90, current_date - 1, interval '1 day') d;

create temp table hora (h int, ini numeric, fim numeric);
insert into hora
select h, coalesce(sum(w) over (order by h rows between unbounded preceding and 1 preceding), 0)::numeric / sum(w) over (),
          sum(w) over (order by h)::numeric / sum(w) over ()
  from (values (7,3),(8,5),(9,7),(10,10),(11,12),(12,9),(13,6),(14,5),(15,5),(16,6),(17,9),(18,12),(19,10),(20,6),(21,3)) v(h, w);

create temp table cupom_tmp as
select e.id as empresa_id, d.dia,
       random() as r_hora, random() as r_pdv, random() as r_cliente, random() as r_itens
  from empresa e
  join dia d on true
 cross join lateral generate_series(1, round(2330 * (select escala from seed.cfg) * e.num_pdvs / 62.0 * d.fator)::int + 0 * e.id)
 where e.tipo = 'LOJA';

create temp table clientes as select id, row_number() over (order by id) as rn from transacionador where is_cliente;
create unique index on clientes (rn);

insert into cupom (empresa_id, pdv_id, numero, data_hora, transacionador_id, cancelado)
select c.empresa_id, p.id,
       0,
       c.dia + make_interval(hours => h.h, secs => floor(random() * 3600)::int),
       case when c.r_cliente < 0.4 then (select id from clientes where rn = 1 + floor(c.r_cliente / 0.4 * (select count(*) from clientes))::int) end,
       random() < 0.004
  from cupom_tmp c
  join hora h on c.r_hora >= h.ini and c.r_hora < h.fim
  join lateral (select id, row_number() over (order by numero) as rn, count(*) over () as n
                  from pdv where empresa_id = c.empresa_id) p
    on p.rn = 1 + floor(c.r_pdv * p.n)::int
 order by 4;

-- numeração sequencial por PDV (como a NFC-e)
update cupom c set numero = s.num
  from (select id, row_number() over (partition by pdv_id order by data_hora, id) as num from cupom) s
 where s.id = c.id;

\echo '>> itens dos cupons'
create temp table item_raw as
select c.id as cupom_id, c.empresa_id, c.data_hora::date as dia, g.seq,
       1 + floor(random() * (select max(idx) from slot))::int as slot_idx,
       random() as r_qtd
  from cupom c
 cross join lateral (select 1 + least(24, floor(-ln(1 - random()) * 4.2))::int + 0 * c.id as k) k
 cross join lateral generate_series(1, k.k) g(seq);

create temp table promo_dia as
select pi.produto_id, d.dia, min(pi.preco_promocional) as preco
  from promocao p join promocao_item pi on pi.promocao_id = p.id
  join dia d on d.dia between p.inicio and p.fim
 group by 1, 2;
create index on promo_dia (produto_id, dia);

insert into cupom_item (cupom_id, sequencia, produto_id, quantidade, preco_unitario, valor_total, custo_unitario, em_promocao)
select r.cupom_id, r.seq, s.produto_id, q.qtd,
       coalesce(least(pd.preco, pl.preco_venda), pl.preco_venda),
       round(q.qtd * coalesce(least(pd.preco, pl.preco_venda), pl.preco_venda), 2),
       pl.custo_medio,
       pd.preco is not null
  from item_raw r
  join slot s on s.idx = r.slot_idx
  join produto p on p.id = s.produto_id
  join produto_loja pl on pl.empresa_id = r.empresa_id and pl.produto_id = s.produto_id
  left join promo_dia pd on pd.produto_id = s.produto_id and pd.dia = r.dia
 cross join lateral (select case
            when p.pesavel then round(((0.3 + power(r.r_qtd, 1.5) * 1.6)
                                       * case p.secao_id when 502 then 0.35 when 501 then 0.6
                                                         when 401 then 1.5 when 402 then 1.4 else 1.3 end)::numeric, 3)
            when r.r_qtd < 0.80 then 1
            when r.r_qtd < 0.95 then 2
            else 3 + floor((r.r_qtd - 0.95) * 80) end::numeric(12,3) as qtd) q
on conflict do nothing;

\echo '>> cenários: ruptura declarada e estoque fantasma'
create table seed.ruptura as
with candidatos as (
    select ci.produto_id, c.empresa_id, count(*) as vendas
      from cupom_item ci join cupom c on c.id = ci.cupom_id
      join produto p on p.id = ci.produto_id and not p.pesavel
     group by 1, 2
    having count(*) >= 120               -- itens que vendem todo dia (curva A)
)
select empresa_id, produto_id,
       current_date - (3 + floor(random() * 9))::int as desde,
       case when random() < 0.7 then 'RUPTURA' else 'FANTASMA' end as tipo
  from (select *, row_number() over (partition by empresa_id order by random()) rk from candidatos) x
 where rk <= 12 * (select escala from seed.cfg) + 3;

delete from cupom_item ci
 using cupom c, seed.ruptura r
 where c.id = ci.cupom_id and r.empresa_id = c.empresa_id and r.produto_id = ci.produto_id
   and c.data_hora >= r.desde;

-- reorganiza sequência e totais dos cupons
delete from cupom c where not exists (select 1 from cupom_item i where i.cupom_id = c.id);

update cupom c
   set valor_bruto = t.bruto, valor_desconto = 0, valor_total = t.bruto
  from (select cupom_id, sum(valor_total) as bruto from cupom_item group by cupom_id) t
 where t.cupom_id = c.id;

update cupom c
   set chave_nfce = fn_chave_acesso(42, c.data_hora::date, e.cnpj, 65, p.numero, c.numero, 1,
                                    (c.id * 7919 % 99999999)::int)
  from empresa e, pdv p
 where e.id = c.empresa_id and p.id = c.pdv_id;

\echo '>> pagamentos'
insert into cupom_pagamento (cupom_id, sequencia, forma, bandeira, nsu, valor)
select c.id, 1, f.forma,
       case when f.forma in ('CREDITO', 'DEBITO') then (array['VISA','MASTERCARD','ELO','HIPERCARD','AMEX'])[1 + floor(random() * 5)::int]
            when f.forma = 'VOUCHER' then (array['VR','ALELO','TICKET','SODEXO'])[1 + floor(random() * 4)::int] end,
       case when f.forma in ('CREDITO', 'DEBITO', 'VOUCHER', 'PIX') then lpad((c.id * 104729 % 999999)::text, 6, '0') end,
       c.valor_total
  from cupom c
 cross join lateral (select case
          when r < 0.31 then 'PIX' when r < 0.59 then 'DEBITO' when r < 0.84 then 'CREDITO'
          when r < 0.95 then 'DINHEIRO' else 'VOUCHER' end as forma
        from (select random() + 0 * c.id as r) x) f;

\echo '>> livro-razão de estoque'
alter table estoque_movimento disable trigger estoque_movimento_aplicar;

create temp table venda_dia as
select c.empresa_id, i.produto_id, c.data_hora::date as dia, sum(i.quantidade) as qtd
  from cupom_item i join cupom c on c.id = i.cupom_id
 where not c.cancelado and not i.cancelado
 group by 1, 2, 3;
create index on venda_dia (empresa_id, produto_id, dia);
analyze venda_dia;

create temp table venda_media as
select empresa_id, produto_id, sum(qtd) / 90.0 as media_dia from venda_dia group by 1, 2;

-- 1) inventário inicial (−91 dias): perecíveis ~3 dias de venda, demais ~10 dias + mínimo de gôndola
insert into estoque_movimento (empresa_id, produto_id, data_hora, tipo, quantidade, custo_unitario, documento)
select pl.empresa_id, pl.produto_id, current_date - 91, 'INVENTARIO',
       case when s.perecivel then round(coalesce(vm.media_dia, 0) * 3 + 1, 3)
            else ceil(coalesce(vm.media_dia, 0) * 10 + pl.estoque_minimo / 2) end,
       round(pl.custo_medio * 0.97, 4),
       'INVENTARIO-INICIAL'
  from produto_loja pl
  join produto p on p.id = pl.produto_id
  join secao s on s.id = p.secao_id
  left join venda_media vm using (empresa_id, produto_id);

-- 2) reposição semanal (entrega direta do fornecedor): cobre a venda da semana seguinte
create temp table semana as
select n as semana, current_date - 91 + n * 7 as dia from generate_series(0, 12) n;

create temp table reposicao as
select v.empresa_id, v.produto_id, s.semana, s.dia, sum(v.qtd) as venda_semana
  from venda_dia v join semana s on v.dia >= s.dia and v.dia < s.dia + 7
 group by 1, 2, 3, 4;

-- cenário margem negativa: fornecedor reajustou e o preço não acompanhou
create table seed.reajuste as
select id as produto_id, 1.20 + random()::numeric * 0.20 as fator_sobre_preco
  from produto
 where not pesavel and id in (select produto_id from venda_media group by 1 having sum(media_dia) > 3)
 order by random() limit (25 * (select escala from seed.cfg) + 5)::int;

insert into estoque_movimento (empresa_id, produto_id, data_hora, tipo, quantidade, custo_unitario, documento)
select r.empresa_id, r.produto_id, r.dia + time '06:30', 'ENTRADA_NF',
       -- perecíveis são repostos quase na medida da venda; secos carregam folga de segurança
       case when s.perecivel then round(r.venda_semana * (1.0 + random()::numeric * 0.08), 3)
            else ceil(r.venda_semana * (1.0 + random()::numeric * 0.15)) end,
       case when rj.produto_id is not null and r.semana >= 9
            then round(pl.preco_venda * rj.fator_sobre_preco, 4)
            else round(pl.custo_medio * (1 + 0.0025 * r.semana) * (0.98 + random()::numeric * 0.04), 4) end,
       'NF-' || r.empresa_id || '-' || p.fornecedor_principal_id || '-' || to_char(r.dia, 'YYYYMMDD')
  from reposicao r
  join produto p on p.id = r.produto_id
  join secao s on s.id = p.secao_id
  join produto_loja pl on pl.empresa_id = r.empresa_id and pl.produto_id = r.produto_id
  left join seed.reajuste rj on rj.produto_id = r.produto_id
  left join seed.ruptura ru on ru.empresa_id = r.empresa_id and ru.produto_id = r.produto_id
 where ru.produto_id is null or r.dia < ru.desde - 10 or ru.tipo = 'FANTASMA';

-- 3) saídas por venda: um movimento por produto/loja/dia (resumo de vendas do PDV)
insert into estoque_movimento (empresa_id, produto_id, data_hora, tipo, quantidade, documento)
select empresa_id, produto_id, dia + time '23:59', 'VENDA', -qtd, 'RESUMO-PDV-' || to_char(dia, 'YYYYMMDD')
  from venda_dia;

-- 4) quebras de perecíveis (vencimento, avaria, deterioração)
insert into estoque_movimento (empresa_id, produto_id, data_hora, tipo, quantidade, motivo, documento)
select r.empresa_id, r.produto_id, r.dia + 6 + time '20:00', 'QUEBRA',
       -round(r.venda_semana * (0.01 + random()::numeric * 0.05), 3),
       (array['VENCIMENTO','AVARIA','DETERIORAÇÃO','DESIDRATAÇÃO','FURTO'])[1 + floor(random() * 5)::int],
       'QUEBRA-' || to_char(r.dia + 6, 'YYYYMMDD')
  from reposicao r
  join produto p on p.id = r.produto_id
  join secao s on s.id = p.secao_id and s.perecivel
 where r.dia + 6 < current_date and r.venda_semana > 2;

\echo '>> recalculando saldos e custo médio (pode levar ~1 minuto)'
select fn_recalcular_estoque() as produtos_recalculados;

-- ruptura declarada: inventário rotativo confirma saldo zero
insert into estoque_movimento (empresa_id, produto_id, data_hora, tipo, quantidade, custo_unitario, motivo, documento)
select pl.empresa_id, pl.produto_id, current_date - 1 + time '21:00', 'AJUSTE', -pl.estoque_atual, null,
       'INVENTÁRIO ROTATIVO', 'INV-ROT-' || to_char(current_date - 1, 'YYYYMMDD')
  from produto_loja pl
  join seed.ruptura r using (empresa_id, produto_id)
 where r.tipo = 'RUPTURA' and pl.estoque_atual <> 0;

-- estoque nunca negativo nos demais itens (ERP com bloqueio de saldo negativo)
insert into estoque_movimento (empresa_id, produto_id, data_hora, tipo, quantidade, custo_unitario, motivo, documento)
select empresa_id, produto_id, current_date - 1 + time '21:00', 'AJUSTE', -estoque_atual, custo_medio,
       'AJUSTE DE SALDO NEGATIVO', 'AJ-' || to_char(current_date - 1, 'YYYYMMDD')
  from produto_loja where estoque_atual < 0;

alter table estoque_movimento enable trigger estoque_movimento_aplicar;
select fn_recalcular_estoque() as produtos_recalculados;

\echo '>> lotes e validades'
insert into produto_lote (empresa_id, produto_id, lote, fabricacao, validade, quantidade)
select pl.empresa_id, pl.produto_id,
       'L' || to_char(current_date - l.idade, 'YYMMDD') || l.n,
       current_date - l.idade,
       current_date - l.idade + l.vida,
       case when p.pesavel then round(pl.estoque_atual / l.total, 3)
            else floor(pl.estoque_atual / l.total)
                 + case when l.n = 1 then pl.estoque_atual - floor(pl.estoque_atual / l.total) * l.total else 0 end end
  from produto_loja pl
  join produto p on p.id = pl.produto_id
  join secao s on s.id = p.secao_id and s.perecivel
 cross join lateral (
        select n, count(*) over () as total,
               -- idade do lote: quase sempre dentro da validade; ~3% passam do prazo (para o alerta)
               floor(random() * coalesce(p.validade_dias, 30) * case when random() < 0.03 then 1.4 else 0.95 end)::int
                 + 0 * pl.produto_id as idade,
               coalesce(p.validade_dias, 30 + floor(random() * 60)::int) as vida
          from generate_series(1, 1 + floor(random() * 3)::int + 0 * pl.produto_id) n) l
 where pl.estoque_atual > 0 and pl.empresa_id <> 1;

delete from produto_lote where quantidade = 0;

\echo '>> pedidos de compra (base para o portal de recebimento S5)'
insert into pedido_compra (empresa_id, fornecedor_id, data_emissao, previsao_entrega, status, comprador, condicao_pagamento)
select case when n % 3 = 0 then 1 else 2 + n % 5 end,
       f.id,
       current_date - (1 + floor(random() * 10))::int,
       current_date + floor(random() * 8)::int,
       'ABERTO',
       (array['Marcia Bortolini','Rafael Kunz','Jéssica Lazzari'])[1 + n % 3],
       (array['28 DDL','28/35/42 DDL','21 DDL','À VISTA'])[1 + n % 4]
  from generate_series(1, 80) n
  join lateral (select id from transacionador t
                 where is_fornecedor and exists (select 1 from produto p where p.fornecedor_principal_id = t.id and not p.pesavel)
                 order by random() + n * 0 limit 1) f on true;

insert into pedido_compra_item (pedido_compra_id, sequencia, produto_id, embalagem_id, quantidade_emb, fator, custo_embalagem)
select pc.id, row_number() over (partition by pc.id order by p.id), p.id, e.id,
       1 + floor(random() * 20), e.fator,
       round(pl.custo_medio * e.fator, 4)
  from pedido_compra pc
 cross join lateral (select * from produto p
                      where p.fornecedor_principal_id = pc.fornecedor_id and not p.pesavel
                      order by random() + pc.id * 0 limit 5 + floor(random() * 20)::int) p
  join produto_embalagem e on e.produto_id = p.id and e.sigla = 'CX'
  join produto_loja pl on pl.empresa_id = pc.empresa_id and pl.produto_id = p.id;

delete from pedido_compra pc where not exists (select 1 from pedido_compra_item i where i.pedido_compra_id = pc.id);

analyze;
