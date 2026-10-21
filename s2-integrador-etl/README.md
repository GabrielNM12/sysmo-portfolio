# S2 · Integrador ETL no estilo do Sysmo Integrador

Serviço Java/Quarkus que importa **pedidos de e-commerce** (contrato inspirado em SiteMercado/iFood) para o ERP de laboratório (S1), reproduzindo a arquitetura descrita no manual público de integração do Sysmo Integrador:

- **grupos de processos** declarados em YAML, com variáveis globais;
- **etapas reutilizáveis** (`BUSCAR WEBSERVICE`, `EXTRAIR LOJA`, `SALVAR CLIENTE`, `PREPARAR CABEÇALHO`…) executadas em sequência;
- conexão com a base do ERP e credenciais do webservice lidas do **cadastro de parâmetros da empresa** (no S1 real, Sgr101 › Integrações › E-commerce);
- pedidos só entram no ERP **depois que a separação foi finalizada** na plataforma.

> Projeto de estudo. Não usa código, API nem banco da Sysmo; a API do e-commerce é um simulador embutido.

## O grupo de processos

[`processos/sitemercado-pedidos.yml`](src/main/resources/processos/sitemercado-pedidos.yml):

```yaml
nome: SITEMERCADO_PEDIDOS
variaveis: { empresa_id: 2, webservice: SITEMERCADO, origem: SITEMERCADO, status_importar: SEPARACAO_FINALIZADA }
etapas:
  - { nome: BUSCAR WEBSERVICE, tipo: buscar-webservice, parametros: { empresa: "${empresa_id}", nome: "${webservice}" } }
  - { nome: LOGIN NA API,      tipo: autenticar-api,  retentativas: 3, intervaloMs: 300 }
  - { nome: LISTAR PEDIDOS,    tipo: listar-pedidos,  retentativas: 3, intervaloMs: 300 }
  - nome: PROCESSAR PEDIDOS
    paraCada: pedidos          # um pedido com erro não impede os outros
    como: pedido
    etapas:
      - EXTRAIR LOJA → EXTRAIR CLIENTE → EXTRAIR ENDERECO → VERIFICAR DUPLICIDADE
      - SALVAR CLIENTE, PREPARAR CABECALHO, PREPARAR ITENS, GRAVAR PEDIDO   (executarSe: "!ja_integrado")
      - CONFIRMAR INTEGRACAO (retentativas: 3)
```

Mudar a ordem, a política de retentativa ou ligar outra loja é configuração; o motor valida tudo **na partida** (tipo de etapa inexistente derruba o deploy, não a madrugada).

| Etapa | O que faz | Detalhe de domínio |
|---|---|---|
| BUSCAR WEBSERVICE | lê URL, client_id e secret da empresa no ERP | um cadastro por loja |
| LOGIN NA API | OAuth2 client credentials | 401/403 não é retentado (credencial errada não melhora sozinha) |
| LISTAR PEDIDOS | só `SEPARACAO_FINALIZADA` e não integrados | pedido em separação ainda muda: item em falta, peso real |
| EXTRAIR LOJA | CNPJ da loja → código da empresa | valida DV do CNPJ |
| EXTRAIR CLIENTE | normaliza e valida CPF | o log mostra o CPF mascarado (LGPD) |
| EXTRAIR ENDERECO | entrega exige endereço; retirada na loja não | |
| VERIFICAR DUPLICIDADE | pedido já está no ERP? | habilita o "exatamente uma vez" |
| SALVAR CLIENTE | cria ou atualiza o **transacionador** pelo CPF + endereço de entrega | cliente e fornecedor são a mesma entidade no ERP |
| PREPARAR ITENS | EAN → produto (embalados) e **PLU de balança** → produto (pesáveis); usa a **quantidade separada** | item em falta não é faturado; preço do site diferente do ERP vira observação |
| GRAVAR PEDIDO | pedido + itens + **baixa de estoque no livro-razão** numa transação | o gatilho do ERP atualiza saldo e custo; o integrador nunca escreve no saldo |
| CONFIRMAR INTEGRACAO | devolve o número do pedido no ERP à plataforma | retentado em falha transitória |

