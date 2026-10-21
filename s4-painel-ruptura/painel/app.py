"""Painel web (FastAPI) e API JSON. Só leitura: o usuário do banco não tem permissão de escrita."""
from __future__ import annotations

import csv
import io
import time
from collections import Counter
from decimal import Decimal
from pathlib import Path

from fastapi import FastAPI, HTTPException, Query, Request
from fastapi.responses import HTMLResponse, StreamingResponse
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates

from painel import __version__
from painel.config import Config
from painel.consultas import Repositorio
from painel.notificacao import NOME_TIPO
from painel.regras import Alerta, avaliar_loja

BASE = Path(__file__).parent
app = FastAPI(title="Painel de ruptura, margem e validade", version=__version__)
app.mount("/static", StaticFiles(directory=BASE / "static"), name="static")
templates = Jinja2Templates(directory=BASE / "templates")


def _moeda(valor) -> str:
    if valor is None:
        return "–"
    return f"R$ {float(valor):,.2f}".replace(",", "X").replace(".", ",").replace("X", ".")


def _pct(valor) -> str:
    return "–" if valor is None else f"{float(valor):.1f}".replace(".", ",") + "%"


templates.env.filters["moeda"] = _moeda
templates.env.filters["pct"] = _pct
templates.env.globals["NOME_TIPO"] = NOME_TIPO

_cache: dict[int, tuple[float, list[Alerta]]] = {}
CACHE_SEGUNDOS = 120


def repo() -> Repositorio:
    return Repositorio(Config().database_url)


def alertas_da_loja(loja: int) -> list[Alerta]:
    agora = time.monotonic()
    if loja in _cache and agora - _cache[loja][0] < CACHE_SEGUNDOS:
        return _cache[loja][1]
    alertas = avaliar_loja(repo(), loja)
    _cache[loja] = (agora, alertas)
    return alertas


def _loja(loja_id: int) -> dict:
    loja = next((l for l in repo().lojas() if l["id"] == loja_id), None)
    if not loja:
        raise HTTPException(404, f"Loja {loja_id} não encontrada")
    return loja


def _resumo(alertas: list[Alerta]) -> dict:
    tipos = Counter(a.tipo for a in alertas)
    return {
        "total": len(alertas),
        "altas": sum(1 for a in alertas if a.severidade.name == "ALTA"),
        "por_tipo": dict(tipos),
        "venda_perdida": float(sum(a.impacto for a in alertas if a.tipo in ("RUPTURA", "ESTOQUE_FANTASMA"))),
        "prejuizo_margem_mes": float(sum(a.impacto for a in alertas if a.tipo in ("MARGEM_NEGATIVA", "CUSTO_ACIMA_DO_PRECO"))),
        "validade_em_risco": float(sum(a.impacto for a in alertas if a.tipo == "VALIDADE")),
    }


def _json(v):
    if isinstance(v, Decimal):
        return float(v)
    if hasattr(v, "isoformat"):
        return v.isoformat()
    return v


@app.get("/saude")
def saude():
    r = repo()
    return {"status": "ok", "lojas": len(r.lojas()), "versao": __version__}


@app.get("/api/lojas")
def api_lojas():
    return [{**l, "alertas": _resumo(alertas_da_loja(l["id"]))} for l in repo().lojas()]


@app.get("/api/lojas/{loja_id}/alertas")
def api_alertas(loja_id: int, tipo: str | None = None, severidade: str | None = None):
    _loja(loja_id)
    alertas = [a for a in alertas_da_loja(loja_id)
               if (tipo is None or a.tipo == tipo) and (severidade is None or a.severidade.name == severidade)]
    return [a.como_dict() for a in alertas]


@app.get("/api/lojas/{loja_id}/indicadores")
def api_indicadores(loja_id: int):
    _loja(loja_id)
    r = repo()
    return {"kpis": {k: _json(v) for k, v in r.kpis(loja_id).items()},
            "serie": [{k: _json(v) for k, v in linha.items()} for linha in r.serie(loja_id)],
            "departamentos": [{k: _json(v) for k, v in linha.items()} for linha in r.departamentos(loja_id)]}


@app.get("/lojas/{loja_id}/alertas.csv")
def exportar_csv(loja_id: int):
    _loja(loja_id)
    buf = io.StringIO()
    w = csv.writer(buf, delimiter=";")
    w.writerow(["severidade", "tipo", "produto", "descricao", "mensagem", "acao", "impacto"])
    for a in alertas_da_loja(loja_id):
        w.writerow([a.severidade.name, a.tipo, a.produto_id, a.descricao, a.mensagem, a.acao, f"{a.impacto:.2f}".replace(".", ",")])
    return StreamingResponse(iter(["﻿" + buf.getvalue()]), media_type="text/csv",
                             headers={"Content-Disposition": f"attachment; filename=alertas_loja_{loja_id}.csv"})


@app.get("/", response_class=HTMLResponse)
def pagina_rede(request: Request):
    r = repo()
    lojas = []
    for l in r.lojas():
        lojas.append({**l, "kpis": r.kpis(l["id"]), "resumo": _resumo(alertas_da_loja(l["id"]))})
    return templates.TemplateResponse(request, "rede.html", {"lojas": lojas})


@app.get("/loja/{loja_id}", response_class=HTMLResponse)
def pagina_loja(request: Request, loja_id: int, tipo: str | None = Query(None)):
    loja = _loja(loja_id)
    r = repo()
    alertas = alertas_da_loja(loja_id)
    filtrados = [a for a in alertas if tipo is None or a.tipo == tipo]
    return templates.TemplateResponse(request, "loja.html", {
        "loja": loja, "kpis": r.kpis(loja_id), "departamentos": r.departamentos(loja_id),
        "serie": [{k: _json(v) for k, v in linha.items()} for linha in r.serie(loja_id)],
        "resumo": _resumo(alertas), "alertas": filtrados[:300], "total_filtrado": len(filtrados), "tipo": tipo,
    })
