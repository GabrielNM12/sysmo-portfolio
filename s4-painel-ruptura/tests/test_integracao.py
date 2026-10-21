"""Rodam contra o S1 com o usuário painel_s4 (somente leitura, sem dados pessoais)."""
import psycopg
import pytest
from fastapi.testclient import TestClient

from painel.consultas import Repositorio
from painel.regras import Severidade, avaliar_loja


def test_detecta_os_cenarios_plantados_no_s1(dsn):
    alertas = avaliar_loja(Repositorio(dsn), 2)
    tipos = {a.tipo for a in alertas}
    assert {"RUPTURA", "ESTOQUE_FANTASMA", "MARGEM_NEGATIVA", "VALIDADE"} <= tipos
    assert alertas[0].severidade == Severidade.ALTA
    fantasmas = [a for a in alertas if a.tipo in ("RUPTURA", "ESTOQUE_FANTASMA")]
    assert len(fantasmas) < 40, "limite estatístico deve evitar enxurrada de falsos positivos"


def test_usuario_do_painel_nao_escreve_nem_le_cpf(dsn):
    with psycopg.connect(dsn, autocommit=True) as conn:
        with pytest.raises(psycopg.errors.ReadOnlySqlTransaction):
            conn.execute("update erp.produto_loja set preco_venda = 0 where produto_id = 1")
        with pytest.raises(psycopg.errors.InsufficientPrivilege):
            conn.execute("select cpf_cnpj from erp.transacionador limit 1")


def test_api_e_paginas(dsn, monkeypatch):
    monkeypatch.setenv("DATABASE_URL", dsn)
    from painel.app import app

    c = TestClient(app)
    assert c.get("/saude").json()["status"] == "ok"
    lojas = c.get("/api/lojas").json()
    assert len(lojas) == 5 and lojas[0]["alertas"]["total"] > 0
    rupturas = c.get("/api/lojas/2/alertas", params={"tipo": "RUPTURA"}).json()
    assert rupturas and all(a["tipo"] == "RUPTURA" for a in rupturas)
    ind = c.get("/api/lojas/2/indicadores").json()
    assert len(ind["serie"]) >= 40 and ind["kpis"]["margem_30d"] > 0
    assert c.get("/").status_code == 200
    pagina = c.get("/loja/2?tipo=VALIDADE")
    assert pagina.status_code == 200 and "Validade" in pagina.text
    assert c.get("/lojas/2/alertas.csv").text.startswith("﻿severidade;tipo")
    assert c.get("/loja/999").status_code == 404
