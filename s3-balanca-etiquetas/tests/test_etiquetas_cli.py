from decimal import Decimal

from balanca.cli import main
from balanca.etiquetas import EtiquetaGondola, gerar_etiqueta_balanca, gerar_etiquetas_gondola, preco_por_medida
from balanca.fonte_erp import exportar_csv
from balanca.modelos import ItemBalanca


def test_preco_por_medida():
    assert preco_por_medida("CAFÉ EXTRA FORTE 500G", Decimal("18.90")) == (Decimal("37.80"), "kg")
    assert preco_por_medida("REFRIGERANTE COLA 1,5L", Decimal("7.49")) == (Decimal("4.99"), "L")
    assert preco_por_medida("SABONETE 3UN", Decimal("5")) is None


def test_pdfs(tmp_path):
    etqs = [EtiquetaGondola("ARROZ BRANCO TIPO 1 BOM GRÃO 5KG", Decimal("27.90"), ean="7890000000072",
                            codigo_interno=10, preco_de=Decimal("31.90")) for _ in range(30)]
    pdf = gerar_etiquetas_gondola(etqs, tmp_path / "g.pdf")
    assert pdf.read_bytes().startswith(b"%PDF") and pdf.read_bytes().count(b"/Type /Page\n") >= 1
    pdf2, ean = gerar_etiqueta_balanca(codigo=1001, descricao="Picanha", preco_kg=Decimal("89.90"),
                                       peso_kg=Decimal("1.110"), validade_dias=4, destino=tmp_path / "b.pdf")
    assert ean == "2100100099794"   # 89,90 × 1,110 kg = 99,79 and pdf2.stat().st_size > 1000


def _csv(tmp_path, preco):
    itens = [ItemBalanca(codigo=1001, descricao="Alcatra", preco=Decimal(preco), validade_dias=4, produto_id=1, secao_id=401),
             ItemBalanca(codigo=1002, descricao="Banana", preco=Decimal("4.99"), validade_dias=4, produto_id=2, secao_id=301)]
    caminho = tmp_path / f"itens_{preco}.csv"
    exportar_csv(itens, caminho)
    return caminho


def test_cli_carga_com_diferencas(tmp_path, capsys):
    assert main(["carga", "--csv", str(_csv(tmp_path, "49.90")), "--saida", str(tmp_path / "ontem")]) == 0
    assert main(["carga", "--csv", str(_csv(tmp_path, "52.90")), "--saida", str(tmp_path / "hoje"),
                 "--anterior", str(tmp_path / "ontem")]) == 0
    saida = capsys.readouterr().out
    assert "2 itens gravados" in saida
    assert "PLU   1001 PREÇO ↑" in saida and "49.90 -> 52.90" in saida
    assert "1002" not in saida.split("Alterações")[1]


def test_cli_ler(capsys):
    assert main(["ler", "2100100012991"]) == 0
    assert "PLU 1001" in capsys.readouterr().out
    assert main(["ler", "(01)17891234567892(17)261031(3103)012345"]) == 0
    assert "12.345 kg" in capsys.readouterr().out
    assert main(["ler", "2100100012994"]) == 0       # DV errado: não é de balança, cai como GTIN inválido
    assert "INVÁLIDO" in capsys.readouterr().out
