"""Leitura de GS1-128 (antigo EAN/UCC-128): o código das caixas de carne, frios e queijos
vindos da indústria, com GTIN, lote, validade e peso variável embutidos.

Aceita a forma "legível" com parênteses — (01)17891234567895(17)261231(3103)001250 —
e a forma lida pelo scanner, em que campos variáveis terminam com FNC1 (caractere GS, 0x1D).
"""
from __future__ import annotations

import calendar
import re
from dataclasses import dataclass, field
from datetime import date
from decimal import Decimal

from balanca.codigo_balanca import gtin_valido

GS = "\x1d"

# AIs de tamanho fixo (prefixo de 2 dígitos -> tamanho total do AI + dado), da especificação GS1
_FIXOS_POR_PREFIXO = {"00": 20, "01": 16, "02": 16, "03": 16, "04": 18, "11": 8, "12": 8, "13": 8,
                      "15": 8, "16": 8, "17": 8, "18": 8, "19": 8, "20": 4, "31": 10, "32": 10,
                      "33": 10, "34": 10, "35": 10, "36": 10}


@dataclass(frozen=True)
class DefinicaoAI:
    ai: str
    descricao: str
    tamanho_ai: int
    tamanho_max: int
    fixo: bool


def _definicao(ai_inicio: str) -> DefinicaoAI:
    """Descobre o AI no início do texto e o tamanho do dado."""
    if ai_inicio[:2] in ("31", "32", "33", "34", "35", "36"):           # medidas: 4 dígitos de AI, 6 de dado
        return DefinicaoAI(ai_inicio[:4], "medida", 4, 6, True)
    if ai_inicio[:3] in ("390", "391", "392", "393"):                     # valores monetários, 4 dígitos de AI
        return DefinicaoAI(ai_inicio[:4], "valor", 4, 18 if ai_inicio[:3] in ("391", "393") else 15, False)
    if ai_inicio[:2] in _FIXOS_POR_PREFIXO:
        return DefinicaoAI(ai_inicio[:2], "", 2, _FIXOS_POR_PREFIXO[ai_inicio[:2]] - 2, True)
    if ai_inicio[:2] in ("10", "21", "22", "30", "37"):
        return DefinicaoAI(ai_inicio[:2], "", 2, {"10": 20, "21": 20, "22": 20, "30": 8, "37": 8}[ai_inicio[:2]], False)
    if ai_inicio[:3] in ("400", "401", "402", "403", "410", "411", "412", "413", "414", "420", "421", "422"):
        tam = {"400": 30, "401": 30, "402": 17, "403": 30, "420": 20, "421": 12, "422": 3}.get(ai_inicio[:3], 13)
        return DefinicaoAI(ai_inicio[:3], "", 3, tam, ai_inicio[:3] in ("402", "410", "411", "412", "413", "414", "422"))
    raise ValueError(f"Identificador de aplicação (AI) não suportado: {ai_inicio[:4]}")


@dataclass
class LeituraGS1:
    campos: dict[str, str] = field(default_factory=dict)

    @property
    def gtin(self) -> str | None:
        return self.campos.get("01") or self.campos.get("02")

    @property
    def lote(self) -> str | None:
        return self.campos.get("10")

    @property
    def serie(self) -> str | None:
        return self.campos.get("21")

    @property
    def validade(self) -> date | None:
        return _data_gs1(self.campos["17"]) if "17" in self.campos else None

    @property
    def consumir_ate(self) -> date | None:
        return _data_gs1(self.campos["15"]) if "15" in self.campos else None

    @property
    def producao(self) -> date | None:
        return _data_gs1(self.campos["11"]) if "11" in self.campos else None

    @property
    def peso_liquido_kg(self) -> Decimal | None:
        for ai, valor in self.campos.items():
            if ai.startswith("310"):
                return Decimal(int(valor)) / (Decimal(10) ** int(ai[3]))
        return None

    @property
    def preco(self) -> Decimal | None:
        for ai, valor in self.campos.items():
            if ai.startswith("392"):
                return Decimal(int(valor)) / (Decimal(10) ** int(ai[3]))
            if ai.startswith("393"):                  # 3 primeiros dígitos = moeda ISO 4217 (986 = BRL)
                return Decimal(int(valor[3:])) / (Decimal(10) ** int(ai[3]))
        return None

    def vencido(self, hoje: date | None = None) -> bool:
        limite = self.validade or self.consumir_ate
        return bool(limite and limite < (hoje or date.today()))


def _data_gs1(aammdd: str) -> date:
    """Data GS1 AAMMDD; dia 00 significa último dia do mês. Século pela regra da janela de 50 anos."""
    aa, mm, dd = int(aammdd[:2]), int(aammdd[2:4]), int(aammdd[4:6])
    ano = 2000 + aa if aa < 50 else 1900 + aa
    if dd == 0:
        dd = calendar.monthrange(ano, mm)[1]
    return date(ano, mm, dd)


def interpretar_gs1(codigo: str) -> LeituraGS1:
    texto = codigo.strip()
    if texto.startswith("]C1"):          # identificador de simbologia enviado por alguns scanners
        texto = texto[3:]
    leitura = LeituraGS1()

    if texto.startswith("("):
        for ai, valor in re.findall(r"\((\d{2,4})\)([^(]*)", texto):
            leitura.campos[ai] = valor
    else:
        pos = 0
        while pos < len(texto):
            if texto[pos] == GS:
                pos += 1
                continue
            d = _definicao(texto[pos : pos + 4])
            pos += d.tamanho_ai
            if d.fixo:
                valor = texto[pos : pos + d.tamanho_max]
                if len(valor) != d.tamanho_max:
                    raise ValueError(f"AI {d.ai}: esperado {d.tamanho_max} caracteres")
                pos += d.tamanho_max
            else:
                fim = texto.find(GS, pos)
                fim = len(texto) if fim == -1 else fim
                valor = texto[pos:fim]
                if len(valor) > d.tamanho_max:
                    raise ValueError(f"AI {d.ai}: {len(valor)} caracteres (máximo {d.tamanho_max}). Faltou o separador FNC1?")
                pos = fim
            leitura.campos[d.ai] = valor

    if leitura.gtin and not gtin_valido(leitura.gtin):
        raise ValueError(f"GTIN {leitura.gtin} com dígito verificador inválido")
    return leitura


def montar_gs1(campos: dict[str, str], legivel: bool = False) -> str:
    """Gera o conteúdo GS1-128 (para testes e para etiquetas de caixa)."""
    if legivel:
        return "".join(f"({ai}){valor}" for ai, valor in campos.items())
    partes = []
    itens = list(campos.items())
    for i, (ai, valor) in enumerate(itens):
        partes.append(ai + valor)
        if not _definicao(ai.ljust(4, "0")).fixo and i < len(itens) - 1:
            partes.append(GS)
    return "".join(partes)
