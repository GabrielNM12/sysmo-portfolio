from decimal import Decimal

import pytest

from balanca.layouts import obter_layout
from balanca.layouts.toledo_mgv6 import ITENSMGV, quebrar_descritivo
from balanca.modelos import ItemBalanca, TipoVenda


def item(**kw):
    base = dict(codigo=1001, descricao="Alcatra Açougue Próprio", preco=Decimal("49.90"), validade_dias=4,
                setor=2, setor_nome="AÇOUGUE", info_extra="CONSERVAR DE 0 A 4 C")
    base.update(kw)
    return ItemBalanca(**base)


def test_registro_tem_150_posicoes_e_campos_no_lugar():
    res = obter_layout("toledo-mgv6").gerar([item()])
    linha = res.arquivos["ITENSMGV.TXT"][0]
    assert len(linha) == ITENSMGV.tamanho == 150
    assert linha[0:2] == "02"              # departamento
    assert linha[2] == "0"                 # 0 = venda por peso
    assert linha[3:9] == "001001"          # PLU
    assert linha[9:15] == "004990"         # R$ 49,90 em centavos
    assert linha[15:18] == "004"           # validade
    assert linha[18:43].rstrip() == "ALCATRA ACOUGUE PROPRIO"  # sem acento, maiúsculo
    assert linha[68:74] == "001001"        # aponta para o TXINFO do próprio PLU
    assert linha[84] == "1"                # imprime validade


def test_unidade_e_info_extra():
    res = obter_layout("toledo-mgv6").gerar([item(tipo=TipoVenda.UNIDADE, info_extra="")])
    linha = res.arquivos["ITENSMGV.TXT"][0]
    assert linha[2] == "1"
    assert linha[68:74] == "000000"
    assert res.arquivos["TXINFO.TXT"] == []


def test_txinfo_e_depto():
    res = obter_layout("toledo-mgv6").gerar([item()])
    assert len(res.arquivos["TXINFO.TXT"][0]) == 946
    assert res.arquivos["DEPTO.TXT"] == ["02" + "ACOUGUE".ljust(40)]


def test_ida_e_volta():
    layout = obter_layout("toledo-mgv6")
    res = layout.gerar([item(), item(codigo=1002, descricao="Picanha", preco=Decimal("89.99"))])
    lidos = layout.ler_itens(res.arquivos["ITENSMGV.TXT"])
    assert [(i["codigo"], i["preco"], i["descricao"]) for i in lidos] == [
        (1001, Decimal("49.90"), "ALCATRA ACOUGUE PROPRIO"), (1002, Decimal("89.99"), "PICANHA")]


def test_rejeita_preco_que_nao_cabe_zerado_e_duplicado():
    res = obter_layout("toledo-mgv6").gerar([
        item(codigo=1, preco=Decimal("10000.00")),
        item(codigo=2, preco=Decimal("0")),
        item(codigo=3), item(codigo=3),
    ])
    motivos = {i.codigo: m for i, m in res.rejeitados}
    assert "não cabe" in motivos[1]
    assert "zerado" in motivos[2]
    assert "duplicado" in motivos[3]
    assert len(res.arquivos["ITENSMGV.TXT"]) == 1


def test_tara_vira_tabela():
    res = obter_layout("toledo-mgv6").gerar([item(tara_g=15), item(codigo=1002, tara_g=15), item(codigo=1003, tara_g=8)])
    assert res.arquivos["TARA.TXT"] == ["0001000015" + "TARA 15G".ljust(20), "0002000008" + "TARA 8G".ljust(20)]
    assert res.arquivos["ITENSMGV.TXT"][2][118:122] == "0002"


@pytest.mark.parametrize("descricao, esperado", [
    ("Banana", "BANANA"),
    ("Linguiça Toscana Frigorífico Oeste Especial", "LINGUICA TOSCANA         FRIGORIFICO OESTE ESPECIA"),
])
def test_descritivo_quebra_em_palavra(descricao, esperado):
    assert quebrar_descritivo(descricao) == esperado


def test_plu_invalido():
    with pytest.raises(ValueError):
        item(codigo=1_000_000)


def test_filizola_tamanho_e_campos():
    res = obter_layout("filizola").gerar([item()])
    linha = res.arquivos["CADTXT.TXT"][0]
    assert len(linha) == 169
    assert linha[:6] == "001001" and linha[6] == "P"
    assert linha[29:36] == "0004990"
    assert res.arquivos["SETORTXT.TXT"][0].startswith("ACOUGUE     001001")


def test_gravacao_crlf_ascii(tmp_path):
    layout = obter_layout("toledo-mgv6")
    layout.gravar(layout.gerar([item()]), tmp_path)
    bruto = (tmp_path / "ITENSMGV.TXT").read_bytes()
    assert bruto.endswith(b"\r\n") and bruto.isascii()
