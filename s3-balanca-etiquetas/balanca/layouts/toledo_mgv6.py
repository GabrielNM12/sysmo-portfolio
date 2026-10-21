"""Layout de carga Toledo MGV6 (ITENSMGV.TXT + arquivos auxiliares).

Posições conferidas com a implementação aberta do Projeto ACBr (ACBrCargaBal, modelo
modToledoMGV6). Sempre valide com o manual do MGV na versão instalada no cliente:
campos novos podem ser acrescentados ao fim do registro.
"""
from __future__ import annotations

from typing import Iterable

from balanca.layouts.base import Campo, CampoInvalido, LayoutBalanca, RegistroFixo, registrar
from balanca.modelos import ItemBalanca, ResultadoCarga, TipoVenda, texto_balanca

TIPO_TOLEDO = {TipoVenda.PESO: 0, TipoVenda.UNIDADE: 1}
TIPO_TOLEDO_INV = {v: k for k, v in TIPO_TOLEDO.items()}

ITENSMGV = RegistroFixo((
    Campo("departamento", 2),
    Campo("tipo_produto", 1),             # 0 = peso, 1 = unidade, 2 = EAN-13 peso, 5 = EAN-13 unidade
    Campo("codigo", 6),                   # PLU
    Campo("preco", 6, decimais=2),        # R$ 9.999,99 no máximo
    Campo("validade_dias", 3),
    Campo("descritivo", 50, "A"),         # 2 linhas de 25 caracteres
    Campo("cod_info_extra", 6),           # chave do TXINFO.TXT (ingredientes, conservação)
    Campo("cod_imagem", 4),
    Campo("cod_info_nutricional", 6),
    Campo("imprime_validade", 1),
    Campo("imprime_embalagem", 1),
    Campo("cod_fornecedor", 4),
    Campo("lote", 12),
    Campo("cod_ean_especial", 11),
    Campo("versao_preco", 1),
    Campo("cod_som", 4),
    Campo("cod_tara", 4),
    Campo("cod_fracionador", 4),
    Campo("cod_campo_extra1", 4),
    Campo("cod_campo_extra2", 4),
    Campo("cod_conservacao", 4),
    Campo("ean13_fornecedor", 12),
))

TXINFO = RegistroFixo((
    Campo("codigo", 6),
    Campo("observacao", 100, "A"),
    Campo("texto", 840, "A"),
))

DEPTO = RegistroFixo((
    Campo("codigo", 2),
    Campo("descricao", 40, "A"),
))

TARA = RegistroFixo((
    Campo("codigo", 4),
    Campo("valor_kg", 6, decimais=3),
    Campo("descricao", 20, "A"),
))


def quebrar_descritivo(descricao: str, largura: int = 25) -> str:
    """Distribui a descrição em 2 linhas de 25 sem cortar palavra no meio quando possível."""
    texto = texto_balanca(descricao)
    if len(texto) <= largura:
        return texto
    corte = texto.rfind(" ", 0, largura + 1)
    if corte <= 0:
        corte = largura
    linha1, linha2 = texto[:corte].rstrip(), texto[corte:].lstrip()
    return linha1.ljust(largura) + linha2[:largura]


@registrar
class ToledoMGV6(LayoutBalanca):
    nome = "toledo-mgv6"
    descricao = "Toledo MGV6 (Prix 4/5/6): ITENSMGV.TXT, TXINFO.TXT, TARA.TXT, DEPTO.TXT"

    def gerar(self, itens: Iterable[ItemBalanca]) -> ResultadoCarga:
        res = ResultadoCarga(arquivos={"ITENSMGV.TXT": [], "TXINFO.TXT": [], "TARA.TXT": [], "DEPTO.TXT": []})
        taras: dict[int, int] = {}
        setores: dict[int, str] = {}
        vistos: set[int] = set()

        for item in sorted(itens, key=lambda i: i.codigo):
            if item.codigo in vistos:
                res.rejeitados.append((item, "PLU duplicado na carga"))
                continue
            if item.preco == 0:
                res.rejeitados.append((item, "preço zerado: a balança venderia de graça"))
                continue

            cod_tara = 0
            if item.tara_g:
                cod_tara = taras.setdefault(item.tara_g, len(taras) + 1)
            descritivo = quebrar_descritivo(item.descricao)
            if len(texto_balanca(item.descricao)) > 50:
                res.avisos.append(f"PLU {item.codigo}: descrição cortada em 50 caracteres")

            try:
                linha = ITENSMGV.montar({
                    "departamento": item.setor,
                    "tipo_produto": TIPO_TOLEDO[item.tipo],
                    "codigo": item.codigo,
                    "preco": item.preco,
                    "validade_dias": item.validade_dias,
                    "descritivo": descritivo,
                    "cod_info_extra": item.codigo if item.info_extra else 0,
                    "imprime_validade": 1 if item.validade_dias else 0,
                    "imprime_embalagem": 1,
                    "cod_tara": cod_tara,
                })
            except CampoInvalido as erro:
                res.rejeitados.append((item, str(erro)))
                continue

            vistos.add(item.codigo)
            res.arquivos["ITENSMGV.TXT"].append(linha)
            if item.info_extra:
                res.arquivos["TXINFO.TXT"].append(
                    TXINFO.montar({"codigo": item.codigo, "observacao": "", "texto": item.info_extra}))
            if item.setor_nome:
                setores.setdefault(item.setor, item.setor_nome)

        for gramas, codigo in sorted(taras.items(), key=lambda t: t[1]):
            res.arquivos["TARA.TXT"].append(
                TARA.montar({"codigo": codigo, "valor_kg": gramas / 1000, "descricao": f"TARA {gramas}G"}))
        for codigo, nome in sorted(setores.items()):
            res.arquivos["DEPTO.TXT"].append(DEPTO.montar({"codigo": codigo, "descricao": nome}))
        return res

    def ler_itens(self, linhas: Iterable[str]) -> list[dict]:
        itens = []
        for linha in linhas:
            if not linha.strip():
                continue
            reg = ITENSMGV.ler(linha)
            reg["tipo"] = TIPO_TOLEDO_INV.get(reg["tipo_produto"])
            reg["descricao"] = (reg["descritivo"][:25].rstrip() + " " + reg["descritivo"][25:].strip()).strip()
            itens.append(reg)
        return itens
