"""Point d'entrée du bot sur Android (appelé par BotService via Chaquopy)."""

import asyncio
import logging
import os
import tempfile
from logging.handlers import RotatingFileHandler


def run(token: str, admin_ids: str, data_dir: str) -> None:
    os.environ["TELEGRAM_TOKEN"] = token
    os.environ["ADMIN_IDS"] = admin_ids or ""
    os.environ["DB_PATH"] = os.path.join(data_dir, "bot.db")
    os.environ["XDG_CACHE_HOME"] = os.path.join(data_dir, "cache")
    os.environ.setdefault("MAX_PARALLEL", "2")  # un téléphone, c'est moins costaud qu'un PC

    tmp = os.path.join(data_dir, "tmp")
    os.makedirs(tmp, exist_ok=True)
    tempfile.tempdir = tmp

    # Journal lu par l'écran de l'appli (MainActivity).
    logging.basicConfig(
        format="%(asctime)s %(levelname)s: %(message)s",
        datefmt="%H:%M:%S",
        level=logging.INFO,
        handlers=[
            RotatingFileHandler(os.path.join(data_dir, "bot.log"), maxBytes=200_000, backupCount=1, encoding="utf-8"),
            logging.StreamHandler(),
        ],
    )
    log = logging.getLogger("android")

    # On tourne dans un thread Java : pas de boucle asyncio ni de signaux par défaut.
    asyncio.set_event_loop(asyncio.new_event_loop())
    try:
        import bot
        from telegram.error import InvalidToken

        try:
            bot.main(stop_signals=None)
        except InvalidToken:
            log.error("❌ Token refusé par Telegram. Vérifie-le dans l'appli.")
    except Exception:
        log.exception("❌ Le bot s'est arrêté")
