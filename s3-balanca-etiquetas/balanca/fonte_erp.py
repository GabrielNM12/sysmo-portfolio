"""Leitura dos dados no ERP de laboratório (S1) ou em CSV exportado de qualquer ERP."""
from __future__ import annotations

import csv
import os
from decimal import Decimal
from pathlib import Path

from balanca.etiquetas import EtiquetaGondola
from balanca.modelos import ItemBalanca, TipoVenda

# Setores da balança por seção do mercadológico (cada balança costuma ficar num balcão)
SETOR_POR_SECAO = {301: (1, "HORTIFRUTI"), 302: (1, "HORTIFRUTI"), 401: (2, "ACOUGUE"), 402: (2, "ACOUGUE"),
                   501: (3, "PADARIA"), 502: (4, "FRIOS")}

INFO_EXTRA = {
    401: "CONSERVAR RESFRIADO DE 0 A 4 C. CARNE BOVINA IN NATURA.",
    402: "CONSERVAR RESFRIADO DE 0 A 4 C. PRODUTO CRU: COZINHAR BEM ANTES DE CONSUMIR.",
    501: "CONTEM GLUTEN. PODE CONTER LEITE E OVOS.",
    502: "CONSERVAR RESFRIADO ENTRE 0 E 10 C. APOS ABERTO CONSUMIR EM ATE 3 DIAS.",
}

# Tara da embalagem (bandeja de isopor + filme) descontada automaticamente pela balança
TARA_POR_SECAO = {401: 15, 402: 15, 502: 8}

SQL_PESAVEIS = """
select p.id, p.codigo_balanca, p.descricao, p.unidade_venda, p.validade_dias, p.secao_id, pv.preco
  from erp.produto p
  join erp.produto_loja pl on pl.produto_id = p.id and pl.empresa_id = %(loja)s and pl.ativo
 cross join lateral erp.fn_preco_vigente(%(loja)s::smallint, p.id) pv
 where p.pesavel and p.ativo
 order by p.codigo_balanca
"""

SQL_GONDOLA = """
select p.id, p.descricao, p.unidade_venda, p.pesavel, p.codigo_balanca,
       (select e.ean from erp.produto_embalagem e where e.produto_id = p.id and e.venda and e.ean is not null limit 1) as ean,
       pl.preco_venda, pv.preco, pv.em_promocao,
       (select max(pr.fim) from erp.promocao pr join erp.promocao_item pi on pi.promocao_id = pr.id
         where pi.produto_id = p.id and current_date between pr.inicio and pr.fim
           and (pi.empresa_id is null or pi.empresa_id = %(loja)s)) as oferta_ate
  from erp.produto p
  join erp.produto_loja pl on pl.produto_id = p.id and pl.empresa_id = %(loja)s and pl.ativo
 cross join lateral erp.fn_preco_vigente(%(loja)s::smallint, p.id) pv
 where p.ativo
   and (%(secao)s::int is null or p.secao_id = %(secao)s::int)
   and (not %(so_promocao)s or pv.em_promocao)
 order by p.secao_id, p.descricao
 limit %(limite)s
"""


def conectar(dsn: str | None = None):
    import psycopg

    return psycopg.connect(dsn or os.environ.get("DATABASE_URL", ""), autocommit=True)


def _item_de_linha(r: dict) -> ItemBalanca:
    secao = int(r["secao_id"])
    setor, setor_nome = SETOR_POR_SECAO.get(secao, (9, "DIVERSOS"))
    return ItemBalanca(
        codigo=int(r["codigo_balanca"]),
        descricao=r["descricao"],
        preco=Decimal(str(r["preco"])),
        tipo=TipoVenda.PESO if r["unidade_venda"] == "KG" else TipoVenda.UNIDADE,
        validade_dias=int(r["validade_dias"] or 0),
        setor=setor,
        setor_nome=setor_nome,
        info_extra=INFO_EXTRA.get(secao, ""),
        tara_g=TARA_POR_SECAO.get(secao, 0),
        produto_id=int(r["id"]),
        secao_id=secao,
    )


def itens_do_erp(loja: int, dsn: str | None = None) -> list[ItemBalanca]:
    from psycopg.rows import dict_row

    with conectar(dsn) as conn, conn.cursor(row_factory=dict_row) as cur:
        cur.execute(SQL_PESAVEIS, {"loja": loja})
        return [_item_de_linha(r) for r in cur.fetchall()]


def itens_de_csv(caminho: str | Path) -> list[ItemBalanca]:
    """CSV com colunas: id;codigo_balanca;descricao;unidade_venda;validade_dias;secao_id;preco"""
    with open(caminho, newline="", encoding="utf-8") as f:
        return [_item_de_linha(r) for r in csv.DictReader(f, delimiter=";")]


def exportar_csv(itens: list[ItemBalanca], caminho: str | Path) -> None:
    with open(caminho, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f, delimiter=";")
        w.writerow(["id", "codigo_balanca", "descricao", "unidade_venda", "validade_dias", "secao_id", "preco"])
        for i in itens:
            w.writerow([i.produto_id, i.codigo, i.descricao, "KG" if i.tipo == TipoVenda.PESO else "UN",
                        i.validade_dias, i.secao_id, f"{i.preco:.2f}"])


def etiquetas_do_erp(loja: int, secao: int | None = None, so_promocao: bool = False, limite: int = 240,
                     dsn: str | None = None) -> list[EtiquetaGondola]:
    from psycopg.rows import dict_row

    with conectar(dsn) as conn, conn.cursor(row_factory=dict_row) as cur:
        cur.execute(SQL_GONDOLA, {"loja": loja, "secao": secao, "so_promocao": so_promocao, "limite": limite})
        etiquetas = []
        for r in cur.fetchall():
            preco = Decimal(str(r["preco"]))
            normal = Decimal(str(r["preco_venda"]))
            etiquetas.append(EtiquetaGondola(
                descricao=r["descricao"],
                preco=preco,
                unidade=r["unidade_venda"],
                ean=r["ean"],
                codigo_interno=r["codigo_balanca"] if r["pesavel"] else r["id"],
                preco_de=normal if r["em_promocao"] and normal > preco else None,
                oferta_ate=r["oferta_ate"] if r["em_promocao"] else None,
            ))
        return etiquetas


def parametros_balanca(loja: int, dsn: str | None = None) -> dict[str, str]:
    with conectar(dsn) as conn, conn.cursor() as cur:
        cur.execute("select chave, valor from erp.parametro_empresa where empresa_id = %s and chave like 'BALANCA_%%'",
                    (loja,))
        return dict(cur.fetchall())
