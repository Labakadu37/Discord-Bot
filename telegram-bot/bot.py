"""Bot Telegram de téléchargement de vidéos (TikTok, Instagram, YouTube, X, ...)."""

import asyncio
import logging
import os
import re
import secrets
import tempfile
from pathlib import Path

from dotenv import load_dotenv
from telegram import InlineKeyboardButton, InlineKeyboardMarkup, InputMediaPhoto, InputMediaVideo, Update
from telegram.constants import ChatAction, ParseMode
from telegram.error import TelegramError
from telegram.ext import (
    Application,
    CallbackQueryHandler,
    CommandHandler,
    ContextTypes,
    MessageHandler,
    filters,
)

import downloader
from storage import Storage

load_dotenv()
logging.basicConfig(format="%(asctime)s %(levelname)s %(name)s: %(message)s", level=logging.INFO)
logging.getLogger("httpx").setLevel(logging.WARNING)
log = logging.getLogger("bot")

TOKEN = os.environ["TELEGRAM_TOKEN"]
ADMIN_IDS = {int(x) for x in os.getenv("ADMIN_IDS", "").replace(" ", "").split(",") if x}
LOCAL_API = os.getenv("LOCAL_BOT_API_URL", "").rstrip("/")
# 50 Mo max avec l'API Telegram publique, 2 Go avec un serveur Bot API local.
MAX_BYTES = (2000 if LOCAL_API else 50) * 1024 * 1024

URL_RE = re.compile(r"https?://[^\s<>\"]+")
MAX_PENDING_LINKS = 20

db = Storage(os.getenv("DB_PATH", "data/bot.db"))
slots = asyncio.Semaphore(int(os.getenv("MAX_PARALLEL", "3")))

PLATFORM_LIST = "TikTok, Instagram, YouTube, X (Twitter), Facebook, Snapchat, Pinterest, Reddit, Threads, Twitch, Vimeo, SoundCloud…"

WELCOME = (
    "👋 <b>Salut {name} !</b>\n\n"
    "Envoie-moi un lien et je te renvoie la vidéo, <b>sans filigrane</b> quand c'est possible.\n\n"
    f"📱 <b>Plateformes :</b> {PLATFORM_LIST}\n\n"
    "🎬 Vidéo ou 🎵 audio MP3 : tu choisis après avoir envoyé le lien.\n"
    "ℹ️ /aide pour plus d'infos."
)

HELP = (
    "📖 <b>Comment ça marche</b>\n\n"
    "1. Copie le lien de la vidéo (bouton <i>Partager → Copier le lien</i>).\n"
    "2. Colle-le ici.\n"
    "3. Choisis 🎬 Vidéo ou 🎵 Audio.\n\n"
    "⚠️ Les comptes privés ne marchent pas.\n"
    "⚠️ Limite de taille : {limit} Mo par fichier (la qualité baisse automatiquement si besoin).\n\n"
    "<b>Commandes</b>\n"
    "/start – accueil\n"
    "/aide – cette aide\n"
    "/moi – ton nombre de téléchargements"
)


def remember(update: Update) -> None:
    user = update.effective_user
    if user:
        db.save_user(user.id, user.username, user.first_name)


