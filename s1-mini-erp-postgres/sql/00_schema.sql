-- =============================================================================
select set_config('client_min_messages', 'warning', false);
-- S1 · Mini-ERP de supermercado · Modelo de dados
-- -----------------------------------------------------------------------------
-- Modelo didático inspirado no vocabulário público do varejo alimentar e do
-- Sysmo S1 (transacionador, empresa/loja, CD, embalagem, código de balança).
-- NÃO é o banco real do Sysmo S1; serve para estudar o domínio e para os
-- projetos S2–S5 deste repositório usarem como "ERP de mentira".
-- =============================================================================

drop schema if exists erp cascade;
create schema erp;
set search_path = erp, public;

-- -----------------------------------------------------------------------------
-- Empresas (lojas e centro de distribuição)
-- -----------------------------------------------------------------------------
create table empresa (
    id              smallint primary key,
    tipo            text        not null check (tipo in ('LOJA', 'CD')),
    cnpj            char(14)    not null unique,
    razao_social    text        not null,
    fantasia        text        not null,
    cidade          text        not null,
    uf              char(2)     not null default 'SC',
    abastecida_por  smallint    references empresa (id),   -- loja recebe do CD
    num_pdvs        smallint    not null default 0,
    ativa           boolean     not null default true
);
comment on table empresa is 'Lojas e CD da rede. abastecida_por indica o CD que reabastece a loja.';

-- Parâmetros por empresa (no S1 real ficam no programa Sgr101 - Cadastro de Parâmetros)
create table parametro_empresa (
    empresa_id  smallint not null references empresa (id),
    chave       text     not null,
    valor       text     not null,
    descricao   text,
    primary key (empresa_id, chave)
);

-- Credenciais de webservices externos (Sgr101 › Integrações › E-commerce)
create table integracao_webservice (
    id              serial primary key,
    empresa_id      smallint not null references empresa (id),
    nome            text     not null,              -- ex.: SITEMERCADO
    url_base        text     not null,
    client_id       text     not null,
    client_secret   text     not null,
    ativo           boolean  not null default true,
    unique (empresa_id, nome)
);

-- -----------------------------------------------------------------------------
-- Mercadológico
-- -----------------------------------------------------------------------------
create table departamento (
    id    smallint primary key,
    nome  text not null unique
);

create table secao (
    id               smallint primary key,
    departamento_id  smallint not null references departamento (id),
    nome             text     not null,
    perecivel        boolean  not null default false,
    unique (departamento_id, nome)
);

-- -----------------------------------------------------------------------------
-- Transacionadores: clientes e fornecedores na mesma entidade (padrão Sysmo)
-- -----------------------------------------------------------------------------
create table transacionador (
    id              bigint generated always as identity primary key,
    tipo_pessoa     char(1)  not null check (tipo_pessoa in ('F', 'J')),
    cpf_cnpj        varchar(14) not null unique,
    nome            text     not null,
    nome_fantasia   text,
    is_cliente      boolean  not null default false,
    is_fornecedor   boolean  not null default false,
    email           text,
    telefone        text,
    data_nascimento date,
    aceita_marketing boolean not null default false,  -- consentimento LGPD
    criado_em       timestamptz not null default now(),
    check (is_cliente or is_fornecedor),
    check ((tipo_pessoa = 'F' and length(cpf_cnpj) = 11) or (tipo_pessoa = 'J' and length(cpf_cnpj) = 14))
);
comment on table transacionador is 'Clientes e fornecedores. Contém dados pessoais (LGPD): acesso restrito.';

create table transacionador_endereco (
    id                bigint generated always as identity primary key,
    transacionador_id bigint not null references transacionador (id) on delete cascade,
    tipo              text   not null default 'PRINCIPAL',
    cep               char(8),
    logradouro        text,
    numero            text,
    complemento       text,
    bairro            text,
    cidade            text,
    uf                char(2),
    codigo_ibge       char(7)
);

-- -----------------------------------------------------------------------------
-- Produtos
-- -----------------------------------------------------------------------------
create table produto (
    id                  integer primary key,
    descricao           text        not null,
    descricao_reduzida  varchar(25) not null,          -- cupom e balança (25 posições)
    secao_id            smallint    not null references secao (id),
    marca               text,
    unidade_venda       char(2)     not null check (unidade_venda in ('UN', 'KG', 'LT')),
    pesavel             boolean     not null default false,
    codigo_balanca      integer     unique,            -- PLU na balança (até 6 dígitos)
    validade_dias       smallint,                      -- validade impressa na etiqueta da balança
    ncm                 char(8)     not null,
    cest                char(7),
    cst_icms            char(3)     not null default '000',
    aliquota_icms       numeric(5,2) not null default 17.00,
    fornecedor_principal_id bigint  references transacionador (id),
    ativo               boolean     not null default true,
    criado_em           timestamptz not null default now(),
    check (not pesavel or codigo_balanca is not null)
);

