-- =============================================================================
-- S1 · Carga sintética de cadastros
-- Parâmetro psql:  -v escala=1   (1 = 20 mil SKUs, 50 mil clientes)
-- Todos os dados são fictícios e reproduzíveis (setseed).
-- =============================================================================
set search_path = erp, public;
set max_parallel_workers_per_gather = 0;  -- random() em paralelo quebra a reprodutibilidade
set client_min_messages = warning;
select setseed(0.1987);

create temp table cfg as select (:'escala')::numeric as escala;

-- -----------------------------------------------------------------------------
-- Empresas: 1 CD + 5 lojas no oeste catarinense
-- -----------------------------------------------------------------------------
insert into empresa (id, tipo, cnpj, razao_social, fantasia, cidade, abastecida_por, num_pdvs) values
 (1, 'CD',   fn_cnpj_com_dv('123456780001'), 'Rede Oeste Supermercados Ltda', 'CD Oeste',            'Chapecó',              null, 0),
 (2, 'LOJA', fn_cnpj_com_dv('123456780002'), 'Rede Oeste Supermercados Ltda', 'Oeste Centro',        'São Miguel do Oeste',  1,   14),
 (3, 'LOJA', fn_cnpj_com_dv('123456780003'), 'Rede Oeste Supermercados Ltda', 'Oeste Efapi',         'Chapecó',              1,   18),
 (4, 'LOJA', fn_cnpj_com_dv('123456780004'), 'Rede Oeste Supermercados Ltda', 'Oeste Xanxerê',       'Xanxerê',              1,   10),
 (5, 'LOJA', fn_cnpj_com_dv('123456780005'), 'Rede Oeste Supermercados Ltda', 'Oeste Concórdia',     'Concórdia',            1,   12),
 (6, 'LOJA', fn_cnpj_com_dv('123456780006'), 'Rede Oeste Supermercados Ltda', 'Oeste Joaçaba',       'Joaçaba',              1,    8);

insert into pdv (empresa_id, numero, tipo)
select e.id, n, case when n > e.num_pdvs - 2 then 'SELF_CHECKOUT' else 'CAIXA' end
  from empresa e, generate_series(1, e.num_pdvs) n
 where e.tipo = 'LOJA';

insert into parametro_empresa (empresa_id, chave, valor, descricao)
select e.id, p.chave, p.valor, p.descricao
  from empresa e
 cross join (values
   ('ESTOQUE_PERMITE_NEGATIVO', 'N',  'Bloqueia saída sem saldo'),
   ('RUPTURA_DIAS_SEM_VENDA',   '3',  'Dias sem venda para suspeitar de ruptura em itens curva A'),
   ('MARGEM_MINIMA_PERC',       '8',  'Margem mínima aceitável para alerta de precificação'),
   ('VALIDADE_ALERTA_DIAS',     '5',  'Antecedência do alerta de vencimento'),
   ('BALANCA_PREFIXO',          '2',  'Primeiro dígito do EAN gerado pela balança'),
   ('BALANCA_TAM_CODIGO',       '4',  'Dígitos do código (PLU) no EAN de balança'),
   ('BALANCA_TIPO_VALOR',       'P',  'P = preço total embutido, W = peso embutido')
 ) as p(chave, valor, descricao)
 where e.tipo = 'LOJA';

insert into integracao_webservice (empresa_id, nome, url_base, client_id, client_secret)
values (2, 'SITEMERCADO', 'http://localhost:8089/sitemercado', 'oeste-centro', 'segredo-de-teste');

-- -----------------------------------------------------------------------------
-- Mercadológico + modelos para gerar produtos plausíveis
-- Marcas são FICTÍCIAS (evita uso de marcas registradas e combinações absurdas).
-- Cada "linha" agrupa itens que combinam entre si (base × tamanho × variante).
-- -----------------------------------------------------------------------------
create temp table modelo_secao (
    secao_id smallint, depto text, secao text, perecivel boolean, pesavel boolean,
    unidade char(2), peso_mix numeric, preco_min numeric, preco_max numeric, margem numeric,
    validade smallint, ncm char(8), cest char(7), cst char(3), aliq numeric,
    marcas text[]
);

