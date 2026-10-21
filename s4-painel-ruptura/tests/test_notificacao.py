import json
from datetime import datetime, timedelta
from decimal import Decimal

import httpx

from painel.notificacao import MemoriaAlertas, Telegram, Webhook, formatar_resumo
from painel.regras import Alerta, Severidade


def alerta(produto=1, sev=Severidade.ALTA, tipo="RUPTURA", impacto="100"):
    return Alerta(tipo, sev, 2, produto, f"PRODUTO {produto} <TESTE>", "msg", "acao", Decimal(impacto))


def test_resumo_ordenado_e_escapado():
    texto = formatar_resumo("Oeste Centro", [alerta(1), alerta(2, tipo="VALIDADE", sev=Severidade.BAIXA)])
    assert "<b>Oeste Centro</b> · 2 alerta(s)" in texto
    assert "Ruptura: 1 | Validade: 1" in texto
    assert "R$ 100,00" in texto
    assert "&lt;TESTE&gt;" in texto                     # HTML do Telegram não quebra com nome de produto


def test_resumo_vazio():
    assert "Sem alertas novos" in formatar_resumo("Loja", [])


def test_memoria_nao_repete_mas_escala_severidade():
    m = MemoriaAlertas()
    agora = datetime(2026, 10, 21, 8, 0)
    primeiro = [alerta(1, Severidade.MEDIA), alerta(2)]
    m.registrar(m.filtrar_novos(primeiro, timedelta(hours=24), agora), agora)

    depois = agora + timedelta(hours=3)
    assert m.filtrar_novos(primeiro, timedelta(hours=24), depois) == []
    piorou = alerta(1, Severidade.ALTA)
    assert m.filtrar_novos([piorou], timedelta(hours=24), depois) == [piorou]
    assert len(m.filtrar_novos(primeiro, timedelta(hours=24), agora + timedelta(hours=25))) == 2


def test_telegram_e_webhook_enviam_payload_certo():
    recebidos = []

    def responder(request: httpx.Request):
        recebidos.append((str(request.url), json.loads(request.content)))
        return httpx.Response(200, json={"ok": True})

    cliente = httpx.Client(transport=httpx.MockTransport(responder))
    Telegram("TOKEN", "-100123", cliente).enviar("linha\n" * 1500)          # > 4096 caracteres: divide
    Webhook("https://hooks.example/abc", cliente).enviar("oi")
    urls = [u for u, _ in recebidos]
    assert urls.count("https://api.telegram.org/botTOKEN/sendMessage") == 3
    assert all(len(p["text"]) <= 4096 for u, p in recebidos if "telegram" in u)
    assert recebidos[0][1]["parse_mode"] == "HTML" and recebidos[0][1]["chat_id"] == "-100123"
    assert recebidos[-1] == ("https://hooks.example/abc", {"text": "oi"})
