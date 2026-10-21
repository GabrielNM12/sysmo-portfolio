"""Envio de alertas (console, Telegram, webhook) com memória do que já foi enviado.

Gerente que recebe o mesmo alerta todo dia passa a ignorar todos. Por isso cada alerta tem
uma chave e só é reenviado depois de N horas ou se a severidade aumentar.
"""
from __future__ import annotations

import html
import sqlite3
from datetime import datetime, timedelta
from typing import Protocol

import httpx

from painel.regras import Alerta, Severidade

ICONE = {Severidade.ALTA: "🔴", Severidade.MEDIA: "🟠", Severidade.BAIXA: "🟡"}
NOME_TIPO = {"RUPTURA": "Ruptura", "ESTOQUE_FANTASMA": "Estoque fantasma", "MARGEM_NEGATIVA": "Margem negativa",
             "CUSTO_ACIMA_DO_PRECO": "Custo acima do preço", "MARGEM_BAIXA": "Margem baixa", "VALIDADE": "Validade"}


class Canal(Protocol):
    nome: str

    def enviar(self, texto: str) -> None: ...


class Console:
    nome = "console"

    def enviar(self, texto: str) -> None:
        print(texto)


class Telegram:
    nome = "telegram"

    def __init__(self, token: str, chat_id: str, cliente: httpx.Client | None = None):
        self.url = f"https://api.telegram.org/bot{token}/sendMessage"
        self.chat_id = chat_id
        self.cliente = cliente or httpx.Client(timeout=10)

    def enviar(self, texto: str) -> None:
        for parte in _partes(texto, 4000):          # limite de 4096 caracteres por mensagem
            r = self.cliente.post(self.url, json={"chat_id": self.chat_id, "text": parte, "parse_mode": "HTML"})
            r.raise_for_status()


class Webhook:
    """Para Slack/Teams/Zenvia/n8n: POST com JSON {"text": ...}."""
    nome = "webhook"

    def __init__(self, url: str, cliente: httpx.Client | None = None):
        self.url = url
        self.cliente = cliente or httpx.Client(timeout=10)

    def enviar(self, texto: str) -> None:
        self.cliente.post(self.url, json={"text": texto}).raise_for_status()


def _partes(texto: str, limite: int) -> list[str]:
    partes, atual = [], ""
    for linha in texto.split("\n"):
        if len(atual) + len(linha) + 1 > limite:
            partes.append(atual)
            atual = ""
        atual += linha + "\n"
    return partes + [atual] if atual.strip() else partes


def _moeda(v) -> str:
    return f"R$ {float(v):,.2f}".replace(",", "X").replace(".", ",").replace("X", ".")


def formatar_resumo(loja_nome: str, alertas: list[Alerta], maximo: int = 12) -> str:
    """Mensagem curta, ordenada por impacto: o gerente lê no celular entre um caixa e outro."""
    if not alertas:
        return f"<b>{html.escape(loja_nome)}</b>\nSem alertas novos. ✅"
    contagem: dict[str, int] = {}
    for a in alertas:
        contagem[a.tipo] = contagem.get(a.tipo, 0) + 1
    impacto = sum(a.impacto for a in alertas if a.tipo in ("RUPTURA", "ESTOQUE_FANTASMA"))
    linhas = [f"<b>{html.escape(loja_nome)}</b> · {len(alertas)} alerta(s)",
              " | ".join(f"{NOME_TIPO.get(t, t)}: {n}" for t, n in contagem.items())]
    if impacto:
        linhas.append(f"Venda perdida estimada com ruptura: <b>{_moeda(impacto)}</b>")
    linhas.append("")
    for a in alertas[:maximo]:
        linhas.append(f"{ICONE[a.severidade]} <b>{NOME_TIPO.get(a.tipo, a.tipo)}</b> · {html.escape(a.descricao[:45])}")
        linhas.append(f"   {html.escape(a.mensagem)}")
        linhas.append(f"   ➜ {html.escape(a.acao)}")
    if len(alertas) > maximo:
        linhas.append(f"\n… e mais {len(alertas) - maximo} no painel.")
    return "\n".join(linhas)


class MemoriaAlertas:
    def __init__(self, caminho: str = ":memory:"):
        self.db = sqlite3.connect(caminho)
        self.db.execute("create table if not exists enviado (chave text primary key, severidade int, enviado_em text)")

    def filtrar_novos(self, alertas: list[Alerta], reenviar_apos: timedelta, agora: datetime | None = None) -> list[Alerta]:
        agora = agora or datetime.now()
        novos = []
        for a in alertas:
            linha = self.db.execute("select severidade, enviado_em from enviado where chave = ?", (a.chave,)).fetchone()
            if linha is None or a.severidade > linha[0] or agora - datetime.fromisoformat(linha[1]) >= reenviar_apos:
                novos.append(a)
        return novos

    def registrar(self, alertas: list[Alerta], agora: datetime | None = None) -> None:
        agora = (agora or datetime.now()).isoformat(timespec="seconds")
        self.db.executemany("insert or replace into enviado values (?, ?, ?)",
                            [(a.chave, int(a.severidade), agora) for a in alertas])
        self.db.commit()
