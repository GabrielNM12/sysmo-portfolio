"""Etiquetas de gôndola (A4, folha de 24) e simulação da etiqueta impressa pela balança."""
from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import date, timedelta
from decimal import ROUND_HALF_UP, Decimal
from pathlib import Path

from reportlab.graphics import renderPDF
from reportlab.graphics.barcode import createBarcodeDrawing
from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.units import mm
from reportlab.pdfgen import canvas

from balanca.codigo_balanca import ConfigCodigoBalanca, gerar_codigo, gtin_valido

AZUL = colors.HexColor("#14365c")
VERMELHO = colors.HexColor("#c62828")
AMARELO = colors.HexColor("#ffd54f")
CINZA = colors.HexColor("#5f6b7a")


@dataclass(frozen=True)
class EtiquetaGondola:
    descricao: str
    preco: Decimal
    unidade: str = "UN"                      # UN, KG, LT
    ean: str | None = None
    codigo_interno: int | None = None
    preco_de: Decimal | None = None          # preço normal quando em oferta
    oferta_ate: date | None = None


_TAMANHO = re.compile(r"(\d+(?:[.,]\d+)?)\s*(KG|G|ML|L)\b", re.IGNORECASE)


def preco_por_medida(descricao: str, preco: Decimal) -> tuple[Decimal, str] | None:
    """Preço por kg ou litro a partir do conteúdo na descrição (ex.: 'CAFÉ 500G' a 18,90 -> 37,80/kg)."""
    achado = None
    for achado in _TAMANHO.finditer(descricao):
        pass
    if not achado:
        return None
    qtd = Decimal(achado.group(1).replace(",", "."))
    unidade = achado.group(2).upper()
    if unidade in ("G", "ML"):
        qtd = qtd / 1000
    if qtd <= 0:
        return None
    base = "kg" if unidade in ("KG", "G") else "L"
    return (preco / qtd).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP), base


def _moeda(valor: Decimal) -> str:
    inteiro, centavos = f"{valor:.2f}".split(".")
    inteiro = f"{int(inteiro):,}".replace(",", ".")
    return f"{inteiro},{centavos}"


def _quebrar(texto: str, largura_pt: float, linhas: int, fonte: str = "Helvetica-Bold", tamanho: float = 7.2) -> list[str]:
    """Quebra o texto pela largura real impressa (em pontos), não por número de caracteres."""
    from reportlab.pdfbase.pdfmetrics import stringWidth

    def cabe(t: str) -> bool:
        return stringWidth(t, fonte, tamanho) <= largura_pt

    saida, atual = [], ""
    for p in texto.split():
        tentativa = f"{atual} {p}".strip()
        if cabe(tentativa) or not atual:
            atual = tentativa
        else:
            saida.append(atual)
            atual = p
    if atual:
        saida.append(atual)
    if len(saida) > linhas:
        saida = saida[:linhas]
        ultima = saida[-1]
        while ultima and not cabe(ultima + "…"):
            ultima = ultima[:-1]
        saida[-1] = ultima.rstrip() + "…"
    return [l if cabe(l) else l[: max(1, int(len(l) * largura_pt / stringWidth(l, fonte, tamanho)))] for l in saida]


def _desenhar_barras(c: canvas.Canvas, ean: str, x: float, y: float, largura: float, altura: float) -> None:
    tipo = "EAN13" if len(ean) == 13 else "EAN8"
    d = createBarcodeDrawing(tipo, value=ean[:-1], barHeight=altura, humanReadable=True, fontSize=6)
    escala = largura / d.width
    d.scale(escala, 1)
    d.width *= escala
    renderPDF.draw(d, c, x, y)