async def start(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    remember(update)
    name = update.effective_user.first_name if update.effective_user else ""
    await update.message.reply_text(WELCOME.format(name=name), parse_mode=ParseMode.HTML)


async def help_cmd(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    remember(update)
    await update.message.reply_text(HELP.format(limit=MAX_BYTES // (1024 * 1024)), parse_mode=ParseMode.HTML)


async def me_cmd(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    remember(update)
    count = db.user_count(update.effective_user.id)
    await update.message.reply_text(f"📊 Tu as téléchargé <b>{count}</b> fichier(s).", parse_mode=ParseMode.HTML)


async def stats_cmd(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    if update.effective_user.id not in ADMIN_IDS:
        return
    s = db.stats()
    platforms = "\n".join(f"  • {name} : {n}" for name, n in s["platforms"]) or "  (rien)"
    await update.message.reply_text(
        "📈 <b>Statistiques</b>\n\n"
        f"👥 Utilisateurs : <b>{s['users']}</b> (+{s['new_today']} aujourd'hui)\n"
        f"⬇️ Téléchargements : <b>{s['downloads']}</b> ({s['today']} aujourd'hui)\n"
        f"❌ Échecs : {s['failed']}\n\n"
        f"<b>Par plateforme</b>\n{platforms}",
        parse_mode=ParseMode.HTML,
    )


async def on_link(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    remember(update)
    match = URL_RE.search(update.message.text or "")
    if not match:
        if update.effective_chat.type == "private":
            await update.message.reply_text("🔗 Envoie-moi un lien de vidéo (TikTok, Instagram, YouTube…).")
        return

    url = match.group(0)
    # Les données de bouton Telegram sont limitées à 64 octets : on garde le lien côté bot.
    links: dict = context.user_data.setdefault("links", {})
    while len(links) >= MAX_PENDING_LINKS:
        links.pop(next(iter(links)))
    key = secrets.token_urlsafe(6)
    links[key] = url

    platform = downloader.platform_name(url)
    label = f"📱 {platform}" if platform != "Autre" else "🌐 Lien"
    keyboard = InlineKeyboardMarkup([[
        InlineKeyboardButton("🎬 Vidéo", callback_data=f"v:{key}"),
        InlineKeyboardButton("🎵 Audio MP3", callback_data=f"a:{key}"),
    ]])
    await update.message.reply_text(f"{label} — tu veux quoi ?", reply_markup=keyboard)


async def on_choice(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    query = update.callback_query
    kind, _, key = (query.data or "").partition(":")
    url = context.user_data.get("links", {}).pop(key, None)
    if not url:
        await query.answer("Lien expiré, renvoie-le 🙏", show_alert=True)
        return
    if context.user_data.get("busy"):
        context.user_data["links"][key] = url
        await query.answer("⏳ Attends la fin de ton téléchargement en cours.", show_alert=True)
        return

    context.user_data["busy"] = True
    audio_only = kind == "a"
    kind = "audio" if audio_only else "video"
    platform = downloader.platform_name(url)
    user_id = update.effective_user.id
    chat_id = query.message.chat_id
    try:
        await query.answer()
        if slots.locked():
            await query.edit_message_text("🕒 Beaucoup de monde, tu es dans la file d'attente…")
        async with slots:
            await query.edit_message_text("⏳ Téléchargement en cours…")
            await context.bot.send_chat_action(chat_id, ChatAction.RECORD_VOICE if audio_only else ChatAction.UPLOAD_VIDEO)
            with tempfile.TemporaryDirectory() as tmp:
                try:
                    media = await asyncio.to_thread(downloader.download, url, Path(tmp), audio_only, MAX_BYTES)
                except downloader.DownloadError as e:
                    db.log_download(user_id, platform, kind, ok=False)
                    await query.edit_message_text(str(e))
                    return
                except Exception:
                    log.exception("Échec du téléchargement de %s", url)
                    db.log_download(user_id, platform, kind, ok=False)
                    await query.edit_message_text("❌ Erreur inattendue, réessaie plus tard.")
                    return

                await query.edit_message_text("📤 Envoi…")
                await send_media(context, chat_id, media, audio_only)
        db.log_download(user_id, platform, kind, ok=True)
        await query.delete_message()
    except TelegramError:
        log.exception("Échec de l'envoi de %s", url)
        db.log_download(user_id, platform, kind, ok=False)
        try:
            await query.edit_message_text("❌ Telegram a refusé le fichier, réessaie en audio ou plus tard.")
        except TelegramError:
            pass
    finally:
        context.user_data["busy"] = False


async def send_media(context: ContextTypes.DEFAULT_TYPE, chat_id: int, media: list, audio_only: bool) -> None:
    bot = context.bot
    if audio_only:
        for m in media:
            with m.path.open("rb") as f:
                await bot.send_audio(chat_id, f, title=m.title, duration=m.duration)
        return

    if len(media) > 1:
        files = [m.path.open("rb") for m in media]
        try:
            group = [
                InputMediaVideo(f, supports_streaming=True) if m.is_video else InputMediaPhoto(f)
                for m, f in zip(media, files)
            ]
            await bot.send_media_group(chat_id, group)
        finally:
            for f in files:
                f.close()
        return

    m = media[0]
    with m.path.open("rb") as f:
        if m.is_video:
            await bot.send_video(chat_id, f, duration=m.duration, supports_streaming=True)
        elif m.path.suffix.lower() in {".jpg", ".jpeg", ".png", ".webp"}:
            await bot.send_photo(chat_id, f)
        else:
            await bot.send_document(chat_id, f)


async def on_error(update: object, context: ContextTypes.DEFAULT_TYPE) -> None:
    log.error("Erreur non gérée", exc_info=context.error)


def main() -> None:
    builder = (
        Application.builder()
        .token(TOKEN)
        .concurrent_updates(True)  # plusieurs utilisateurs en même temps
        .read_timeout(60)
        .write_timeout(300)
        .media_write_timeout(300)
    )
    if LOCAL_API:
        builder = builder.base_url(f"{LOCAL_API}/bot").base_file_url(f"{LOCAL_API}/file/bot").local_mode(True)
    app = builder.build()

    app.add_handler(CommandHandler("start", start))
    app.add_handler(CommandHandler(["aide", "help"], help_cmd))
    app.add_handler(CommandHandler("moi", me_cmd))
    app.add_handler(CommandHandler("stats", stats_cmd))
    app.add_handler(CallbackQueryHandler(on_choice, pattern=r"^[va]:"))
    app.add_handler(MessageHandler(filters.TEXT & ~filters.COMMAND, on_link))
    app.add_error_handler(on_error)

    log.info("Bot démarré (limite %d Mo)", MAX_BYTES // (1024 * 1024))
    app.run_polling(allowed_updates=Update.ALL_TYPES)


if __name__ == "__main__":
    main()