create temp table modelo_linha (secao_id smallint, bases text[], tamanhos text[], variantes text[], marcas text[]);  -- marcas null = as da seção

insert into modelo_secao values
 (101,'Mercearia','Grãos e Farináceos',false,false,'UN',8,4,38,0.18,null,'10063021',null,'000',7,
  '{Bom Grão,Sabor Serrano,Vale do Peperi,Colonial Oeste,Dona Ida,Campo Belo,Primor do Sul,Fazenda Boa Vista,Dia a Dia,Tradição Gaúcha,Celeiro,Grão Nobre}'),
 (102,'Mercearia','Massas',false,false,'UN',5,3,18,0.25,null,'19021900',null,'000',17,
  '{Nonna Rosa,Dona Ida,Casa Bianchi,Bella Massa,Vale do Peperi,Dia a Dia,Colonial Oeste,Trigo Real,Mamma Gina}'),
 (103,'Mercearia','Enlatados e Conservas',false,false,'UN',4,3,25,0.28,null,'20052000',null,'000',17,
  '{Horta Viva,Mar Azul,Campo Belo,Dia a Dia,Sabor Serrano,Bom Prato,Primor do Sul,Conservas Iguaçu}'),
 (104,'Mercearia','Biscoitos',false,false,'UN',6,2,14,0.30,null,'19053100',null,'000',17,
  '{Crocante,Delícia do Sul,Casa Bianchi,Dia a Dia,Biscoitos Peperi,Hora do Lanche,Primor do Sul,Nonna Rosa}'),
 (105,'Mercearia','Matinais',false,false,'UN',5,4,32,0.26,null,'09012100',null,'000',17,
  '{Café Serrano,Bom Dia,Grão Nobre,Fazenda Boa Vista,Dia a Dia,Sabor Serrano,Manhã Feliz,Colonial Oeste}'),
 (106,'Mercearia','Óleos, Açúcar e Temperos',false,false,'UN',5,3,45,0.22,null,'15079011',null,'000',12,
  '{Dourado,Oliva Real,Campo Belo,Dia a Dia,Primor do Sul,Sabor Serrano,Doce Vale,Tempero da Vó}'),
 (201,'Bebidas','Refrigerantes',false,false,'UN',6,3,14,0.24,null,'22021000','0300700','060',0,
  '{Refri Sul,Gelada,Bolhas,Fonte Clara,Dia a Dia,Serra Gaúcha}'),
 (202,'Bebidas','Cervejas',false,false,'UN',6,3,12,0.20,null,'22030000','0302100','060',0,
  '{Cervejaria Peperi,Lúpulo Bravo,Velha Chapecó,Malte Sul,Bierhaus,Oeste Bier,Dia a Dia}'),
 (203,'Bebidas','Sucos e Águas',false,false,'UN',4,2,15,0.30,null,'22011000','0300400','060',0,
  '{Fonte Clara,Vale das Uvas,Pomar do Sul,Dia a Dia,Serra Gaúcha,Água Pura}'),
 (204,'Bebidas','Vinhos e Destilados',false,false,'UN',3,15,160,0.32,null,'22042100','0201200','060',0,
  '{Vinícola Contestado,Vale das Uvas,Serra Gaúcha,Adega Bianchi,Alambique Peperi,Destilaria Iguaçu}'),
 (301,'Hortifruti','Frutas',true,true,'KG',5,3,22,0.40,4,'08039000',null,'040',0,'{Granel}'),
 (302,'Hortifruti','Legumes e Verduras',true,true,'KG',5,2,15,0.42,3,'07020000',null,'040',0,'{Granel}'),
 (401,'Açougue','Bovinos',true,true,'KG',4,25,95,0.24,4,'02013000',null,'040',0,'{Açougue Próprio}'),
 (402,'Açougue','Suínos e Aves',true,true,'KG',4,9,38,0.26,4,'02071400',null,'040',0,'{Açougue Próprio,Frigorífico Oeste,Coop Peperi}'),
 (501,'Padaria','Pães e Confeitaria',true,true,'KG',4,8,48,0.55,2,'19059090',null,'000',17,'{Padaria Própria}'),
 (502,'Frios e Laticínios','Frios Fatiados',true,true,'KG',3,25,110,0.35,6,'16010000',null,'000',17,'{Frigorífico Oeste,Coop Peperi,Laticínios Serrano,Colonial Oeste}'),
 (503,'Frios e Laticínios','Laticínios',false,false,'UN',6,2,30,0.22,null,'04012010',null,'000',7,
  '{Laticínios Serrano,Coop Peperi,Leite da Serra,Vale Verde,Dia a Dia,Colonial Oeste,Fazenda Boa Vista}'),
 (601,'Limpeza','Limpeza Doméstica',false,false,'UN',6,2,40,0.30,null,'34025000',null,'000',17,
  '{Brilho Fácil,Casa Limpa,Limpex,Dia a Dia,Perfumata,Lavanda Sul,Super Limpo}'),
 (701,'Higiene e Perfumaria','Higiene Pessoal',false,false,'UN',6,2,45,0.35,null,'34011190',null,'000',17,
  '{Suave,Bem Estar,Dia a Dia,Pura Pele,Sorriso,Cuidado Diário,Fresh Up}'),
 (801,'Bazar','Utilidades',false,false,'UN',2,5,90,0.45,null,'39241000',null,'000',17,
  '{Casa Prática,Dia a Dia,Útil,Lar Doce Lar,Brasa Viva}'),
 (901,'Pet','Pet Shop',false,false,'UN',2,6,120,0.28,null,'23091000',null,'000',17,
  '{Patinha,Amigo Fiel,Dia a Dia,Nutripet,Bicho Feliz}');

