"""Linha de comando: carga de balança, leitura de códigos e etiquetas.

Exemplos:
    balanca carga --loja 2 --modelo toledo-mgv6 --saida carga/
    balanca carga --csv pesaveis.csv --anterior carga_ontem/ --saida carga/
    balanca ler 2100100012993
    balanca ler "(01)17891234567895(17)261231(10)L42(3103)001250"
    balanca etiquetas --loja 2 --secao 105 --saida gondola.pdf
    balanca etiqueta-balanca --loja 2 --plu 1001 --peso 1.235 --saida etiqueta.pdf
"""
from __future__ import annotations

import argparse
import sys
from decimal import Decimal
from pathlib import Path

from balanca.codigo_balanca import ConfigCodigoBalanca, eh_codigo_balanca, gtin_valido, interpretar_codigo
from balanca.gs1 import interpretar_gs1
from balanca.layouts import layouts_disponiveis, obter_layout
from balanca.modelos import ItemBalanca


def _carregar_itens(args) -> list[ItemBalanca]:
    from balanca import fonte_erp

    if args.csv:
        return fonte_erp.itens_de_csv(args.csv)
    return fonte_erp.itens_do_erp(args.loja, args.dsn)


def diferencas(layout, dir_anterior: Path, itens_novos: list[dict]) -> list[str]:
    """Compara com a carga anterior: o que mudou de preço, entrou ou saiu (vai para o log da loja)."""
    nome = next(iter(layout.gerar([]).arquivos))
    arquivo = dir_anterior / nome
    if not arquivo.exists():
        return [f"(sem carga anterior em {arquivo})"]
    antigos = {i["codigo"]: i for i in layout.ler_itens(arquivo.read_text(encoding=layout.codificacao).splitlines())}
    novos = {i["codigo"]: i for i in itens_novos}
    saida = []
    for cod in sorted(novos.keys() | antigos.keys()):
        a, n = antigos.get(cod), novos.get(cod)
        if a is None:
            saida.append(f"+ PLU {cod:>6} NOVO       {n['descricao'][:30]:<30} R$ {n['preco']:.2f}")
        elif n is None:
            saida.append(f"- PLU {cod:>6} REMOVIDO   {a['descricao'][:30]}")
        elif a["preco"] != n["preco"]:
            seta = "↑" if n["preco"] > a["preco"] else "↓"
            saida.append(f"* PLU {cod:>6} PREÇO {seta}    {n['descricao'][:30]:<30} {a['preco']:.2f} -> {n['preco']:.2f}")
    return saida or ["(nenhuma alteração de preço ou cadastro)"]


def cmd_carga(args) -> int:
    layout = obter_layout(args.modelo)
    itens = _carregar_itens(args)
    res = layout.gerar(itens)
    destino = Path(args.saida)
    arquivos = layout.gravar(res, destino)

    print(f"Layout {layout.nome}: {res.total_itens} itens gravados em {destino}/")
    for a in arquivos:
        print(f"  {a.name:<14} {sum(1 for _ in a.open(encoding=layout.codificacao)):>5} linhas")
    for aviso in res.avisos:
        print(f"  aviso: {aviso}")
    for item, motivo in res.rejeitados:
        print(f"  REJEITADO PLU {item.codigo} ({item.descricao}): {motivo}")
    if args.anterior:
        principal = next(iter(res.arquivos))
        print("\nAlterações em relação à carga anterior:")
        for linha in diferencas(layout, Path(args.anterior), layout.ler_itens(res.arquivos[principal])):
            print("  " + linha)
    return 1 if res.rejeitados and args.estrito else 0


def cmd_ler(args) -> int:
    codigo = args.codigo.strip()
    cfg = ConfigCodigoBalanca(prefixo=args.prefixo, digitos_codigo=args.digitos_codigo,
                              tipo_valor=args.tipo_valor, digitos_valor=args.digitos_valor)
    try:
        if codigo.isdigit() and eh_codigo_balanca(codigo, cfg):
            l = interpretar_codigo(codigo, cfg)
            print(f"Código de balança  PLU {l.codigo}")
            if l.preco_total is not None:
                print(f"  preço total      R$ {l.preco_total:.2f}")
            if l.peso_kg is not None:
                print(f"  peso             {l.peso_kg:.3f} kg")
        elif codigo.isdigit() and len(codigo) in (8, 12, 13, 14):
            print(f"GTIN {codigo}: {'válido' if gtin_valido(codigo) else 'DÍGITO VERIFICADOR INVÁLIDO'}")
        else:
            l = interpretar_gs1(codigo.replace("<GS>", "\x1d"))
            print("GS1-128")
            for ai, valor in l.campos.items():
                print(f"  ({ai}) {valor}")
            if l.gtin:
                print(f"  GTIN             {l.gtin}")
            if l.lote:
                print(f"  lote             {l.lote}")
            if l.validade:
                print(f"  validade         {l.validade:%d/%m/%Y}" + ("  ** VENCIDO **" if l.vencido() else ""))
            if l.peso_liquido_kg is not None:
                print(f"  peso líquido     {l.peso_liquido_kg} kg")
            if l.preco is not None:
                print(f"  preço            R$ {l.preco:.2f}")
    except ValueError as erro:
        print(f"Código inválido: {erro}", file=sys.stderr)
        return 1
    return 0


