-- =============================================================================
-- S1 · Perfis de acesso (princípio do menor privilégio + LGPD)
-- Cada sistema satélite usa um usuário próprio, com só o que precisa.
-- Senhas abaixo são de laboratório: troque em qualquer ambiente real.
-- =============================================================================
set search_path = erp, public;
select set_config('client_min_messages', 'warning', false);

do $$
declare r text;
begin
    foreach r in array array['bi_leitura', 'painel_s4', 'balanca_s3', 'integrador_s2', 'portal_s5'] loop
        if exists (select 1 from pg_roles where rolname = r) then
            execute format('reassign owned by %I to current_user', r);
            execute format('drop owned by %I', r);
            execute format('drop role %I', r);
        end if;
    end loop;
end $$;

-- -----------------------------------------------------------------------------
-- BI somente leitura: NÃO enxerga dados pessoais (nome, CPF, e-mail, telefone)
-- -----------------------------------------------------------------------------
create role bi_leitura nologin;
grant usage on schema erp to bi_leitura;
grant select on all tables in schema erp to bi_leitura;
revoke select on transacionador, transacionador_endereco, integracao_webservice from bi_leitura;
grant select (id, tipo_pessoa, is_cliente, is_fornecedor, nome_fantasia, aceita_marketing, criado_em)
   on transacionador to bi_leitura;
grant select (id, transacionador_id, cidade, uf, codigo_ibge) on transacionador_endereco to bi_leitura;

create role painel_s4 login password 'painel_s4' in role bi_leitura;
alter role painel_s4 set default_transaction_read_only = on;   -- impede escrita mesmo por engano
alter role painel_s4 set statement_timeout = '30s';             -- não derruba o servidor dos PDVs
alter role painel_s4 set search_path = erp;

-- Gerador de carga de balança e etiquetas (S3): mesma visão do BI, somente leitura
create role balanca_s3 login password 'balanca_s3' in role bi_leitura;
alter role balanca_s3 set default_transaction_read_only = on;

-- -----------------------------------------------------------------------------
-- Integrador e-commerce (S2): grava pedidos e clientes, lê catálogo e parâmetros
-- -----------------------------------------------------------------------------
create role integrador_s2 login password 'integrador_s2';
alter role integrador_s2 set search_path = erp;
grant usage on schema erp to integrador_s2;
grant select on empresa, parametro_empresa, integracao_webservice, produto, produto_embalagem,
                produto_loja, promocao, promocao_item to integrador_s2;
grant select, insert, update on transacionador, transacionador_endereco, pedido_venda, pedido_venda_item to integrador_s2;
grant insert on estoque_movimento to integrador_s2;
grant update (estoque_atual, custo_medio, custo_ultima_entrada, atualizado_em) on produto_loja to integrador_s2;
grant usage on all sequences in schema erp to integrador_s2;
-- schema próprio para o log de execuções do integrador (o integrador cria as tabelas na partida)
create schema integrador authorization integrador_s2;

-- -----------------------------------------------------------------------------
-- Portal de recebimento (S5): lê pedidos de compra, registra notas de entrada
-- -----------------------------------------------------------------------------
create role portal_s5 login password 'portal_s5';
alter role portal_s5 set search_path = erp;
grant usage on schema erp to portal_s5;
grant select on empresa, produto, produto_embalagem, produto_loja, pedido_compra, pedido_compra_item to portal_s5;
grant select (id, tipo_pessoa, cpf_cnpj, nome, nome_fantasia, is_fornecedor) on transacionador to portal_s5;
grant select, insert, update on nota_entrada, nota_entrada_item to portal_s5;
grant update (status) on pedido_compra to portal_s5;
grant usage on all sequences in schema erp to portal_s5;
