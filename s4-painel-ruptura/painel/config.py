from __future__ import annotations

import os
from dataclasses import dataclass, field


@dataclass(frozen=True)
class Config:
    database_url: str = field(default_factory=lambda: os.environ.get(
        "DATABASE_URL", "postgresql://painel_s4:painel_s4@localhost:5432/postgres"))
    telegram_token: str = field(default_factory=lambda: os.environ.get("TELEGRAM_BOT_TOKEN", ""))
    telegram_chat_id: str = field(default_factory=lambda: os.environ.get("TELEGRAM_CHAT_ID", ""))
    webhook_url: str = field(default_factory=lambda: os.environ.get("ALERTA_WEBHOOK_URL", ""))
    estado_alertas: str = field(default_factory=lambda: os.environ.get("ALERTA_ESTADO", "alertas_enviados.sqlite"))
    reenviar_apos_horas: int = field(default_factory=lambda: int(os.environ.get("ALERTA_REENVIAR_HORAS", "24")))
