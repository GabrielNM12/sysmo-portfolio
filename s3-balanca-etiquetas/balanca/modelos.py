"""Modelo de item de balança, independente de fabricante."""
from __future__ import annotations

import unicodedata
from dataclasses import dataclass, field
from decimal import Decimal
from enum import Enum


class TipoVenda(str, Enum):
    PESO = "PESO"          # preço por kg, a balança pesa e calcula o total
    UNIDADE = "UNIDADE"    # preço por unidade (ex.: bandeja de pão de queijo)


@dataclass(frozen=True)
class ItemBalanca:
    codigo: int                       # PLU: código do item na balança (até 6 dígitos)
    descricao: str
    preco: Decimal                    # R$/kg (PESO) ou R$/un (UNIDADE)
    tipo: TipoVenda = TipoVenda.PESO
    validade_dias: int = 0            # 0 = não imprime validade
    setor: int = 1                    # departamento da balança (açougue, padaria...)
    setor_nome: str = ""
    info_extra: str = ""              # ingredientes, modo de conservação (TXINFO)
    tara_g: int = 0                   # tara da embalagem em gramas
    produto_id: int | None = None     # id no ERP (rastreabilidade)
    secao_id: int | None = None       # seção do mercadológico no ERP

    def __post_init__(self) -> None:
        if not 0 < self.codigo <= 999_999:
            raise ValueError(f"PLU {self.codigo} fora da faixa 1..999999")
        if self.preco < 0:
            raise ValueError(f"Preço negativo no PLU {self.codigo}")
        if not 0 <= self.validade_dias <= 999:
            raise ValueError(f"Validade {self.validade_dias} fora da faixa 0..999 no PLU {self.codigo}")


@dataclass
class ResultadoCarga:
    arquivos: dict[str, list[str]] = field(default_factory=dict)
    avisos: list[str] = field(default_factory=list)
    rejeitados: list[tuple[ItemBalanca, str]] = field(default_factory=list)

    @property
    def total_itens(self) -> int:
        return sum(len(v) for k, v in self.arquivos.items() if k.upper().startswith(("ITENS", "CADTXT")))


def texto_balanca(texto: str) -> str:
    """Balanças imprimem em ASCII: remove acentos, força maiúsculas e tira caracteres de controle."""
    sem_acento = unicodedata.normalize("NFKD", texto).encode("ascii", "ignore").decode("ascii")
    return "".join(c for c in sem_acento.upper() if 32 <= ord(c) < 127)
