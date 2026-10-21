"""Rotina de alertas (para agendar no cron / agendador de tarefas) e servidor do painel.

    painel alertas --loja 2 --canal console
    painel alertas --canal telegram           # todas as lojas; TELEGRAM_BOT_TOKEN e TELEGRAM_CHAT_ID no ambiente
    painel servidor --porta 8040
"""
from __future__ import annotations

import argparse
from datetime import timedelta

from painel.config import Config
from painel.consultas import Repositorio
from painel.notificacao import Console, MemoriaAlertas, Telegram, Webhook, formatar_resumo
from painel.regras import avaliar_loja


def cmd_alertas(args, cfg: Config) -> int:
    repo = Repositorio(cfg.database_url)
    if args.canal == "telegram":
        if not (cfg.telegram_token and cfg.telegram_chat_id):
            raise SystemExit("Defina TELEGRAM_BOT_TOKEN e TELEGRAM_CHAT_ID")
        canal = Telegram(cfg.telegram_token, cfg.telegram_chat_id)
    elif args.canal == "webhook":
        canal = Webhook(cfg.webhook_url)
    else:
        canal = Console()
    memoria = MemoriaAlertas(":memory:" if args.sem_memoria else cfg.estado_alertas)

    lojas = [l for l in repo.lojas() if args.loja in (None, l["id"])]
    for loja in lojas:
        alertas = avaliar_loja(repo, loja["id"])
        novos = memoria.filtrar_novos(alertas, timedelta(hours=cfg.reenviar_apos_horas))
        canal.enviar(formatar_resumo(f"{loja['fantasia']} ({loja['cidade']})", novos))
        memoria.registrar(novos)
        print(f"[{canal.nome}] loja {loja['id']}: {len(alertas)} alertas, {len(novos)} enviados")
    return 0


def cmd_servidor(args, cfg: Config) -> int:
    import uvicorn

    uvicorn.run("painel.app:app", host=args.host, port=args.porta)
    return 0


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(prog="painel", description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="comando", required=True)
    a = sub.add_parser("alertas", help="avalia as regras e envia os alertas novos")
    a.add_argument("--loja", type=int)
    a.add_argument("--canal", choices=["console", "telegram", "webhook"], default="console")
    a.add_argument("--sem-memoria", action="store_true", help="envia tudo, ignorando o que já foi enviado")
    a.set_defaults(func=cmd_alertas)
    s = sub.add_parser("servidor", help="sobe o painel web")
    s.add_argument("--host", default="127.0.0.1")
    s.add_argument("--porta", type=int, default=8040)
    s.set_defaults(func=cmd_servidor)
    args = p.parse_args(argv)
    return args.func(args, Config())


if __name__ == "__main__":
    raise SystemExit(main())
