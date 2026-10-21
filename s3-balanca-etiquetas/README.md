# S3 · Carga de balança, códigos de balança e etiquetas

Ferramenta de linha de comando (Python) para um dos pontos mais sensíveis de implantação de ERP em supermercado: **fazer a balança, o PDV e a gôndola falarem a mesma língua que o cadastro**.

- **Carga de itens para balanças** Toledo MGV6 (`ITENSMGV.TXT`, `TXINFO.TXT`, `TARA.TXT`, `DEPTO.TXT`) e Filizola (`CADTXT.TXT`, `SETORTXT.TXT`), lidos do ERP de laboratório (S1) ou de um CSV de qualquer ERP, com relatório de alterações em relação à carga anterior.
- **Leitura do EAN-13 gerado pela balança** (prefixo 2, PLU + preço ou peso embutido), com os três formatos usuais e conversão para quantidade que o PDV lança no cupom.
- **Leitura de GS1-128** das caixas de carne/frios da indústria: GTIN, lote, fabricação, validade, peso líquido variável e preço, nos formatos com parênteses e "cru" com separador FNC1.
- **Etiquetas de gôndola em PDF** (folha A4 de 24), com preço por kg/L calculado da descrição, código de barras e destaque de oferta (de/por e validade da oferta).
- **Simulação da etiqueta da balança** (60 × 40 mm) com peso, R$/kg, total, embalagem, validade e EAN-13.

| Etiqueta de oferta (A4) | Etiqueta da balança |
|---|---|
| ![ofertas](exemplos/preview_ofertas-1.png) | ![balança](exemplos/preview_etiqueta_balanca-1.png) |

## Como usar

```bash
pip install -e ".[erp]"                       # Python 3.10+

# a partir do S1 (usuário somente leitura balanca_s3)
export DATABASE_URL=postgresql://balanca_s3:balanca_s3@localhost/postgres
balanca carga --loja 2 --modelo toledo-mgv6 --saida carga/
balanca carga --loja 2 --saida carga_hoje/ --anterior carga/     # lista preços que mudaram
balanca etiquetas --loja 2 --promocao --saida ofertas.pdf
balanca etiqueta-balanca --loja 2 --plu 1020 --peso 1.235 --saida etiqueta.pdf

# sem banco: CSV (id;codigo_balanca;descricao;unidade_venda;validade_dias;secao_id;preco)
balanca carga --csv exemplos/pesaveis_loja2.csv --modelo filizola --saida carga_filizola/

# interpretar o que o leitor do PDV recebeu
balanca ler 2102000013429
balanca ler "(01)17891234567892(11)261015(17)261031(10)LT4521(3103)012345"
```

Saída do relatório de alterações (o que o operador da balança precisa conferir):

```
Alterações em relação à carga anterior:
  * PLU   1001 PREÇO ↑    ALCATRA ACOUGUE PROPRIO        49.90 -> 52.90
  + PLU   1128 NOVO       COSTELINHA SUINA COOP PEPERI   R$ 32.90
```

## O layout Toledo MGV6

Arquivo de largura fixa, 150 posições por item, texto ASCII sem acento e quebra de linha CRLF. As posições foram conferidas com a implementação aberta do [Projeto ACBr](https://www.projetoacbr.com.br/) (`ACBrCargaBal`, modelo MGV6). O layout é declarado como dados (`RegistroFixo` com `Campo`s), o mesmo objeto serve para **gerar e para ler** o arquivo, e esta tabela é produzida por `ITENSMGV.documentar()`:

| Posição | Tam. | Campo | Tipo |
|---|---|---|---|
| 1 | 2 | `departamento` | N |
| 3 | 1 | `tipo_produto` (0 peso, 1 unidade) | N |
| 4 | 6 | `codigo` (PLU) | N |
| 10 | 6 | `preco` | N (2 dec.) |
| 16 | 3 | `validade_dias` | N |
| 19 | 50 | `descritivo` (2 linhas de 25) | A |
| 69 | 6 | `cod_info_extra` → TXINFO.TXT | N |
| 75 | 4 | `cod_imagem` | N |
| 79 | 6 | `cod_info_nutricional` | N |
| 85 | 1 | `imprime_validade` | N |
| 86 | 1 | `imprime_embalagem` | N |
| 87 | 4 | `cod_fornecedor` | N |
| 91 | 12 | `lote` | N |
| 103 | 11 | `cod_ean_especial` | N |
| 114 | 1 | `versao_preco` | N |
| 115 | 4 | `cod_som` | N |
| 119 | 4 | `cod_tara` → TARA.TXT | N |
| 123 | 4 | `cod_fracionador` | N |
| 127 | 4 | `cod_campo_extra1` | N |
| 131 | 4 | `cod_campo_extra2` | N |
| 135 | 4 | `cod_conservacao` | N |
| 139 | 12 | `ean13_fornecedor` | N |

> Sempre confira com o manual do MGV instalado no cliente: versões mais novas acrescentam campos no fim do registro.

### Validações que evitam problema na loja

- Preço que não cabe no campo (acima de R$ 9.999,99 no MGV6) é **rejeitado**, não truncado: uma balança com preço truncado vende errado.
- Preço zerado e PLU duplicado são rejeitados.
- Descrição quebrada em palavra inteira nas duas linhas de 25 caracteres; aviso quando passa de 50.
- Taras iguais viram uma única entrada em `TARA.TXT` (bandeja de 15 g no açougue, 8 g nos frios).
- `--estrito` devolve código de saída 1 se algo foi rejeitado, para uso em rotina agendada.

## O código de barras da balança

O EAN-13 com prefixo 2 é de uso interno da loja e **não tem formato único**: a balança e o PDV precisam estar configurados igual. Suportados (no S1 vêm dos parâmetros `BALANCA_PREFIXO`, `BALANCA_TAM_CODIGO` e `BALANCA_TIPO_VALOR` da empresa):

| Formato | Exemplo (PLU 1001, R$ 12,99) |
|---|---|
| `2 CCCC 0 VVVVVV D` | `2100100012991` |
| `2 CCCCC VVVVVV D` | `2010010012991` |
| `2 CCCCCC VVVVV D` | `2001001012991` |

Com preço embutido, o PDV obtém a quantidade dividindo o total pelo preço/kg do cadastro (`LeituraBalanca.quantidade_para`); se a balança estiver com preço antigo, a quantidade lançada fica errada e o estoque de pesáveis diverge, por isso a carga diária com relatório de alterações importa.

## Testes

```bash
pip install -e ".[dev]"
pytest                                    # 26 testes de unidade
DATABASE_URL=postgresql://balanca_s3:balanca_s3@localhost/postgres pytest   # + 2 de integração com o S1
```

## Estrutura

```
balanca/layouts/base.py         motor de layouts de largura fixa (gera, lê e documenta)
balanca/layouts/toledo_mgv6.py  ITENSMGV, TXINFO, TARA, DEPTO
balanca/layouts/filizola.py     CADTXT, SETORTXT
balanca/codigo_balanca.py       EAN-13 de balança (preço/peso embutido) e dígito GTIN
balanca/gs1.py                  GS1-128: AIs fixos e variáveis, FNC1, datas, peso e preço
balanca/etiquetas.py            etiquetas de gôndola e de balança (ReportLab)
balanca/fonte_erp.py            leitura do S1 (pesáveis, preços vigentes, ofertas, parâmetros) e CSV
balanca/cli.py                  comandos carga, ler, etiquetas, etiqueta-balanca, layouts
exemplos/                       cargas, CSV e PDFs gerados a partir da base do S1
```
