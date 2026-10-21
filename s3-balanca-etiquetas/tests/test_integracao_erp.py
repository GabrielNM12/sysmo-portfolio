"""Rodam contra o banco do S1 (no CI e localmente com DATABASE_URL)."""
from balanca import fonte_erp
from balanca.codigo_balanca import ConfigCodigoBalanca
from balanca.layouts import obter_layout


def test_carga_a_partir_do_erp(dsn_erp):
    itens = fonte_erp.itens_do_erp(2, dsn_erp)
    assert len(itens) > 50
    assert all(1000 < i.codigo < 10000 for i in itens)
    res = obter_layout("toledo-mgv6").gerar(itens)
    assert not res.rejeitados
    assert {l[:2] for l in res.arquivos["ITENSMGV.TXT"]} == {"01", "02", "03", "04"}


def test_etiquetas_e_parametros(dsn_erp):
    etqs = fonte_erp.etiquetas_do_erp(2, so_promocao=True, dsn=dsn_erp)
    assert etqs and all(e.preco_de and e.preco < e.preco_de for e in etqs)
    cfg = ConfigCodigoBalanca.de_parametros(fonte_erp.parametros_balanca(2, dsn_erp))
    assert cfg.prefixo == "2" and cfg.digitos_codigo == 4