-- perecíveis embalados (laticínios) também têm controle de validade, mas não são pesáveis
update modelo_secao set perecivel = true where secao_id = 503;

insert into modelo_linha values
 (101,'{Arroz Branco,Arroz Parboilizado,Arroz Integral}','{1kg,2kg,5kg}','{Tipo 1,Premium,Agulhinha,Seleção}',null),
 (101,'{Feijão Carioca,Feijão Preto,Feijão Vermelho}','{1kg,2kg}','{Tipo 1,Seleção,Tradicional}',null),
 (101,'{Lentilha,Grão de Bico,Canjica Amarela,Canjica Branca,Milho de Pipoca,Ervilha Partida}','{500g,1kg}','{Tradicional,Seleção}',null),
 (101,'{Farinha de Trigo,Farinha de Mandioca,Farinha de Milho,Fubá Mimoso,Polvilho Doce,Polvilho Azedo}','{500g,1kg,5kg}','{Tradicional,Especial,Integral}',null),
 (102,'{Espaguete,Parafuso,Penne,Talharim,Ave Maria,Fusilli,Conchinha,Padre Nosso}','{500g,1kg}','{Sêmola,Com Ovos,Integral,Grano Duro}',null),
 (102,'{Lasanha,Canelone}','{500g}','{Com Ovos,Sêmola}',null),
 (102,'{Macarrão Instantâneo}','{80g,85g}','{Galinha,Carne,Legumes,Galinha Caipira,Tomate}',null),
 (103,'{Milho Verde,Ervilha,Seleta de Legumes,Grão de Bico Cozido}','{170g,200g}','{Lata,Sachê}',null),
 (103,'{Atum Ralado,Atum Sólido,Sardinha}','{125g,170g}','{Em Óleo,Ao Natural,Com Molho de Tomate,Picante}',null),
 (103,'{Extrato de Tomate,Molho de Tomate,Polpa de Tomate}','{190g,300g,340g,1kg}','{Tradicional,Manjericão,Pizza,Bolonhesa}',null),
 (103,'{Palmito Pupunha,Azeitona Verde,Azeitona Preta,Pepino em Conserva}','{300g,500g}','{Inteiro,Picado,Fatiado}',null),
 (104,'{Biscoito Recheado,Wafer,Cookies}','{90g,140g,200g}','{Chocolate,Morango,Baunilha,Limão,Doce de Leite,Coco}',null),
 (104,'{Biscoito Cream Cracker,Biscoito Água e Sal,Biscoito Maria,Biscoito Maisena,Rosquinha,Biscoito de Polvilho}','{200g,350g,400g}','{Tradicional,Integral,Leite,Coco,Amanteigado}',null),
 (105,'{Café Torrado e Moído,Café em Grãos}','{250g,500g,1kg}','{Tradicional,Extra Forte,Descafeinado,Gourmet}',null),
 (105,'{Café Solúvel,Cappuccino}','{50g,100g,200g}','{Tradicional,Clássico,Canela,Zero Açúcar}',null),
 (105,'{Achocolatado em Pó,Cereal Matinal,Aveia em Flocos,Granola}','{200g,400g,800g}','{Tradicional,Integral,Zero Açúcar,Mel,Frutas}',null),
 (105,'{Leite em Pó}','{200g,400g,800g}','{Integral,Desnatado,Instantâneo}',null),
 (106,'{Óleo de Soja,Óleo de Girassol,Óleo de Canola,Azeite Extra Virgem}','{500ml,900ml}','{Tradicional,Premium}',null),
 (106,'{Açúcar Refinado,Açúcar Cristal,Açúcar Mascavo,Sal Refinado,Sal Grosso}','{1kg,2kg,5kg}','{Tradicional,Orgânico}',null),
 (106,'{Tempero Completo,Colorau,Orégano,Pimenta do Reino}','{40g,300g}','{Tradicional,Sem Pimenta}',null),
 (106,'{Maionese,Ketchup,Mostarda}','{200g,390g}','{Tradicional,Light}',null),
 (106,'{Vinagre de Álcool,Vinagre de Maçã}','{750ml}','{Tradicional}',null),
 (201,'{Refrigerante Cola,Refrigerante Guaraná,Refrigerante Laranja,Refrigerante Limão,Refrigerante Uva,Água Tônica}','{350ml,600ml,"1,5L",2L}','{Tradicional,Zero,Retornável,Pack 6}',null),
 (202,'{Cerveja Pilsen,Cerveja Puro Malte,Cerveja IPA,Cerveja Weiss,Cerveja Lager,Cerveja Sem Álcool}','{269ml,350ml,473ml,600ml}','{Lata,Long Neck,Garrafa,Pack 6,Pack 12,Retornável}',null),
 (203,'{Água Mineral,Água com Gás}','{500ml,"1,5L",5L}','{Tradicional,Pack 6}',null),
 (203,'{Suco Integral,Néctar}','{200ml,1L,"1,5L"}','{Uva,Laranja,Pêssego,Maracujá,Maçã}',null),
 (203,'{Chá Gelado,Isotônico}','{500ml,1L}','{Limão,Pêssego,Laranja,Tangerina}',null),
 (203,'{Suco em Pó}','{18g,25g}','{Uva,Laranja,Maracujá,Limão,Abacaxi,Morango}',null),
 (204,'{Vinho Tinto Seco,Vinho Tinto Suave,Vinho Tinto Reserva}','{750ml,1L,"1,5L"}','{Cabernet Sauvignon,Merlot,Bordô,Tannat}','{Vinícola Contestado,Vale das Uvas,Serra Gaúcha,Adega Bianchi}'),
 (204,'{Vinho Branco Seco,Vinho Branco Suave}','{750ml,1L}','{Chardonnay,Moscato,Sauvignon Blanc,Niágara}','{Vinícola Contestado,Vale das Uvas,Serra Gaúcha,Adega Bianchi}'),
 (204,'{Espumante}','{750ml}','{Brut,Moscatel,Rosé,Demi-Sec}','{Vinícola Contestado,Vale das Uvas,Serra Gaúcha}'),
 (204,'{Cachaça}','{700ml,1L}','{Ouro,Prata,Envelhecida}','{Alambique Peperi,Destilaria Iguaçu}'),
 (204,'{Vodka,Conhaque,Licor de Chocolate}','{700ml,1L}','{Tradicional}','{Destilaria Iguaçu,Alambique Peperi}'),
 (301,'{Banana Caturra,Banana Prata,Maçã Gala,Maçã Fuji,Laranja Pera,Laranja Lima,Bergamota,Mamão Formosa,Manga Palmer,Uva Niágara,Melancia,Melão,Abacaxi,Limão Taiti,Pera}',null,null,null),
 (302,'{Tomate,Tomate Cereja,Batata Inglesa,Cebola,Alho,Cenoura,Abobrinha,Pimentão Verde,Beterraba,Chuchu,Batata Doce,Mandioca,Repolho,Pepino,Abóbora Cabotiá}',null,null,null),
 (401,'{Alcatra,Picanha,Contra Filé,Patinho,Acém,Costela,Carne Moída de Primeira,Carne Moída de Segunda,Maminha,Fraldinha,Coxão Mole,Coxão Duro,Músculo,Paleta,Cupim}',null,null,null),
 (402,'{Coxa e Sobrecoxa,Peito de Frango,Filé de Peito,Asa de Frango,Coração de Frango,Lombo Suíno,Costelinha Suína,Pernil Suíno,Bisteca Suína,Linguiça Toscana,Linguiça Calabresa,Linguiça Colonial}',null,null,null),
 (501,'{Pão Francês,Pão de Milho,Pão Caseiro,Pão Integral,Cuca de Uva,Cuca de Banana,Cuca Alemã,Bolo de Chocolate,Bolo de Cenoura,Sonho,Rosca Doce,Pão de Queijo,Salgado Assado,Torta Salgada}',null,null,null),
 (502,'{Presunto Cozido,Queijo Mussarela,Queijo Prato,Mortadela,Salame Italiano,Peito de Peru,Copa,Queijo Colonial}',null,null,null),
 (503,'{Leite UHT}','{1L}','{Integral,Desnatado,Semidesnatado,Zero Lactose}',null),
 (503,'{Iogurte,Bebida Láctea}','{170g,900g,1L}','{Natural,Morango,Coco,Pêssego,Grego}',null),
 (503,'{Manteiga,Margarina,Requeijão,Creme de Leite,Leite Condensado,Queijo Minas Frescal,Doce de Leite}','{200g,250g,395g,500g}','{Tradicional,Com Sal,Sem Sal,Light,Zero Lactose}',null),
 (601,'{Detergente Líquido,Sabão Líquido,Amaciante,Desinfetante,Limpador Multiuso,Limpa Vidros}','{500ml,1L,2L,5L}','{Neutro,Limão,Lavanda,Floral,Concentrado,Refil}',null),
 (601,'{Sabão em Pó,Sabão em Barra}','{200g,800g,1kg,"1,6kg"}','{Tradicional,Coco,Glicerinado}',null),
 (601,'{Água Sanitária,Álcool 70%}','{1L,2L,5L}','{Tradicional,Perfumado,Gel}',null),
 (601,'{Esponja Multiuso,Pano de Chão,Luva de Látex}','{1un,3un}','{Tradicional,Reforçado}',null),
 (601,'{Saco de Lixo}','{30L,50L,100L}','{Preto,Azul,Reforçado}',null),
 (701,'{Sabonete}','{85g,90g}','{Hidratante,Neutro,Erva Doce,Antibacteriano,Infantil}',null),
 (701,'{Shampoo,Condicionador,Creme de Pentear}','{200ml,350ml,1L}','{Hidratação,Anticaspa,Cachos,Liso Perfeito,Infantil,Reconstrução}',null),
 (701,'{Creme Dental}','{70g,90g}','{Menta,Branqueador,Sensível,Infantil}',null),
 (701,'{Escova Dental,Fio Dental,Enxaguante Bucal}','{1un,50m,500ml}','{Macia,Média,Menta}',null),
 (701,'{Desodorante Aerosol,Desodorante Roll-on}','{50ml,150ml}','{Feminino,Masculino,Sem Perfume,Sensível}',null),
 (701,'{Papel Higiênico}','{4un,12un,16un}','{Folha Dupla,Folha Simples,Neutro}',null),
 (701,'{Fralda Descartável}','{30un,50un}','{P,M,G,XG}',null),
 (701,'{Absorvente,Lenço Umedecido}','{8un,50un}','{Noturno,Com Abas,Sem Perfume}',null),
 (801,'{Pote Plástico,Copo Descartável,Prato Descartável,Papel Alumínio,Filme PVC,Guardanapo}','{1un,50un,100un,30cm,45cm}','{Pequeno,Médio,Grande}',null),
 (801,'{Pilha Alcalina}','{2un,4un}','{AA,AAA}',null),
 (801,'{Lâmpada LED}','{1un,4un}','{9W,12W,15W}',null),
 (801,'{Carvão Vegetal}','{3kg,5kg}','{Tradicional}','{Brasa Viva,Dia a Dia}'),
 (801,'{Fósforo,Vela}','{10un,8un}','{Tradicional}',null),
 (901,'{Ração Cães Adultos,Ração Cães Filhotes,Ração Gatos Adultos,Ração Gatos Castrados}','{1kg,3kg,10kg,15kg}','{Carne,Frango,Peixe,Carne e Vegetais,Light}',null),
 (901,'{Petisco Cães,Sachê Gatos}','{60g,85g}','{Carne,Frango,Peixe}',null),
 (901,'{Areia Higiênica,Tapete Higiênico}','{4kg,30un}','{Tradicional,Perfumada}',null);