## Comportamentos que importam em produção

- **Retentativa com espera exponencial** (300 ms, 600 ms, 1,2 s…) só para falha transitória: HTTP 429/5xx, timeout, perda de conexão, deadlock. Erro de negócio (EAN não cadastrado, CPF inválido) vai direto para pendência.
- **Isolamento por pedido**: um pedido com produto que não existe no ERP fica pendente, com o motivo no log, e os demais entram. A execução termina como `PARCIAL`.
- **Exatamente uma vez**: chave única `(origem, numero_externo)` no ERP + etapa de duplicidade. Se o pedido foi gravado e a confirmação se perdeu, a próxima execução só reconfirma; se duas execuções concorrem, a violação de chave é tratada como "já integrado".
- **Log por etapa e por tentativa** no schema `integrador` (`execucao`, `execucao_etapa`), consultável pela API.
- **Uma execução por grupo de cada vez** (agendada ou manual); a manual concorrente recebe HTTP 409.
- **Menor privilégio**: usuário `integrador_s2` pode gravar pedidos, clientes e movimentos de estoque, mas não altera preço nem lê cupons.

## Como rodar

Pré-requisitos: Java 21, Maven 3.9 e o S1 carregado (veja a raiz do repositório).

```bash
mvn quarkus:dev                     # http://localhost:8089  (Swagger em /q/swagger-ui)

# 1. a plataforma recebe pedidos (simulador)
curl -X POST "localhost:8089/sitemercado/_simulador/pedidos?quantidade=3"
curl -X POST "localhost:8089/sitemercado/_simulador/pedidos?cenario=PESAVEL"
curl -X POST "localhost:8089/sitemercado/_simulador/pedidos?cenario=EAN_DESCONHECIDO"
curl -X POST "localhost:8089/sitemercado/_simulador/falhas?quantidade=2"    # API instável

# 2. roda o grupo de processos (também roda sozinho a cada 60 s)
curl -X POST localhost:8089/integrador/grupos/SITEMERCADO_PEDIDOS/executar
# {"status":"PARCIAL","itensOk":4,"itensComErro":1,"itensIgnorados":0,...}

# 3. log de cada etapa
curl localhost:8089/integrador/execucoes/1
```

Cenários do simulador: `NORMAL`, `PESAVEL` (pedido 1 kg, separado 1,085 kg), `ITEM_EM_FALTA`, `PRECO_PROMOCIONAL`, `EAN_DESCONHECIDO`, `CPF_INVALIDO`, `EM_SEPARACAO`, `RETIRADA`.

## Testes

`mvn verify` roda 21 testes; o CI carrega o S1 num PostgreSQL e executa todos.

| Teste | Verifica |
|---|---|
| `MotorDeProcessosTest` | retentativa com backoff, erro de negócio sem retentativa, isolamento por item, condição `executarSe`, parâmetros `${...}` |
| `ContextoTest` | escopo global × item, navegação em mapas/listas, expressões |
| `CarregadorDeGruposTest` | YAML válido; tipo inexistente falha na partida |
| `DocumentosTest` | DV de CPF, CNPJ e GTIN |
| `IntegracaoSiteMercadoTest` | ponta a ponta contra o PostgreSQL: pedidos entram, em separação não, pesável com peso separado, retirada sem frete, **baixa de estoque no razão**, pendência sem travar os outros, retentativa vencendo a instabilidade, confirmação perdida sem duplicar pedido |

## Estrutura

```
motor/        DefinicaoGrupo/Etapa (YAML), Contexto, MotorDeProcessos, RegistroDeEtapas, CarregadorDeGrupos
etapas/       uma classe por etapa + ClienteApi (HTTP → decisão de retentativa)
banco/        LogEmBanco (schema integrador), Documentos (CPF/CNPJ/GTIN)
api/          REST /integrador e agendamento
simulador/    API de e-commerce simulada com injeção de falhas
```