def cmd_etiquetas(args) -> int:
    from balanca import fonte_erp
    from balanca.etiquetas import gerar_etiquetas_gondola

    etiquetas = fonte_erp.etiquetas_do_erp(args.loja, args.secao, args.promocao, args.limite, args.dsn)
    if not etiquetas:
        print("Nenhum produto encontrado com esses filtros.")
        return 1
    caminho = gerar_etiquetas_gondola(etiquetas, args.saida, loja=f"Loja {args.loja}")
    em_oferta = sum(1 for e in etiquetas if e.preco_de)
    print(f"{len(etiquetas)} etiquetas ({em_oferta} em oferta) em {caminho}")
    return 0


def cmd_etiqueta_balanca(args) -> int:
    from balanca import fonte_erp
    from balanca.etiquetas import gerar_etiqueta_balanca

    itens = {i.codigo: i for i in _carregar_itens(args)}
    item = itens.get(args.plu)
    if not item:
        print(f"PLU {args.plu} não encontrado", file=sys.stderr)
        return 1
    cfg = ConfigCodigoBalanca()
    if not args.csv:
        cfg = ConfigCodigoBalanca.de_parametros(fonte_erp.parametros_balanca(args.loja, args.dsn))
    caminho, ean = gerar_etiqueta_balanca(
        codigo=item.codigo, descricao=item.descricao, preco_kg=item.preco, peso_kg=Decimal(args.peso),
        validade_dias=item.validade_dias, info_extra=item.info_extra, destino=args.saida,
        loja="Rede Oeste Supermercados", config=cfg)
    print(f"Etiqueta {caminho}  EAN {ean}")
    return 0


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(prog="balanca", description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="comando", required=True)

    def origem(sp):
        sp.add_argument("--loja", type=int, default=2, help="empresa_id da loja no ERP (padrão 2)")
        sp.add_argument("--csv", help="ler itens de um CSV em vez do banco")
        sp.add_argument("--dsn", help="conexão PostgreSQL (padrão: DATABASE_URL ou variáveis PG*)")

    c = sub.add_parser("carga", help="gera os arquivos de carga da balança")
    origem(c)
    c.add_argument("--modelo", default="toledo-mgv6", choices=list(layouts_disponiveis()))
    c.add_argument("--saida", required=True, help="diretório de destino")
    c.add_argument("--anterior", help="diretório da carga anterior, para listar alterações")
    c.add_argument("--estrito", action="store_true", help="código de saída 1 se algum item for rejeitado")
    c.set_defaults(func=cmd_carga)

    l = sub.add_parser("ler", help="interpreta EAN de balança, GTIN ou GS1-128")
    l.add_argument("codigo")
    l.add_argument("--prefixo", default="2")
    l.add_argument("--digitos-codigo", type=int, default=4)
    l.add_argument("--tipo-valor", default="PRECO", choices=["PRECO", "PESO"])
    l.add_argument("--digitos-valor", type=int, default=6)
    l.set_defaults(func=cmd_ler)

    e = sub.add_parser("etiquetas", help="etiquetas de gôndola em PDF")
    e.add_argument("--loja", type=int, default=2)
    e.add_argument("--secao", type=int)
    e.add_argument("--promocao", action="store_true", help="só itens em oferta")
    e.add_argument("--limite", type=int, default=240)
    e.add_argument("--dsn")
    e.add_argument("--saida", default="etiquetas_gondola.pdf")
    e.set_defaults(func=cmd_etiquetas)

    b = sub.add_parser("etiqueta-balanca", help="simula a etiqueta impressa pela balança")
    origem(b)
    b.add_argument("--plu", type=int, required=True)
    b.add_argument("--peso", required=True, help="peso em kg, ex.: 1.235")
    b.add_argument("--saida", default="etiqueta_balanca.pdf")
    b.set_defaults(func=cmd_etiqueta_balanca)

    sub.add_parser("layouts", help="lista os layouts suportados").set_defaults(
        func=lambda a: print("\n".join(f"{k:<14} {v}" for k, v in layouts_disponiveis().items())) or 0)

    args = p.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