insert into departamento (id, nome)
select row_number() over (order by min(secao_id)), depto from modelo_secao group by depto;

insert into secao (id, departamento_id, nome, perecivel)
select m.secao_id, d.id, m.secao, m.perecivel
  from modelo_secao m join departamento d on d.nome = m.depto;

-- -----------------------------------------------------------------------------
-- Fornecedores (transacionadores PJ)
-- -----------------------------------------------------------------------------
insert into transacionador (tipo_pessoa, cpf_cnpj, nome, nome_fantasia, is_fornecedor, email, telefone)
select 'J',
       fn_cnpj_com_dv(lpad((20000000 + n * 7919)::text, 8, '0') || '0001'),
       (array['Distribuidora','Indústria','Atacado','Comercial','Cooperativa','Frigorífico','Alimentos'])[1 + n % 7]
         || ' ' || (array['Vale do Uruguai','Serra Gaúcha','Planalto','Fronteira','Contestado','Litoral','Sul','Iguaçu','Peperi','Chapecó'])[1 + (n / 7) % 10]
         || ' ' || (array['Ltda','S.A.','Eireli','ME'])[1 + n % 4],
       'Fornecedor ' || n,
       true,
       'comercial' || n || '@fornecedor.example',
       '(49) 3' || lpad((n * 37 % 1000)::text, 3, '0') || '-' || lpad((n * 91 % 10000)::text, 4, '0')
  from generate_series(1, 150) n;

