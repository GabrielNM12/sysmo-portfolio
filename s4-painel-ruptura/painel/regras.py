"""Regras de alerta: transformam linhas do ERP em alertas priorizados por impacto financeiro."""
from __future__ import annotations

from dataclasses import asdict, dataclass
from decimal import Decimal
from enum import IntEnum


class Severidade(IntEnum):
    BAIXA = 1
    MEDIA = 2
    ALTA = 3


@dataclass(frozen=True)
class Alerta:
    tipo: str
    severidade: Severidade
    loja_id: int
    produto_id: int
    descricao: str
    mensagem: str
    acao: str
    impacto: Decimal                  # R$ em risco ou perdidos (para ordenar o que resolver primeiro)
    detalhe: str = ""                 # lote, por exemplo

    @property
    def chave(self) -> str:
        """Identidade do alerta para não repetir a mesma notificação todo dia."""
        return f"{self.tipo}:{self.loja_id}:{self.produto_id}:{self.detalhe}"

    def como_dict(self) -> dict:
        d = asdict(self)
        d["severidade"] = self.severidade.name
        d["impacto"] = float(self.impacto)
        d["chave"] = self.chave
        return d


def _d(valor) -> Decimal:
    return Decimal(str(valor or 0))


def _br(valor, casas: int = 2) -> str:
    """Número no formato brasileiro: 1.234,56"""
    return f"{float(valor):,.{casas}f}".replace(",", "X").replace(".", ",").replace("X", ".")


def _qtd(valor) -> str:
    v = _d(valor)
    return _br(v, 0) if v == v.to_integral_value() else _br(v, 3)


def _prob(p) -> str:
    return "< 0,001%" if float(p) < 0.00001 else f"{_br(float(p) * 100, 3)}%"


def alertas_ruptura(loja: int, linhas: list[dict]) -> list[Alerta]:
    alertas = []
    for r in linhas:
        perdido = _d(r["venda_perdida_dia"]) * r["dias_sem_venda"]
        if _d(r["estoque_atual"]) <= 0:
            alertas.append(Alerta(
                "RUPTURA", Severidade.ALTA if _d(r["venda_perdida_dia"]) >= 50 else Severidade.MEDIA,
                loja, r["produto_id"], r["descricao"],
                f"Sem estoque e sem venda há {r['dias_sem_venda']} dias (vendia {_br(r['venda_media_dia'])}/dia)",
                "Verificar pedido de compra em aberto / transferir do CD", perdido))
        else:
            alertas.append(Alerta(
                "ESTOQUE_FANTASMA", Severidade.ALTA, loja, r["produto_id"], r["descricao"],
                f"Sistema mostra {_qtd(r['estoque_atual'])} em estoque, mas não vende há {r['dias_sem_venda']} dias "
                f"(chance de ser acaso: {_prob(r['prob_acaso'])})",
                "Conferir gôndola e depósito; se não achar, ajustar inventário", perdido))
    return alertas


def alertas_margem(loja: int, linhas: list[dict], margem_min: Decimal) -> list[Alerta]:
    alertas = []
    for r in linhas:
        preco, custo = _d(r["preco_venda"]), _d(r["custo_medio"])
        ultimo = _d(r["custo_ultima_entrada"])
        prejuizo_mes = max(Decimal(0), (custo - preco)) * _d(r["venda_media_dia"]) * 30
        if _d(r["margem_perc"]) < 0:
            alertas.append(Alerta(
                "MARGEM_NEGATIVA", Severidade.ALTA, loja, r["produto_id"], r["descricao"],
                f"Custo médio R$ {_br(custo)} acima do preço R$ {_br(preco)} (margem {_br(r['margem_perc'], 1)}%)",
                f"Reprecificar: preço mínimo para {_br(margem_min, 0)}% de margem é R$ {_br(custo / (1 - margem_min / 100))}",
                prejuizo_mes.quantize(Decimal("0.01"))))
        elif ultimo > preco:
            alertas.append(Alerta(
                "CUSTO_ACIMA_DO_PRECO", Severidade.ALTA, loja, r["produto_id"], r["descricao"],
                f"Última entrada custou R$ {_br(ultimo)}, acima do preço R$ {_br(preco)}; o custo médio ainda esconde o problema",
                "Reajuste do fornecedor não repassado: revisar preço antes do estoque antigo acabar",
                ((ultimo - preco) * _d(r["venda_media_dia"]) * 30).quantize(Decimal("0.01"))))
        else:
            alertas.append(Alerta(
                "MARGEM_BAIXA", Severidade.MEDIA, loja, r["produto_id"], r["descricao"],
                f"Margem {_br(r['margem_perc'], 1)}% abaixo do mínimo de {_br(margem_min, 0)}%",
                "Avaliar preço ou negociar custo",
                ((margem_min / 100 * preco - (preco - custo)) * _d(r["venda_media_dia"]) * 30).quantize(Decimal("0.01"))))
    return alertas


def alertas_validade(loja: int, linhas: list[dict]) -> list[Alerta]:
    alertas = []
    for r in linhas:
        dias, sobra = r["dias_para_vencer"], _d(r["sobra_prevista"])
        if dias < 0:
            sev, msg, acao = (Severidade.ALTA, f"Lote {r['lote']} VENCIDO há {-dias} dia(s): {_qtd(sobra)} na área de venda",
                              "Retirar da área de venda e registrar quebra")
        elif dias <= 2:
            sev, msg, acao = (Severidade.MEDIA, f"Lote {r['lote']} vence em {dias} dia(s); devem sobrar {_qtd(sobra)} no ritmo atual",
                              "Rebaixar preço hoje ou transferir para loja com mais giro")
        else:
            sev, msg, acao = (Severidade.BAIXA, f"Lote {r['lote']} vence em {dias} dias; devem sobrar {_qtd(sobra)} no ritmo atual",
                              "Planejar ação (degustação, ponta de gôndola, encarte)")
        alertas.append(Alerta("VALIDADE", sev, loja, r["produto_id"], r["descricao"], msg, acao,
                              _d(r["valor_custo"]), detalhe=r["lote"]))
    return alertas


def priorizar(alertas: list[Alerta]) -> list[Alerta]:
    return sorted(alertas, key=lambda a: (-a.severidade, -a.impacto, a.descricao))


def avaliar_loja(repo, loja: int) -> list[Alerta]:
    p = repo.parametros(loja)
    margem_min = Decimal(p["MARGEM_MINIMA_PERC"])
    alertas = alertas_ruptura(loja, repo.ruptura(loja, float(p["RUPTURA_PROB_MAX"])))
    alertas += alertas_margem(loja, repo.margem(loja, margem_min), margem_min)
    alertas += alertas_validade(loja, repo.validade(loja, int(p["VALIDADE_ALERTA_DIAS"])))
    return priorizar(alertas)