def gerar_etiquetas_gondola(etiquetas: list[EtiquetaGondola], destino: str | Path, loja: str = "") -> Path:
    """Folha A4 com 3 × 8 etiquetas de 64 × 33,9 mm (padrão de etiqueta adesiva A4 de 24)."""
    destino = Path(destino)
    c = canvas.Canvas(str(destino), pagesize=A4)
    c.setTitle(f"Etiquetas de gôndola {loja}".strip())
    larg, alt = 64 * mm, 33.9 * mm
    margem_x = (A4[0] - 3 * larg) / 2
    margem_y = (A4[1] - 8 * alt) / 2
    hoje = date.today().strftime("%d/%m/%Y")

    for i, et in enumerate(etiquetas):
        pos = i % 24
        if i and pos == 0:
            c.showPage()
        col, lin = pos % 3, pos // 3
        x = margem_x + col * larg
        y = A4[1] - margem_y - (lin + 1) * alt
        em_oferta = et.preco_de is not None and et.preco_de > et.preco

        c.setStrokeColor(colors.HexColor("#c9d1db"))
        c.setLineWidth(0.4)
        c.rect(x, y, larg, alt)
        if em_oferta:
            c.setFillColor(AMARELO)
            c.rect(x, y + alt - 5 * mm, larg, 5 * mm, fill=1, stroke=0)
            c.setFillColor(VERMELHO)
            c.setFont("Helvetica-Bold", 8)
            texto = "OFERTA" + (f" ATÉ {et.oferta_ate:%d/%m}" if et.oferta_ate else "")
            c.drawString(x + 2 * mm, y + alt - 3.6 * mm, texto)
            topo = y + alt - 8.2 * mm
        else:
            topo = y + alt - 4 * mm

        c.setFillColor(AZUL)
        c.setFont("Helvetica-Bold", 7.2)
        for n, linha in enumerate(_quebrar(et.descricao, larg - 4 * mm, 2)):
            c.drawString(x + 2 * mm, topo - n * 3 * mm, linha)

        # preço
        cor_preco = VERMELHO if em_oferta else AZUL
        c.setFillColor(cor_preco)
        c.setFont("Helvetica-Bold", 7)
        c.drawString(x + 30 * mm, y + 13.5 * mm, "R$")
        c.setFont("Helvetica-Bold", 20)
        c.drawRightString(x + larg - 9 * mm, y + 11.5 * mm, _moeda(et.preco))
        c.setFont("Helvetica-Bold", 7)
        c.drawString(x + larg - 8 * mm, y + 11.5 * mm, "/" + et.unidade.lower())
        if em_oferta:
            c.setFillColor(CINZA)
            c.setFont("Helvetica", 6.5)
            de = f"de R$ {_moeda(et.preco_de)}"
            c.drawString(x + 30 * mm, y + 19.5 * mm, de)
            w = c.stringWidth(de, "Helvetica", 6.5)
            c.setStrokeColor(CINZA)
            c.line(x + 30 * mm, y + 20.6 * mm, x + 30 * mm + w, y + 20.6 * mm)

        unitario = preco_por_medida(et.descricao, et.preco) if et.unidade == "UN" else None
        c.setFillColor(CINZA)
        c.setFont("Helvetica", 6)
        if unitario:
            c.drawString(x + 30 * mm, y + 7.6 * mm, f"Preço por {unitario[1]}: R$ {_moeda(unitario[0])}")
        rodape = f"Cód. {et.codigo_interno}" if et.codigo_interno else ""
        c.drawString(x + 30 * mm, y + 2.2 * mm, f"{rodape}   {hoje}".strip())

        if et.ean and gtin_valido(et.ean):
            _desenhar_barras(c, et.ean, x + 1.5 * mm, y + 2.5 * mm, 27 * mm, 11 * mm)
        elif et.codigo_interno:
            c.setFont("Helvetica-Bold", 14)
            c.setFillColor(AZUL)
            c.drawString(x + 3 * mm, y + 8 * mm, f"PLU {et.codigo_interno}")
    c.save()
    return destino


def gerar_etiqueta_balanca(*, codigo: int, descricao: str, preco_kg: Decimal, peso_kg: Decimal,
                           validade_dias: int, destino: str | Path, info_extra: str = "",
                           loja: str = "", config: ConfigCodigoBalanca = ConfigCodigoBalanca(),
                           embalado_em: date | None = None) -> tuple[Path, str]:
    """Simula a etiqueta 60 × 40 mm que a balança imprime. Devolve o PDF e o EAN-13 gerado."""
    destino = Path(destino)
    total = (preco_kg * peso_kg).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)
    ean = gerar_codigo(codigo, preco_total=total, peso_kg=peso_kg, config=config)
    embalado = embalado_em or date.today()

    larg, alt = 60 * mm, 40 * mm
    c = canvas.Canvas(str(destino), pagesize=(larg, alt))
    c.setTitle(f"Etiqueta de balança PLU {codigo}")
    c.setFont("Helvetica-Bold", 6.5)
    c.drawString(2 * mm, alt - 4 * mm, loja.upper()[:40])
    c.setFont("Helvetica-Bold", 8)
    for n, linha in enumerate(_quebrar(descricao.upper(), larg - 4 * mm, 2, tamanho=8)):
        c.drawString(2 * mm, alt - 7.8 * mm - n * 3.2 * mm, linha)

    c.setFont("Helvetica", 5.5)
    c.drawString(2 * mm, alt - 15 * mm, "PESO LÍQ. (kg)")
    c.drawString(22 * mm, alt - 15 * mm, "R$/kg")
    c.drawString(40 * mm, alt - 15 * mm, "TOTAL R$")
    c.setFont("Helvetica-Bold", 9)
    c.drawString(2 * mm, alt - 18.6 * mm, f"{peso_kg:.3f}".replace(".", ","))
    c.drawString(22 * mm, alt - 18.6 * mm, _moeda(preco_kg))
    c.setFont("Helvetica-Bold", 11)
    c.drawString(40 * mm, alt - 19 * mm, _moeda(total))

    c.setFont("Helvetica", 5.5)
    c.drawString(2 * mm, alt - 22.5 * mm, f"EMB.: {embalado:%d/%m/%Y}")
    if validade_dias:
        c.drawString(22 * mm, alt - 22.5 * mm, f"VAL.: {embalado + timedelta(days=validade_dias):%d/%m/%Y}")
    if info_extra:
        c.setFont("Helvetica", 4.5)
        c.drawString(2 * mm, alt - 25 * mm, info_extra[:70])
    _desenhar_barras(c, ean, 6 * mm, 1.2 * mm, 48 * mm, 9 * mm)
    c.save()
    return destino, ean