-- -----------------------------------------------------------------------------
-- Clientes (transacionadores PF) – nomes e CPFs sintéticos
-- -----------------------------------------------------------------------------
insert into transacionador (tipo_pessoa, cpf_cnpj, nome, is_cliente, email, telefone, data_nascimento, aceita_marketing)
select 'F',
       fn_cpf_com_dv(lpad((100000000 + n * 13)::text, 9, '0')),
       (array['Ana','Bruno','Carla','Daniel','Eduarda','Felipe','Gabriela','Henrique','Isabela','João','Karina','Lucas',
              'Mariana','Nicolas','Olívia','Pedro','Rafaela','Samuel','Tatiane','Vinícius','Juliana','Marcos','Patrícia','Rodrigo'])[1 + floor(random() * 24)::int]
         || ' ' || (array['Silva','Santos','Oliveira','Souza','Pereira','Costa','Rodrigues','Almeida','Nascimento','Lima',
                          'Araújo','Fernandes','Carvalho','Gomes','Martins','Rocha','Ribeiro','Schmidt','Müller','Zanella','Bortolini','Dal Pizzol'])[1 + floor(random() * 22)::int]
         || ' ' || (array['Silva','Santos','Weber','Lazzari','Fritsch','Bianchi','Kunz','Costa','Moraes','Becker'])[1 + floor(random() * 10)::int],
       true,
       'cliente' || n || '@email.example',
       '(49) 9' || lpad(floor(random() * 99999999)::text, 8, '0'),
       date '1950-01-01' + floor(random() * 20000)::int,
       random() < 0.6
  from generate_series(1, (50000 * (select escala from cfg))::int) n;

