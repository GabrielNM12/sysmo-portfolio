"""Infraestrutura de layouts de largura fixa (o formato de quase todo arquivo de balança)."""
from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass
from decimal import ROUND_HALF_UP, Decimal
from typing import Iterable

from balanca.modelos import ItemBalanca, ResultadoCarga, texto_balanca


class CampoInvalido(ValueError):
    pass


@dataclass(frozen=True)
class Campo:
    nome: str
    tamanho: int
    tipo: str = "N"        # N = numérico (zeros à esquerda), A = alfanumérico (espaços à direita)
    decimais: int = 0      # numérico com casas implícitas: 12,99 com 2 casas -> "001299"

    def formatar(self, valor) -> str:
        if self.tipo == "A":
            return texto_balanca(str(valor or ""))[: self.tamanho].ljust(self.tamanho)
        numero = Decimal(str(valor or 0)) * (Decimal(10) ** self.decimais)
        inteiro = int(numero.quantize(Decimal(1), rounding=ROUND_HALF_UP))
        if inteiro < 0:
            raise CampoInvalido(f"{self.nome}: valor negativo ({valor})")
        texto = str(inteiro)
        if len(texto) > self.tamanho:
            raise CampoInvalido(f"{self.nome}: {valor} não cabe em {self.tamanho} posições")
        return texto.zfill(self.tamanho)

    def interpretar(self, texto: str):
        if self.tipo == "A":
            return texto.rstrip()
        numero = Decimal(int(texto or "0"))
        return numero / (Decimal(10) ** self.decimais) if self.decimais else int(numero)


@dataclass(frozen=True)
class RegistroFixo:
    campos: tuple[Campo, ...]

    @property
    def tamanho(self) -> int:
        return sum(c.tamanho for c in self.campos)

    def montar(self, valores: dict) -> str:
        desconhecidos = set(valores) - {c.nome for c in self.campos}
        if desconhecidos:
            raise KeyError(f"Campos inexistentes no layout: {sorted(desconhecidos)}")
        return "".join(c.formatar(valores.get(c.nome)) for c in self.campos)

    def ler(self, linha: str) -> dict:
        linha = linha.rstrip("\r\n").ljust(self.tamanho)
        valores, pos = {}, 0
        for c in self.campos:
            valores[c.nome] = c.interpretar(linha[pos : pos + c.tamanho])
            pos += c.tamanho
        return valores

    def documentar(self) -> list[tuple[int, int, str, str]]:
        """(posição inicial, tamanho, nome, tipo) — para gerar a documentação do layout."""
        linhas, pos = [], 1
        for c in self.campos:
            tipo = c.tipo + (f" ({c.decimais} dec.)" if c.decimais else "")
            linhas.append((pos, c.tamanho, c.nome, tipo))
            pos += c.tamanho
        return linhas


class LayoutBalanca(ABC):
    nome: str = ""
    descricao: str = ""
    quebra_linha: str = "\r\n"          # equipamentos e softwares de carga rodam em Windows
    codificacao: str = "ascii"

    @abstractmethod
    def gerar(self, itens: Iterable[ItemBalanca]) -> ResultadoCarga: ...

    @abstractmethod
    def ler_itens(self, linhas: Iterable[str]) -> list[dict]: ...

    def gravar(self, resultado: ResultadoCarga, diretorio) -> list:
        from pathlib import Path

        destino = Path(diretorio)
        destino.mkdir(parents=True, exist_ok=True)
        gravados = []
        for nome, linhas in resultado.arquivos.items():
            caminho = destino / nome
            conteudo = self.quebra_linha.join(linhas) + (self.quebra_linha if linhas else "")
            caminho.write_bytes(conteudo.encode(self.codificacao))
            gravados.append(caminho)
        return gravados


_REGISTRO: dict[str, type[LayoutBalanca]] = {}


def registrar(classe: type[LayoutBalanca]) -> type[LayoutBalanca]:
    _REGISTRO[classe.nome] = classe
    return classe


def layouts_disponiveis() -> dict[str, str]:
    return {nome: cls.descricao for nome, cls in sorted(_REGISTRO.items())}


def obter_layout(nome: str) -> LayoutBalanca:
    try:
        return _REGISTRO[nome]()
    except KeyError:
        raise ValueError(f"Layout '{nome}' desconhecido. Disponíveis: {', '.join(_REGISTRO)}") from None