-- Embalagens e códigos de barras (unidade, caixa, fardo). Fator = unidades por embalagem.
create table produto_embalagem (
    id          integer generated always as identity primary key,
    produto_id  integer       not null references produto (id),
    sigla       varchar(3)    not null,                -- UN, CX, FD, KG
    fator       numeric(10,3) not null check (fator > 0),
    ean         varchar(14)   unique,                  -- EAN-13 (unidade) ou DUN-14 (caixa)
    compra      boolean       not null default false,
    venda       boolean       not null default false,
    unique (produto_id, sigla, fator)
);

-- Parâmetros do produto em cada loja: preço, custo médio, estoque
create table produto_loja (
    empresa_id      smallint      not null references empresa (id),
    produto_id      integer       not null references produto (id),
    preco_venda     numeric(12,2) not null check (preco_venda >= 0),
    custo_medio     numeric(14,4) not null default 0,
    custo_ultima_entrada numeric(14,4),
    estoque_atual   numeric(14,3) not null default 0,
    estoque_minimo  numeric(14,3) not null default 0,
    estoque_maximo  numeric(14,3),
    ativo           boolean       not null default true,
    atualizado_em   timestamptz   not null default now(),
    primary key (empresa_id, produto_id)
);

-- Lotes com validade (perecíveis) para controle de vencimento
create table produto_lote (
    id          bigint generated always as identity primary key,
    empresa_id  smallint      not null references empresa (id),
    produto_id  integer       not null references produto (id),
    lote        text          not null,
    fabricacao  date,
    validade    date          not null,
    quantidade  numeric(14,3) not null check (quantidade >= 0),
    unique (empresa_id, produto_id, lote)
);

-- -----------------------------------------------------------------------------
-- Promoções
-- -----------------------------------------------------------------------------
create table promocao (
    id         integer generated always as identity primary key,
    descricao  text not null,
    inicio     date not null,
    fim        date not null,
    tipo       text not null default 'ENCARTE' check (tipo in ('ENCARTE', 'CLUBE', 'RELAMPAGO')),
    check (fim >= inicio)
);

create table promocao_item (
    promocao_id       integer       not null references promocao (id) on delete cascade,
    produto_id        integer       not null references produto (id),
    empresa_id        smallint      references empresa (id),   -- null = todas as lojas
    preco_promocional numeric(12,2) not null,
    unique nulls not distinct (promocao_id, produto_id, empresa_id)
);

-- -----------------------------------------------------------------------------
-- Frente de caixa: cupons (NFC-e) e itens
-- -----------------------------------------------------------------------------
create table pdv (
    id          smallint generated always as identity primary key,
    empresa_id  smallint not null references empresa (id),
    numero      smallint not null,
    tipo        text     not null default 'CAIXA' check (tipo in ('CAIXA', 'SELF_CHECKOUT')),
    unique (empresa_id, numero)
);

create table cupom (
    id                bigint generated always as identity primary key,
    empresa_id        smallint      not null references empresa (id),
    pdv_id            smallint      not null references pdv (id),
    numero            integer       not null,
    data_hora         timestamp     not null,
    transacionador_id bigint        references transacionador (id),  -- "CPF na nota"/clube
    valor_bruto       numeric(12,2) not null default 0,
    valor_desconto    numeric(12,2) not null default 0,
    valor_total       numeric(12,2) not null default 0,
    cancelado         boolean       not null default false,
    chave_nfce        char(44)
);

create table cupom_item (
    cupom_id        bigint        not null references cupom (id) on delete cascade,
    sequencia       smallint      not null,
    produto_id      integer       not null references produto (id),
    quantidade      numeric(12,3) not null check (quantidade > 0),
    preco_unitario  numeric(12,2) not null,
    valor_desconto  numeric(12,2) not null default 0,
    valor_total     numeric(12,2) not null,
    custo_unitario  numeric(14,4) not null,    -- custo médio no momento da venda (CMV)
    em_promocao     boolean       not null default false,
    cancelado       boolean       not null default false,
    primary key (cupom_id, sequencia)
);

create table cupom_pagamento (
    cupom_id   bigint        not null references cupom (id) on delete cascade,
    sequencia  smallint      not null,
    forma      text          not null check (forma in ('DINHEIRO', 'PIX', 'CREDITO', 'DEBITO', 'VOUCHER', 'CREDIARIO')),
    bandeira   text,
    nsu        text,
    valor      numeric(12,2) not null,
    primary key (cupom_id, sequencia)
);