insert into transacionador_endereco (transacionador_id, cep, logradouro, numero, bairro, cidade, uf, codigo_ibge)
select t.id,
       '89' || lpad(floor(random() * 999999)::text, 6, '0'),
       (array['Rua','Avenida','Travessa'])[1 + floor(random() * 3)::int] || ' ' ||
       (array['Sete de Setembro','Getúlio Vargas','Marechal Floriano','das Palmeiras','Santos Dumont','XV de Novembro','Nereu Ramos','Fernando Machado'])[1 + floor(random() * 8)::int],
       (1 + floor(random() * 3000))::text,
       (array['Centro','São Cristóvão','Efapi','Passo dos Fortes','Presidente Médici','Jardim Itália','Santa Maria'])[1 + floor(random() * 7)::int],
       c.cidade, 'SC', c.ibge
  from transacionador t
 cross join lateral (select * from (values
        ('São Miguel do Oeste','4217204'),('Chapecó','4204202'),('Xanxerê','4219507'),
        ('Concórdia','4204301'),('Joaçaba','4209003')) v(cidade, ibge)
        order by random() + t.id * 0 limit 1) c
 where t.is_cliente;

-- -----------------------------------------------------------------------------
-- Produtos (≈20 mil por escala 1)
--   * pesáveis: uma combinação base × marca (≈100 PLUs, realista para balança)
--   * demais: amostra de combinações distintas base × marca × tamanho × variante,
--     respeitando o peso de cada seção no mix
-- -----------------------------------------------------------------------------
create temp table combinacao as
select m.secao_id, m.pesavel, b.base, mc.marca,
       case when m.pesavel then null else t.tamanho end as tamanho,
       nullif(v.variante, '') as variante,
       random() as sorteio
  from modelo_linha l
  join modelo_secao m using (secao_id)
 cross join lateral unnest(l.bases) b(base)
 cross join lateral unnest(coalesce(l.marcas, m.marcas)) mc(marca)
 cross join lateral unnest(coalesce(l.tamanhos, array[''])) t(tamanho)
 cross join lateral unnest(coalesce(l.variantes, array[''])) v(variante);

