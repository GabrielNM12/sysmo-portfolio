"""EAN-13 de uso interno gerado pela balança (prefixo 2): código do item + preço ou peso embutido.

A estrutura não é padronizada: cada loja configura balança E PDV com o mesmo formato
(no S1 de laboratório, parâmetros BALANCA_PREFIXO, BALANCA_TAM_CODIGO e BALANCA_TIPO_VALOR).
Formatos comuns, todos com 13 dígitos:

    2 CCCC 0 VVVVVV D    código de 4 dígitos, 1 zero de preenchimento, valor de 6
    2 CCCCC VVVVVV D     código de 5 dígitos, valor de 6
    2 CCCCCC VVVVV D     código de 6 dígitos, valor de 5

O valor é o PREÇO TOTAL em centavos (mais comum no varejo alimentar) ou o PESO em gramas.
"""
from __future__ import annotations

from dataclasses import dataclass
from decimal import ROUND_HALF_UP, Decimal


def digito_ean(dados: str) -> int:
    """Dígito verificador GTIN (módulo 10, pesos 3 e 1 a partir da direita)."""
    if not dados.isdigit():
        raise ValueError(f"GTIN deve ter só dígitos: {dados!r}")
    soma = sum(int(d) * (3 if i % 2 == 0 else 1) for i, d in enumerate(reversed(dados)))
    return (10 - soma % 10) % 10


def gtin_valido(codigo: str) -> bool:
    return codigo.isdigit() and len(codigo) in (8, 12, 13, 14) and digito_ean(codigo[:-1]) == int(codigo[-1])


@dataclass(frozen=True)
class ConfigCodigoBalanca:
    prefixo: str = "2"
    digitos_codigo: int = 4
    tipo_valor: str = "PRECO"     # PRECO (centavos) | PESO (gramas)
    digitos_valor: int = 6

    def __post_init__(self) -> None:
        if self.tipo_valor not in ("PRECO", "PESO"):
            raise ValueError("tipo_valor deve ser PRECO ou PESO")
        if len(self.prefixo) + self.digitos_codigo + self.digitos_valor > 12:
            raise ValueError("prefixo + código + valor não cabem em 12 dígitos")

    @property
    def preenchimento(self) -> int:
        return 12 - len(self.prefixo) - self.digitos_codigo - self.digitos_valor

    @classmethod
    def de_parametros(cls, parametros: dict[str, str]) -> "ConfigCodigoBalanca":
        """Monta a configuração a partir dos parâmetros da empresa no ERP."""
        return cls(
            prefixo=parametros.get("BALANCA_PREFIXO", "2"),
            digitos_codigo=int(parametros.get("BALANCA_TAM_CODIGO", 4)),
            tipo_valor="PESO" if parametros.get("BALANCA_TIPO_VALOR", "P") == "W" else "PRECO",
        )


@dataclass(frozen=True)
class LeituraBalanca:
    codigo: int
    preco_total: Decimal | None = None
    peso_kg: Decimal | None = None

    def quantidade_para(self, preco_kg: Decimal) -> Decimal:
        """Quantidade (kg) que o PDV lança no cupom para um item com este preço por kg."""
        if self.peso_kg is not None:
            return self.peso_kg
        if not preco_kg:
            raise ValueError("Preço por kg zerado: não dá para converter valor em peso")
        return (self.preco_total / preco_kg).quantize(Decimal("0.001"), rounding=ROUND_HALF_UP)

    def total_para(self, preco_kg: Decimal) -> Decimal:
        if self.preco_total is not None:
            return self.preco_total
        return (self.peso_kg * preco_kg).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)


def gerar_codigo(codigo: int, *, preco_total: Decimal | None = None, peso_kg: Decimal | None = None,
                 config: ConfigCodigoBalanca = ConfigCodigoBalanca()) -> str:
    if config.tipo_valor == "PRECO":
        if preco_total is None:
            raise ValueError("Configuração por PREÇO exige preco_total")
        valor = int((Decimal(preco_total) * 100).quantize(Decimal(1), rounding=ROUND_HALF_UP))
    else:
        if peso_kg is None:
            raise ValueError("Configuração por PESO exige peso_kg")
        valor = int((Decimal(peso_kg) * 1000).quantize(Decimal(1), rounding=ROUND_HALF_UP))
    if len(str(codigo)) > config.digitos_codigo:
        raise ValueError(f"Código {codigo} não cabe em {config.digitos_codigo} dígitos")
    if len(str(valor)) > config.digitos_valor:
        raise ValueError(f"Valor {valor} não cabe em {config.digitos_valor} dígitos")
    dados = (config.prefixo + str(codigo).zfill(config.digitos_codigo) + "0" * config.preenchimento
             + str(valor).zfill(config.digitos_valor))
    return dados + str(digito_ean(dados))


def eh_codigo_balanca(ean: str, config: ConfigCodigoBalanca = ConfigCodigoBalanca()) -> bool:
    return len(ean) == 13 and ean.startswith(config.prefixo) and gtin_valido(ean)


def interpretar_codigo(ean: str, config: ConfigCodigoBalanca = ConfigCodigoBalanca()) -> LeituraBalanca:
    ean = ean.strip()
    if len(ean) != 13 or not ean.isdigit():
        raise ValueError("Código de balança deve ter 13 dígitos")
    if not ean.startswith(config.prefixo):
        raise ValueError(f"Prefixo {ean[:len(config.prefixo)]} não é de balança (esperado {config.prefixo})")
    if not gtin_valido(ean):
        raise ValueError("Dígito verificador inválido (etiqueta danificada ou leitura incorreta)")
    ini = len(config.prefixo)
    codigo = int(ean[ini : ini + config.digitos_codigo])
    ini_valor = 12 - config.digitos_valor
    valor = int(ean[ini_valor:12])
    if config.tipo_valor == "PRECO":
        return LeituraBalanca(codigo=codigo, preco_total=Decimal(valor) / 100)
    return LeituraBalanca(codigo=codigo, peso_kg=Decimal(valor) / 1000)
