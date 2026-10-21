-- =============================================================================
-- S1 · Verificações de integridade da base (rodar após a carga; falha = exceção)
--   psql -v ON_ERROR_STOP=1 -f testes/verificacoes.sql
-- =============================================================================
set search_path = erp, public;
\set QUIET on

do $$
declare n bigint;
begin
    -- 1. Saldo de estoque = soma do livro-razão
    select count(*) into n
      from produto_loja pl
      left join (select empresa_id, produto_id, sum(quantidade) q from estoque_movimento group by 1, 2) m
             using (empresa_id, produto_id)
     where pl.estoque_atual <> coalesce(m.q, 0);
    if n > 0 then raise exception 'FALHOU: % produtos com saldo diferente do kardex', n; end if;
    raise notice 'ok  saldo de estoque confere com o livro-razão';

    -- 2. Sem estoque negativo
    select count(*) into n from produto_loja where estoque_atual < 0;
    if n > 0 then raise exception 'FALHOU: % produtos com estoque negativo', n; end if;
    raise notice 'ok  nenhum estoque negativo';

    -- 3. Total do cupom = soma dos itens = soma dos pagamentos
    select count(*) into n
      from cupom c
      join (select cupom_id, sum(valor_total) t from cupom_item group by 1) i on i.cupom_id = c.id
      join (select cupom_id, sum(valor) t from cupom_pagamento group by 1) p on p.cupom_id = c.id
     where c.valor_total <> i.t or c.valor_total <> p.t;
    if n > 0 then raise exception 'FALHOU: % cupons com total divergente', n; end if;
    raise notice 'ok  totais de cupom, itens e pagamentos batem';

    -- 4. Chaves de acesso NFC-e válidas (44 dígitos, modelo 65, DV módulo 11)
    select count(*) into n from cupom
     where chave_nfce is null or substr(chave_nfce, 21, 2) <> '65'
        or fn_chave_acesso(substr(chave_nfce, 1, 2)::int, to_date(substr(chave_nfce, 3, 4), 'YYMM'),
                           substr(chave_nfce, 7, 14), 65, substr(chave_nfce, 23, 3)::int,
                           substr(chave_nfce, 26, 9)::bigint, substr(chave_nfce, 35, 1)::int,
                           substr(chave_nfce, 36, 8)::int) <> chave_nfce;
    if n > 0 then raise exception 'FALHOU: % chaves NFC-e inválidas', n; end if;
    raise notice 'ok  chaves de acesso NFC-e válidas';

    -- 5. Códigos de barras com dígito verificador correto
    select count(*) into n from produto_embalagem where ean is not null and not fn_gtin_valido(ean);
    if n > 0 then raise exception 'FALHOU: % EAN/DUN inválidos', n; end if;
    raise notice 'ok  EAN-13 e DUN-14 válidos';

    -- 6. CPFs e CNPJs com DV correto
    select count(*) into n from transacionador
     where (tipo_pessoa = 'F' and fn_cpf_com_dv(left(cpf_cnpj, 9)) <> cpf_cnpj)
        or (tipo_pessoa = 'J' and fn_cnpj_com_dv(left(cpf_cnpj, 12)) <> cpf_cnpj);
    if n > 0 then raise exception 'FALHOU: % documentos inválidos', n; end if;
    raise notice 'ok  CPF/CNPJ válidos';

    -- 7. Todo pesável tem código de balança único e cabe em 6 dígitos (limite do PLU Toledo)
    select count(*) into n from produto where pesavel and (codigo_balanca is null or codigo_balanca > 999999);
    if n > 0 then raise exception 'FALHOU: % pesáveis sem PLU válido', n; end if;
    raise notice 'ok  pesáveis com PLU de balança';

    -- 8. Cenários de demonstração presentes
    select count(*) into n from vw_produto_loja_situacao where margem_perc < 0;
    if n = 0 then raise exception 'FALHOU: nenhum cenário de margem negativa'; end if;
    select count(*) into n from produto_lote where validade <= current_date + 5 and quantidade > 0;
    if n = 0 then raise exception 'FALHOU: nenhum lote perto do vencimento'; end if;
    select count(*) into n from pedido_compra where status = 'ABERTO';
    if n = 0 then raise exception 'FALHOU: nenhum pedido de compra aberto'; end if;
    raise notice 'ok  cenários de margem, validade e pedidos de compra presentes';
end $$;

-- 9. Gatilho de estoque: entrada recalcula custo médio; saída mantém o custo
begin;
do $$
declare
    antes  produto_loja%rowtype;
    depois produto_loja%rowtype;
    esperado numeric;
begin
    select * into antes from produto_loja where empresa_id = 2 and estoque_atual > 10 order by produto_id limit 1;

    insert into estoque_movimento (empresa_id, produto_id, data_hora, tipo, quantidade, custo_unitario, documento)
    values (2, antes.produto_id, now(), 'ENTRADA_NF', 10, antes.custo_medio * 2, 'TESTE');
    select * into depois from produto_loja where empresa_id = 2 and produto_id = antes.produto_id;
    esperado := round((antes.estoque_atual * antes.custo_medio + 10 * antes.custo_medio * 2) / (antes.estoque_atual + 10), 4);
    if depois.custo_medio <> esperado or depois.estoque_atual <> antes.estoque_atual + 10 then
        raise exception 'FALHOU: custo médio % (esperado %)', depois.custo_medio, esperado;
    end if;

    insert into estoque_movimento (empresa_id, produto_id, data_hora, tipo, quantidade, documento)
    values (2, antes.produto_id, now(), 'VENDA', -3, 'TESTE');
    select * into antes from produto_loja where empresa_id = 2 and produto_id = depois.produto_id;
    if antes.custo_medio <> depois.custo_medio or antes.estoque_atual <> depois.estoque_atual - 3 then
        raise exception 'FALHOU: saída alterou o custo médio';
    end if;
    raise notice 'ok  gatilho de estoque e custo médio móvel';
end $$;
rollback;

-- 10. Perfil de BI não lê dados pessoais e não escreve
do $$
begin
    if has_column_privilege('bi_leitura', 'erp.transacionador', 'cpf_cnpj', 'select') then
        raise exception 'FALHOU: bi_leitura enxerga CPF';
    end if;
    if has_table_privilege('bi_leitura', 'erp.produto_loja', 'update') then
        raise exception 'FALHOU: bi_leitura pode alterar preço';
    end if;
    raise notice 'ok  perfil de BI sem acesso a dados pessoais e sem escrita';
end $$;