-- -----------------------------------------------------------------------------
-- Estoque: todo saldo nasce de um movimento (livro-razão do estoque)
-- -----------------------------------------------------------------------------
create table estoque_movimento (
    id             bigint generated always as identity primary key,
    empresa_id     smallint      not null references empresa (id),
    produto_id     integer       not null references produto (id),
    data_hora      timestamp     not null,
    tipo           text          not null check (tipo in (
                       'INVENTARIO', 'ENTRADA_NF', 'VENDA', 'DEVOLUCAO_VENDA',
                       'TRANSF_ENTRADA', 'TRANSF_SAIDA', 'QUEBRA', 'AJUSTE', 'VENDA_ECOMMERCE')),
    quantidade     numeric(14,3) not null,   -- positiva entra, negativa sai
    custo_unitario numeric(14,4),            -- obrigatório em entradas: alimenta o custo médio
    motivo         text,                     -- ex.: quebra por vencimento, avaria, furto
    documento      text,                     -- chave NF-e, nº do resumo de vendas, pedido etc.
    check (tipo not in ('ENTRADA_NF', 'INVENTARIO', 'TRANSF_ENTRADA') or custo_unitario is not null)
);

-- -----------------------------------------------------------------------------
-- Compras: pedido de compra e nota de entrada (usados pelo S5)
-- -----------------------------------------------------------------------------
create table pedido_compra (
    id                integer generated always as identity primary key,
    empresa_id        smallint not null references empresa (id),       -- destino (CD ou loja)
    fornecedor_id     bigint   not null references transacionador (id),
    data_emissao      date     not null,
    previsao_entrega  date,
    status            text     not null default 'ABERTO'
                      check (status in ('ABERTO', 'PARCIAL', 'RECEBIDO', 'CANCELADO')),
    comprador         text,
    condicao_pagamento text
);

create table pedido_compra_item (
    pedido_compra_id  integer       not null references pedido_compra (id) on delete cascade,
    sequencia         smallint      not null,
    produto_id        integer       not null references produto (id),
    embalagem_id      integer       not null references produto_embalagem (id),
    quantidade_emb    numeric(12,3) not null check (quantidade_emb > 0),  -- em embalagens
    fator             numeric(10,3) not null,                            -- unidades por embalagem
    custo_embalagem   numeric(14,4) not null,
    quantidade_recebida numeric(12,3) not null default 0,
    primary key (pedido_compra_id, sequencia)
);

create table nota_entrada (
    id                integer generated always as identity primary key,
    chave             char(44) not null unique,
    numero            integer  not null,
    serie             smallint not null,
    fornecedor_id     bigint   not null references transacionador (id),
    empresa_id        smallint not null references empresa (id),
    pedido_compra_id  integer  references pedido_compra (id),
    data_emissao      timestamp not null,
    data_entrada      timestamp,
    valor_produtos    numeric(14,2) not null,
    valor_total       numeric(14,2) not null,
    status            text not null default 'DIGITADA' check (status in ('DIGITADA', 'CONFERIDA', 'ESCRITURADA', 'RECUSADA')),
    xml               text
);

create table nota_entrada_item (
    nota_entrada_id  integer       not null references nota_entrada (id) on delete cascade,
    sequencia        smallint      not null,
    produto_id       integer       references produto (id),
    codigo_fornecedor text,
    ean              varchar(14),
    descricao        text          not null,
    ncm              char(8),
    cfop             char(4),
    unidade          text,
    quantidade       numeric(14,4) not null,
    valor_unitario   numeric(14,6) not null,
    valor_total      numeric(14,2) not null,
    primary key (nota_entrada_id, sequencia)
);

-- -----------------------------------------------------------------------------
-- Vendas e-commerce (destino do integrador S2: pedidos SiteMercado/iFood etc.)
-- -----------------------------------------------------------------------------
create table pedido_venda (
    id                 bigint generated always as identity primary key,
    empresa_id         smallint      not null references empresa (id),
    transacionador_id  bigint        not null references transacionador (id),
    origem             text          not null,          -- SITEMERCADO, IFOOD, SITE_PROPRIO
    numero_externo     text          not null,
    data_pedido        timestamp     not null,
    status             text          not null default 'RECEBIDO'
                       check (status in ('RECEBIDO', 'FATURADO', 'CANCELADO')),
    tipo_entrega       text          not null default 'ENTREGA' check (tipo_entrega in ('ENTREGA', 'RETIRADA')),
    valor_produtos     numeric(12,2) not null,
    valor_frete        numeric(12,2) not null default 0,
    valor_desconto     numeric(12,2) not null default 0,
    valor_total        numeric(12,2) not null,
    forma_pagamento    text,
    endereco_entrega   jsonb,
    observacao         text,
    integrado_em       timestamptz   not null default now(),
    unique (origem, numero_externo)                    -- idempotência da integração
);

create table pedido_venda_item (
    pedido_venda_id  bigint        not null references pedido_venda (id) on delete cascade,
    sequencia        smallint      not null,
    produto_id       integer       not null references produto (id),
    quantidade       numeric(12,3) not null,
    preco_unitario   numeric(12,2) not null,
    valor_total      numeric(12,2) not null,
    codigo_externo   text,
    primary key (pedido_venda_id, sequencia)
);