create temp table alvo_secao as
select secao_id,
       round((select escala from cfg) * 20000 * peso_mix
             / (select sum(peso_mix) from modelo_secao where not pesavel))::int as qtd
  from modelo_secao where not pesavel;

create temp table produto_tmp as
with escolhidos as (
    select c.*, row_number() over (partition by c.secao_id order by c.sorteio) as rk
      from combinacao c
)
select row_number() over (order by e.secao_id, e.sorteio)::int as id,
       e.base, e.marca, e.tamanho, e.variante, m.*,
       random() as r_preco
  from escolhidos e
  join modelo_secao m using (secao_id)
  left join alvo_secao a using (secao_id)
 where e.pesavel or e.rk <= a.qtd;

alter table produto_tmp add column preco numeric;
update produto_tmp
   set preco = round((preco_min + (preco_max - preco_min) * power(r_preco, 1.8))::numeric, 1) - 0.01;

create temp table fornecedor_idx as
select id, row_number() over (order by id) - 1 as idx from transacionador where is_fornecedor;

insert into produto (id, descricao, descricao_reduzida, secao_id, marca, unidade_venda, pesavel,
                     codigo_balanca, validade_dias, ncm, cest, cst_icms, aliquota_icms, fornecedor_principal_id)
select p.id,
       upper(concat_ws(' ', p.base, p.variante, p.marca, nullif(p.tamanho, ''))),
       left(upper(concat_ws(' ', p.base, p.variante, nullif(p.tamanho, ''))), 25),
       p.secao_id, p.marca, p.unidade, p.pesavel,
       case when p.pesavel then 1000 + row_number() over (partition by p.pesavel order by p.id) end,
       p.validade, p.ncm, p.cest, p.cst, p.aliq,
       f.id
  from produto_tmp p
  join fornecedor_idx f on f.idx = (p.secao_id * 7 + p.id % 5) % 150;

