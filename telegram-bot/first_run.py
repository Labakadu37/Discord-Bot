"""Dossier de l'application et configuration au premier lancement (utile pour le .exe)."""

import os
import re
import sys
from pathlib import Path

from dotenv import load_dotenv

FROZEN = getattr(sys, "frozen", False)  # True quand on tourne depuis le .exe
APP_DIR = Path(sys.executable).parent if FROZEN else Path(__file__).resolve().parent
ENV_FILE = APP_DIR / ".env"

TOKEN_RE = re.compile(r"^\d{5,}:[\w-]{30,}$")


def _ask_token() -> str:
    print("=" * 60)
    print("  Premier lancement : configuration du bot")
    print("=" * 60)
    print("1. Sur Telegram, ouvre @BotFather et tape /newbot")
    print("2. Copie le token qu'il te donne (ex. 123456789:AAH...)")
    print()
    while True:
        token = input("Colle ton token ici puis Entrée : ").strip()
        if TOKEN_RE.match(token):
            return token
        print("❌ Ce n'est pas un token valide, réessaie.")


def _ask_admin() -> str:
    print()
    print("Ton ID Telegram (donné par @userinfobot) pour la commande /stats.")
    while True:
        admin = input("Ton ID (ou Entrée pour passer) : ").strip()
        if not admin or admin.isdigit():
            return admin
        print("❌ L'ID ne contient que des chiffres.")


def load_config() -> None:
    """Charge le .env à côté du bot ; le crée en posant les questions s'il manque le token."""
    load_dotenv(ENV_FILE)
    if not os.getenv("TELEGRAM_TOKEN"):
        if not sys.stdin or not sys.stdin.isatty():
            sys.exit(f"TELEGRAM_TOKEN manquant : mets-le dans {ENV_FILE}")
        token = _ask_token()
        admin = _ask_admin()
        ENV_FILE.write_text(f"TELEGRAM_TOKEN={token}\nADMIN_IDS={admin}\n", encoding="utf-8")
        print(f"\n✅ Configuration enregistrée dans {ENV_FILE}\n")
        os.environ["TELEGRAM_TOKEN"] = token
        os.environ["ADMIN_IDS"] = admin

    # Chemins relatifs = relatifs au dossier du bot, pas au dossier courant.
    for key, default in (("DB_PATH", "data/bot.db"), ("COOKIES_FILE", "cookies.txt")):
        path = Path(os.getenv(key) or default)
        os.environ[key] = str(path if path.is_absolute() else APP_DIR / path)

    # ffmpeg livré à côté du .exe (ou du script).
    if not os.getenv("FFMPEG_LOCATION"):
        for name in ("ffmpeg.exe", "ffmpeg"):
            if (APP_DIR / name).is_file():
                os.environ["FFMPEG_LOCATION"] = str(APP_DIR)
                break
