# Portfólio · domínio de ERP para supermercados (estilo Sysmo S1)

Projetos práticos para demonstrar conhecimento do **varejo alimentar** e do tipo de integração que gira em torno de ERPs de supermercado como o **Sysmo S1**: modelo de dados de rede com CD e lojas, PDV/NFC-e, estoque com custo médio, balanças, integração com e-commerce, BI de ruptura e recebimento de mercadorias.

> Projetos independentes, com dados 100% sintéticos e marcas fictícias. Não há afiliação com a Sysmo Sistemas nem uso de código ou banco do Sysmo S1; o vocabulário (transacionador, Sgr101, grupos de processos do Integrador) vem de material público.

| Projeto | O que mostra | Stack | Situação |
|---|---|---|---|
| [S1 · Mini-ERP de supermercado](s1-mini-erp-postgres/) | Modelagem de rede com CD, 1 milhão de itens vendidos, livro-razão de estoque com custo médio móvel, NFC-e, perfis de acesso LGPD, 12 consultas analíticas | PostgreSQL 16, SQL/PLpgSQL | ✅ |
| [S2 · Integrador ETL](s2-integrador-etl/) | Motor de "grupos de processos" em YAML, etapas reutilizáveis, retentativa com backoff, log por etapa, idempotência; integração de pedidos de e-commerce | Java 21, Quarkus, PostgreSQL | 🚧 |
| [S3 · Balança e etiquetas](s3-balanca-etiquetas/) | Carga Toledo MGV6 e Filizola, EAN de balança com preço/peso embutido, GS1-128, etiquetas de gôndola | Python, ReportLab | ✅ |
| [S4 · Painel de ruptura e margem](s4-painel-ruptura/) | Ruptura estatística (Poisson), estoque fantasma, margem corroída por reajuste, validade com projeção FEFO, alertas no Telegram | Python, FastAPI, Chart.js | ✅ |
| S5 · Portal de agendamento de recebimento | Fornecedor agenda doca e envia XML da NF-e; confronto pedido de compra × nota | Java 21, Quarkus, React | 🚧 |

Todos usam a base do **S1** como ERP de laboratório, cada um com seu usuário de banco de menor privilégio.

```mermaid
flowchart LR
    SM[E-commerce<br/>SiteMercado simulado] -->|pedidos| S2[S2 Integrador ETL]
    S2 -->|pedido de venda + baixa de estoque| S1[(S1 ERP<br/>PostgreSQL)]
    S1 -->|pesáveis e preços| S3[S3 Carga de balança<br/>e etiquetas]
    S3 --> BAL[Balanças Toledo/Filizola<br/>e gôndola]
    S1 -->|somente leitura| S4[S4 Painel de ruptura]
    S4 -->|alertas| TG[Telegram do gerente]
    FORN[Fornecedor] -->|agenda + XML NF-e| S5[S5 Portal de recebimento]
    S5 -->|confronto pedido × nota| S1
```

## Rodando tudo

Requisitos: PostgreSQL 14+ (ou Docker), Python 3.10+, Java 21 + Maven.

```bash
# banco de laboratório
docker run -d --name erp -e POSTGRES_PASSWORD=postgres -p 5432:5432 postgres:16
export PGHOST=localhost PGUSER=postgres PGPASSWORD=postgres PGDATABASE=postgres
./s1-mini-erp-postgres/scripts/carregar.sh          # ~90 s

# S3 e S4
pip install -e "s3-balanca-etiquetas[dev]" -e "s4-painel-ruptura[dev]"
DATABASE_URL=postgresql://painel_s4:painel_s4@localhost/postgres painel servidor   # http://localhost:8040
```

O CI (GitHub Actions) sobe um PostgreSQL, carrega o S1 em escala completa, roda as verificações de integridade e os testes de todos os projetos.