-- Embalagem de venda (EAN-13 com prefixo 789 = Brasil) e de compra (caixa com DUN-14)
insert into produto_embalagem (produto_id, sigla, fator, ean, compra, venda)
select id, unidade_venda, 1,
       case when not pesavel then fn_ean13_com_dv('789' || lpad((id * 7)::text, 9, '0')) end,
       pesavel, true
  from produto;

insert into produto_embalagem (produto_id, sigla, fator, ean, compra, venda)
select p.id, 'CX', f.fator,
       fn_dun14_com_dv('1' || '789' || lpad((p.id * 7)::text, 9, '0')),
       true, false
  from produto p
 cross join lateral (select (array[6, 12, 12, 24, 24, 30])[1 + (p.id % 6)] as fator) f
 where not p.pesavel;

-- Produto em cada empresa: preço por loja (pequena variação regional), custo pela margem alvo
insert into produto_loja (empresa_id, produto_id, preco_venda, custo_medio, estoque_minimo, estoque_maximo)
select e.id, p.id,
       case when e.tipo = 'CD' then p.preco else round(p.preco * (1 + (e.id - 4) * 0.01), 2) end,
       round(p.preco * (1 - p.margem) * (0.95 + random()::numeric * 0.1), 4),
       case when p.pesavel then 2 else 4 end * case when e.tipo = 'CD' then 10 else 1 end,
       case when p.pesavel then 30 else 48 end * case when e.tipo = 'CD' then 10 else 1 end
  from produto_tmp p
 cross join empresa e;

-- Popularidade (lei de Zipf: poucos itens concentram a venda) usada pela carga de vendas.
-- Fica no schema auxiliar "seed", removido no fim da carga.
drop schema if exists seed cascade;
create schema seed;
create table seed.cfg as select escala from cfg;
create table seed.popularidade as
select id as produto_id, pesavel,
       -- Zipf-Mandelbrot: 1/(rank+50)^0.9 -> ~20% dos SKUs fazem ~70% da venda, sem um item dominar.
       -- Pesáveis (pão francês, banana, carne) ficam entre os mais vendidos.
       1.0 / power(row_number() over (order by case when pesavel then random() * 0.01 else random() end) + 50, 0.9)
         * case when not pesavel then 1 when secao_id in (301, 302) then 4 when secao_id = 501 then 6 else 1.5 end as peso
  from produto_tmp;

-- Promoções: encartes quinzenais com 60 itens cada, cobrindo os últimos 90 dias
insert into promocao (descricao, inicio, fim, tipo)
select 'Encarte quinzenal #' || n, current_date - 90 + (n - 1) * 15, current_date - 90 + (n - 1) * 15 + 13, 'ENCARTE'
  from generate_series(1, 7) n;

insert into promocao_item (promocao_id, produto_id, empresa_id, preco_promocional)
select pr.id, p.id, null, round(pl.preco_venda * (0.82 + random()::numeric * 0.1), 1) - 0.01
  from promocao pr
 cross join lateral (select id from produto order by random() + pr.id * 0 limit 60) p
  join produto_loja pl on pl.produto_id = p.id and pl.empresa_id = 1;

analyze;
