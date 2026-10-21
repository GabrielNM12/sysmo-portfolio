"""Layout de carga Filizola (CADTXT.TXT + SETORTXT.TXT), conforme o Projeto ACBr (modFilizola)."""
from __future__ import annotations

from typing import Iterable

from balanca.layouts.base import Campo, CampoInvalido, LayoutBalanca, RegistroFixo, registrar
from balanca.modelos import ItemBalanca, ResultadoCarga, TipoVenda

TIPO_FILIZOLA = {TipoVenda.PESO: "P", TipoVenda.UNIDADE: "U"}

CADTXT = RegistroFixo((
    Campo("codigo", 6),
    Campo("tipo", 1, "A"),
    Campo("descricao", 22, "A"),
    Campo("preco", 7, decimais=2),
    Campo("validade_dias", 3),
    Campo("reservado", 126, "A"),
    Campo("tara_kg", 4, decimais=3),
))

SETORTXT = RegistroFixo((
    Campo("setor", 12, "A"),
    Campo("codigo", 6),
    Campo("sequencia", 4),
    Campo("tecla", 3),
))


@registrar
class Filizola(LayoutBalanca):
    nome = "filizola"
    descricao = "Filizola (Smart/Platina): CADTXT.TXT e SETORTXT.TXT"

    def gerar(self, itens: Iterable[ItemBalanca]) -> ResultadoCarga:
        res = ResultadoCarga(arquivos={"CADTXT.TXT": [], "SETORTXT.TXT": []})
        for seq, item in enumerate(sorted(itens, key=lambda i: i.codigo), start=1):
            if item.preco == 0:
                res.rejeitados.append((item, "preço zerado: a balança venderia de graça"))
                continue
            if len(item.descricao) > 22:
                res.avisos.append(f"PLU {item.codigo}: Filizola aceita só 22 caracteres de descrição")
            try:
                res.arquivos["CADTXT.TXT"].append(CADTXT.montar({
                    "codigo": item.codigo,
                    "tipo": TIPO_FILIZOLA[item.tipo],
                    "descricao": item.descricao,
                    "preco": item.preco,
                    "validade_dias": item.validade_dias,
                    "tara_kg": item.tara_g / 1000,
                }))
            except CampoInvalido as erro:
                res.rejeitados.append((item, str(erro)))
                continue
            if item.setor_nome:
                res.arquivos["SETORTXT.TXT"].append(SETORTXT.montar({
                    "setor": item.setor_nome, "codigo": item.codigo, "sequencia": seq, "tecla": 0}))
        return res

    def ler_itens(self, linhas: Iterable[str]) -> list[dict]:
        return [CADTXT.ler(l) for l in linhas if l.strip()]
