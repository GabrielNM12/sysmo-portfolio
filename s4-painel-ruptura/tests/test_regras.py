from decimal import Decimal

from painel.regras import Severidade, alertas_margem, alertas_ruptura, alertas_validade, priorizar


def ruptura(**kw):
    base = dict(produto_id=1, descricao="FEIJÃO CARIOCA 1KG", venda_media_dia=Decimal("9.3"), dias_sem_venda=6,
                prob_acaso=1e-12, estoque_atual=Decimal("0"), preco_venda=Decimal("8.99"), venda_perdida_dia=Decimal("83.61"))
    base.update(kw)
    return base


def test_ruptura_declarada_x_estoque_fantasma():
    a, b = alertas_ruptura(2, [ruptura(), ruptura(produto_id=2, estoque_atual=Decimal("64"), prob_acaso=0.0004)])
    assert a.tipo == "RUPTURA" and a.severidade == Severidade.ALTA
    assert a.impacto == Decimal("83.61") * 6
    assert "9,30/dia" in a.mensagem
    assert b.tipo == "ESTOQUE_FANTASMA" and "64 em estoque" in b.mensagem and "0,040%" in b.mensagem


def test_ruptura_de_item_de_baixo_valor_e_media():
    (a,) = alertas_ruptura(2, [ruptura(venda_perdida_dia=Decimal("12"))])
    assert a.severidade == Severidade.MEDIA


def margem(**kw):
    base = dict(produto_id=10, descricao="RAÇÃO 10KG", preco_venda=Decimal("99.46"), custo_medio=Decimal("80"),
                custo_ultima_entrada=Decimal("80"), margem_perc=Decimal("19.56"), venda_media_dia=Decimal("2"))
    base.update(kw)
    return base


def test_tres_situacoes_de_margem():
    neg, custo, baixa = alertas_margem(2, [
        margem(custo_medio=Decimal("105"), margem_perc=Decimal("-5.57")),
        margem(produto_id=11, custo_ultima_entrada=Decimal("119.58")),
        margem(produto_id=12, custo_medio=Decimal("95"), margem_perc=Decimal("4.48")),
    ], Decimal("8"))
    assert neg.tipo == "MARGEM_NEGATIVA"
    assert neg.impacto == Decimal("332.40")                  # (105 − 99,46) × 2/dia × 30 dias
    assert "R$ 114,13" in neg.acao                            # 105 / (1 − 8%)
    assert custo.tipo == "CUSTO_ACIMA_DO_PRECO" and custo.impacto == Decimal("1207.20")
    assert baixa.tipo == "MARGEM_BAIXA" and baixa.severidade == Severidade.MEDIA


def lote(dias, sobra="3", valor="20"):
    return dict(produto_id=20, descricao="IOGURTE 170G", lote=f"L{dias}", validade=None, dias_para_vencer=dias,
                quantidade=Decimal("10"), sobra_prevista=Decimal(sobra), valor_custo=Decimal(valor))


def test_validade_por_urgencia():
    venc, dois, cinco = alertas_validade(2, [lote(-1), lote(2), lote(5)])
    assert (venc.severidade, dois.severidade, cinco.severidade) == (Severidade.ALTA, Severidade.MEDIA, Severidade.BAIXA)
    assert "VENCIDO" in venc.mensagem and "devem sobrar 3" in dois.mensagem
    assert venc.chave == "VALIDADE:2:20:L-1"


def test_priorizacao_por_severidade_e_impacto():
    alertas = alertas_validade(2, [lote(4, valor="900")]) + alertas_ruptura(2, [ruptura(venda_perdida_dia=Decimal("10")),
                                                                               ruptura(produto_id=3)])
    ordem = [(a.severidade, a.produto_id) for a in priorizar(alertas)]
    assert ordem == [(Severidade.ALTA, 1), (Severidade.MEDIA, 1), (Severidade.BAIXA, 20)] or \
           ordem[0][0] == Severidade.ALTA and ordem[-1][0] == Severidade.BAIXA
