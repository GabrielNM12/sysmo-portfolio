from datetime import date
from decimal import Decimal

import pytest

from balanca.codigo_balanca import (ConfigCodigoBalanca, digito_ean, eh_codigo_balanca, gerar_codigo,
                                    gtin_valido, interpretar_codigo)
from balanca.gs1 import GS, interpretar_gs1, montar_gs1


def test_digito_ean_conhecido():
    assert digito_ean("789100010010") == 3        # GTIN-13 real de exemplo
    assert gtin_valido("7891000100103")
    assert not gtin_valido("7891000100104")


@pytest.mark.parametrize("cfg, ean", [
    (ConfigCodigoBalanca(), "2100100012991"),                                    # 2 CCCC 0 VVVVVV D
    (ConfigCodigoBalanca(digitos_codigo=5), "2010010012991"),                     # 2 CCCCC VVVVVV D
    (ConfigCodigoBalanca(digitos_codigo=6, digitos_valor=5), "2001001012991"),    # 2 CCCCCC VVVVV D
])
def test_preco_embutido_ida_e_volta(cfg, ean):
    gerado = gerar_codigo(1001, preco_total=Decimal("12.99"), config=cfg)
    assert gerado == ean
    leitura = interpretar_codigo(gerado, cfg)
    assert leitura.codigo == 1001 and leitura.preco_total == Decimal("12.99")


def test_peso_embutido_e_conversao_no_pdv():
    cfg = ConfigCodigoBalanca(tipo_valor="PESO")
    ean = gerar_codigo(1020, peso_kg=Decimal("1.235"), config=cfg)
    leitura = interpretar_codigo(ean, cfg)
    assert leitura.peso_kg == Decimal("1.235")
    assert leitura.total_para(Decimal("10.87")) == Decimal("13.42")


def test_pdv_converte_preco_em_quantidade():
    leitura = interpretar_codigo(gerar_codigo(1001, preco_total=Decimal("13.42")))
    assert leitura.quantidade_para(Decimal("10.87")) == Decimal("1.235")


def test_configuracao_vinda_dos_parametros_do_erp():
    cfg = ConfigCodigoBalanca.de_parametros({"BALANCA_PREFIXO": "2", "BALANCA_TAM_CODIGO": "5", "BALANCA_TIPO_VALOR": "W"})
    assert cfg == ConfigCodigoBalanca(digitos_codigo=5, tipo_valor="PESO")


def test_rejeita_etiqueta_danificada_e_valor_grande():
    assert eh_codigo_balanca("2100100012991")
    assert not eh_codigo_balanca("2100100012994")
    with pytest.raises(ValueError, match="verificador"):
        interpretar_codigo("2100100012994")
    with pytest.raises(ValueError, match="não cabe"):
        gerar_codigo(1001, preco_total=Decimal("10000.00"))
    with pytest.raises(ValueError, match="Prefixo"):
        interpretar_codigo("7891000100103")


def test_gs1_legivel_carne_com_peso_variavel():
    l = interpretar_gs1("(01)17891234567892(11)261015(17)261031(10)LT4521(3103)012345")
    assert l.gtin == "17891234567892"
    assert l.producao == date(2026, 10, 15) and l.validade == date(2026, 10, 31)
    assert l.lote == "LT4521"
    assert l.peso_liquido_kg == Decimal("12.345")
    assert l.vencido(hoje=date(2026, 11, 1)) and not l.vencido(hoje=date(2026, 10, 31))


def test_gs1_cru_com_fnc1_e_preco():
    campos = {"01": "17891234567892", "10": "A7", "3922": "4590", "17": "270200"}
    cru = montar_gs1(campos)
    assert GS in cru
    l = interpretar_gs1(cru)
    assert l.campos == campos
    assert l.preco == Decimal("45.90")
    assert l.validade == date(2027, 2, 28)          # dia 00 = último dia do mês


def test_gs1_detecta_problemas():
    with pytest.raises(ValueError, match="dígito verificador"):
        interpretar_gs1("(01)17891234567893")
    with pytest.raises(ValueError, match="FNC1"):
        interpretar_gs1("0117891234567892" + "10" + "L" * 25)
