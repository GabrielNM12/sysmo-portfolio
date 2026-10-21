-- =============================================================================
-- S1 · Funções utilitárias, regras de estoque e custo médio
-- =============================================================================
set search_path = erp, public;

-- -----------------------------------------------------------------------------
-- Dígitos verificadores (gerar dados sintéticos válidos e validar entradas)
-- -----------------------------------------------------------------------------

-- CPF: recebe 9 dígitos, devolve os 11 com DV
create or replace function fn_cpf_com_dv(base9 text) returns char(11)
language plpgsql immutable strict
set search_path = erp, public as $$
declare
    d   int[] := array(select substr(base9, i, 1)::int from generate_series(1, 9) i);
    s   int;
    dv1 int;
    dv2 int;
begin
    s := 0;
    for i in 1..9 loop s := s + d[i] * (11 - i); end loop;
    dv1 := case when s % 11 < 2 then 0 else 11 - s % 11 end;
    s := 0;
    for i in 1..9 loop s := s + d[i] * (12 - i); end loop;
    s := s + dv1 * 2;
    dv2 := case when s % 11 < 2 then 0 else 11 - s % 11 end;
    return base9 || dv1 || dv2;
end $$;

-- CNPJ numérico: recebe 12 dígitos (8 raiz + 4 filial), devolve os 14 com DV
create or replace function fn_cnpj_com_dv(base12 text) returns char(14)
language plpgsql immutable strict
set search_path = erp, public as $$
declare
    p1  int[] := '{5,4,3,2,9,8,7,6,5,4,3,2}';
    p2  int[] := '{6,5,4,3,2,9,8,7,6,5,4,3,2}';
    s   int := 0;
    dv1 int;
    dv2 int;
    tmp text;
begin
    for i in 1..12 loop s := s + substr(base12, i, 1)::int * p1[i]; end loop;
    dv1 := case when s % 11 < 2 then 0 else 11 - s % 11 end;
    tmp := base12 || dv1;
    s := 0;
    for i in 1..13 loop s := s + substr(tmp, i, 1)::int * p2[i]; end loop;
    dv2 := case when s % 11 < 2 then 0 else 11 - s % 11 end;
    return tmp || dv2;
end $$;

-- EAN-13 / GTIN: recebe 12 dígitos, devolve 13 com DV (módulo 10, pesos 1 e 3)
create or replace function fn_ean13_com_dv(base12 text) returns char(13)
language sql immutable strict
set search_path = erp, public as $$
    select base12 || ((10 - (sum(substr(base12, i, 1)::int * case when i % 2 = 0 then 3 else 1 end) % 10)) % 10)::text
    from generate_series(1, 12) i
$$;

-- DUN-14 (GTIN-14) para caixas: indicador + 12 primeiros dígitos do EAN da unidade
create or replace function fn_dun14_com_dv(base13 text) returns char(14)
language sql immutable strict
set search_path = erp, public as $$
    select base13 || ((10 - (sum(substr(base13, i, 1)::int * case when i % 2 = 1 then 3 else 1 end) % 10)) % 10)::text
    from generate_series(1, 13) i
$$;

create or replace function fn_gtin_valido(gtin text) returns boolean
language sql immutable strict
set search_path = erp, public as $$
    select case length(gtin)
        when 13 then fn_ean13_com_dv(left(gtin, 12)) = gtin
        when 14 then fn_dun14_com_dv(left(gtin, 13)) = gtin
        else false end
$$;

-- Chave de acesso NF-e/NFC-e (44 dígitos):
-- cUF(2) AAMM(4) CNPJ(14) modelo(2) série(3) número(9) tpEmis(1) cNF(8) cDV(1)
create or replace function fn_chave_acesso(p_cuf int, p_emissao date, p_cnpj text, p_modelo int,
                                           p_serie int, p_numero bigint, p_tp_emis int, p_cnf int)
returns char(44)
language plpgsql immutable strict
set search_path = erp, public as $$
declare
    base  text := lpad(p_cuf::text, 2, '0') || to_char(p_emissao, 'YYMM') || lpad(p_cnpj, 14, '0')
               || lpad(p_modelo::text, 2, '0') || lpad(p_serie::text, 3, '0') || lpad(p_numero::text, 9, '0')
               || p_tp_emis::text || lpad(p_cnf::text, 8, '0');
    soma  int := 0;
    peso  int := 2;
    resto int;
begin
    for i in reverse 43..1 loop
        soma := soma + substr(base, i, 1)::int * peso;
        peso := case when peso = 9 then 2 else peso + 1 end;
    end loop;
    resto := soma % 11;
    return base || case when resto < 2 then 0 else 11 - resto end;
