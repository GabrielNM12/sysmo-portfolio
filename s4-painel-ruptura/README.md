# S4 · Painel de ruptura, margem e validade com alertas

Painel web e rotina de alertas que leem a base do ERP **em modo somente leitura** e respondem às três perguntas que todo gerente de supermercado faz de manhã:

1. **O que deixei de vender?** Ruptura declarada (sem estoque) e *estoque fantasma* (o sistema diz que tem, a gôndola diz que não).
2. **Onde estou perdendo margem?** Margem negativa, abaixo do mínimo e o caso traiçoeiro: **o fornecedor reajustou, a última nota veio acima do preço de venda, mas o custo médio ainda esconde o prejuízo**.
3. **O que vai vencer na prateleira?** Só o que **deve sobrar** no ritmo atual de venda (projeção FEFO), não toda a lista de perecíveis.

Cada alerta vem com a **ação sugerida** e o **impacto em R$**, e a lista é ordenada por severidade e impacto. Os alertas vão por Telegram (ou webhook para Slack/Teams/Zenvia/n8n) sem repetir a mesma mensagem todo dia.

![Painel da loja](docs/painel_loja.png)

## Como os alertas funcionam

### Ruptura com estatística, não com "3 dias sem venda"

Regra fixa de dias sem venda gera alarme falso em item de giro baixo e demora a alertar item de giro alto. Aqui cada item tem a sua taxa de compras por dia (λ, contando **cupons**, não unidades: quem leva 6 latas fez uma compra) e a chance de passar *d* dias sem nenhuma compra por puro acaso é:

$$P(\text{0 compras em } d \text{ dias}) = e^{-\lambda d}$$

O alerta dispara abaixo de **0,1%**. O limite é rígido porque o teste roda para milhares de itens todo dia (com 1%, sobrariam dezenas de falsos positivos por loja). λ é medido numa janela "limpa" de −35 a −8 dias, para a própria ruptura não puxar a média para baixo.

Na base do S1 isso encontra 13 a 19 itens por loja, praticamente só os casos plantados de propósito; com o critério de unidades e 1% eram mais de 130.

| Situação | Severidade | Ação sugerida |
|---|---|---|
| Sem venda improvável **e estoque zero** | Ruptura (alta se a venda perdida passa de R$ 50/dia) | Verificar pedido de compra / transferir do CD |
| Sem venda improvável **e estoque positivo** | Estoque fantasma (alta) | Conferir gôndola e depósito; ajustar inventário |

### Margem

| Situação | Impacto calculado |
|---|---|
| Custo médio acima do preço | prejuízo de 30 dias no ritmo atual + **preço mínimo** para a margem mínima da loja |
| Última entrada acima do preço (custo médio ainda positivo) | perda de 30 dias quando o estoque antigo acabar |
| Margem abaixo do mínimo da empresa (`MARGEM_MINIMA_PERC`) | margem que falta para o mínimo |

### Validade (FEFO)

Lotes ordenados por validade; para cada lote, *sobra prevista = estoque acumulado até o lote − venda média × dias até vencer*. Pão que vence em 2 dias e vende tudo não alerta; iogurte com 40 unidades e venda de 3 por dia alerta com o valor a custo do que vai sobrar.

Os limites vêm da tabela de parâmetros da empresa no ERP (`RUPTURA_PROB_MAX`, `MARGEM_MINIMA_PERC`, `VALIDADE_ALERTA_DIAS`), como faria um parâmetro do Sgr101.

### Sem spam

Cada alerta tem uma chave (`tipo:loja:produto:lote`). Uma pequena base SQLite guarda o que já foi enviado; o mesmo alerta só volta depois de 24 h (configurável) ou **se a severidade aumentar**.

## Cuidado com o servidor da loja

- Usuário de banco `painel_s4` com `default_transaction_read_only = on` e `statement_timeout = 30s`; sem acesso a nome, CPF, e-mail ou telefone de clientes (privilégio por coluna no S1).
- Nenhuma consulta lê a tabela de itens de cupom: tudo sai da visão materializada `mv_venda_diaria` (atualizada à noite) e do saldo em `produto_loja`.
- Resultado dos alertas em cache por 2 minutos no painel.
- Chart.js servido pelo próprio painel: funciona na intranet da loja sem internet.

## Como rodar

```bash
pip install -e ".[dev]"
export DATABASE_URL=postgresql://painel_s4:painel_s4@localhost/postgres

painel servidor --porta 8040                    # http://localhost:8040
painel alertas --loja 2                         # imprime no console
TELEGRAM_BOT_TOKEN=... TELEGRAM_CHAT_ID=... painel alertas --canal telegram   # agende no cron às 7h
```

API JSON: `GET /api/lojas`, `GET /api/lojas/{id}/alertas?tipo=RUPTURA&severidade=ALTA`, `GET /api/lojas/{id}/indicadores`, `GET /lojas/{id}/alertas.csv`, `GET /saude`.

Exemplo de mensagem no Telegram:

```
Oeste Centro (São Miguel do Oeste) · 137 alerta(s)
Ruptura: 9 | Estoque fantasma: 6 | Custo acima do preço: 6 | Margem negativa: 24 | Validade: 92
Venda perdida estimada com ruptura: R$ 6.233,53

🔴 Ruptura · VINHO BRANCO SUAVE CHARDONNAY ADEGA BIANCHI 7
   Sem estoque e sem venda há 11 dias (vendia 2,25/dia)
   ➜ Verificar pedido de compra em aberto / transferir do CD
🔴 Estoque fantasma · POTE PLÁSTICO GRANDE BRASA VIVA 1UN
   Sistema mostra 64 em estoque, mas não vende há 5 dias (chance de ser acaso: < 0,001%)
   ➜ Conferir gôndola e depósito; se não achar, ajustar inventário
```

## Testes

```bash
pytest                                     # 9 testes de regras e notificação (sem banco)
DATABASE_URL=postgresql://painel_s4:painel_s4@localhost/postgres pytest   # + 3 de integração
```

Os testes de integração verificam que os cenários plantados no S1 são encontrados, que o limite estatístico não gera enxurrada de falsos positivos e que o usuário do painel **não consegue** alterar preço nem ler CPF.

![Visão da rede](docs/painel_rede.png)

## Estrutura

```
painel/consultas.py     SQL somente leitura (ruptura Poisson, margem, validade FEFO, KPIs)
painel/regras.py        transforma linhas em alertas com severidade, ação e impacto em R$
painel/notificacao.py   console, Telegram, webhook; memória anti-repetição
painel/app.py           FastAPI: páginas, API JSON e exportação CSV
painel/cli.py           painel alertas | painel servidor
```