end $$;

-- -----------------------------------------------------------------------------
-- Estoque e custo médio ponderado
-- -----------------------------------------------------------------------------
-- Regra (custo médio móvel, usado pela maioria dos ERPs de varejo):
--   entrada com custo:  novo_custo = (saldo * custo_atual + qtd * custo_entrada) / (saldo + qtd)
--   se o saldo anterior era <= 0, o custo passa a ser o custo da entrada
--   saídas não alteram o custo médio, apenas o saldo
-- -----------------------------------------------------------------------------

create or replace function trg_estoque_movimento_aplicar() returns trigger
language plpgsql
set search_path = erp, public as $$
begin
    update produto_loja pl
       set custo_medio = case
               when new.custo_unitario is null or new.quantidade <= 0 then pl.custo_medio
               when pl.estoque_atual <= 0 then new.custo_unitario
               else round((pl.estoque_atual * pl.custo_medio + new.quantidade * new.custo_unitario)
                          / (pl.estoque_atual + new.quantidade), 4)
           end,
           custo_ultima_entrada = case when new.tipo = 'ENTRADA_NF' then new.custo_unitario else pl.custo_ultima_entrada end,
           estoque_atual = pl.estoque_atual + new.quantidade,
           atualizado_em = now()
     where pl.empresa_id = new.empresa_id
       and pl.produto_id = new.produto_id;

    if not found then
        raise exception 'Produto % não está ativo na empresa %', new.produto_id, new.empresa_id
            using errcode = 'foreign_key_violation';
    end if;
    return new;
end $$;

create or replace trigger estoque_movimento_aplicar
after insert on estoque_movimento
for each row execute function trg_estoque_movimento_aplicar();

-- Recalcula saldo e custo médio a partir do livro-razão (usado após cargas em massa
-- feitas com o gatilho desabilitado, ou para auditar divergências).
create or replace function fn_recalcular_estoque(p_empresa smallint default null, p_produto integer default null)
returns integer
language plpgsql
set search_path = erp, public as $$
declare
    r          record;
    chave_atual text := '';
    saldo      numeric(14,3);
    custo      numeric(14,4);
    ult_ent    numeric(14,4);
    n          integer := 0;
begin
    for r in
        select empresa_id, produto_id, quantidade, custo_unitario, tipo
          from estoque_movimento
         where (p_empresa is null or empresa_id = p_empresa)
           and (p_produto is null or produto_id = p_produto)
         order by empresa_id, produto_id, data_hora, id
    loop
        if chave_atual <> r.empresa_id || '-' || r.produto_id then
            if chave_atual <> '' then
                update produto_loja set estoque_atual = saldo, custo_medio = custo,
                       custo_ultima_entrada = ult_ent, atualizado_em = now()
                 where empresa_id = split_part(chave_atual, '-', 1)::smallint
                   and produto_id = split_part(chave_atual, '-', 2)::integer;
                n := n + 1;
            end if;
            chave_atual := r.empresa_id || '-' || r.produto_id;
            saldo := 0; custo := 0; ult_ent := null;
        end if;

        if r.quantidade > 0 and r.custo_unitario is not null then
            custo := case when saldo <= 0 then r.custo_unitario
                          else round((saldo * custo + r.quantidade * r.custo_unitario) / (saldo + r.quantidade), 4) end;
            if r.tipo = 'ENTRADA_NF' then ult_ent := r.custo_unitario; end if;
        end if;
        saldo := saldo + r.quantidade;
    end loop;

    if chave_atual <> '' then
        update produto_loja set estoque_atual = saldo, custo_medio = custo,
               custo_ultima_entrada = ult_ent, atualizado_em = now()
         where empresa_id = split_part(chave_atual, '-', 1)::smallint
           and produto_id = split_part(chave_atual, '-', 2)::integer;
        n := n + 1;
    end if;
    return n;
end $$;

-- Preço vigente de um produto numa loja em uma data (considera promoções)
create or replace function fn_preco_vigente(p_empresa smallint, p_produto integer, p_data date default current_date)
returns table (preco numeric, em_promocao boolean)
language sql stable
set search_path = erp, public as $$
    select coalesce(min(pi.preco_promocional), pl.preco_venda),
           min(pi.preco_promocional) is not null
      from produto_loja pl
      left join promocao_item pi
             on pi.produto_id = pl.produto_id
            and (pi.empresa_id is null or pi.empresa_id = pl.empresa_id)
            and exists (select 1 from promocao p
                         where p.id = pi.promocao_id and p_data between p.inicio and p.fim)
     where pl.empresa_id = p_empresa and pl.produto_id = p_produto
     group by pl.preco_venda
$$;
