"""Téléchargement des vidéos/audios avec yt-dlp (TikTok, Instagram, YouTube, X, ...)."""

import os
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlparse

import yt_dlp

# Domaine -> nom affiché. yt-dlp gère bien plus de sites : les autres liens sont tentés quand même.
PLATFORMS = {
    "tiktok.com": "TikTok",
    "instagram.com": "Instagram",
    "youtube.com": "YouTube",
    "youtu.be": "YouTube",
    "twitter.com": "X",
    "x.com": "X",
    "facebook.com": "Facebook",
    "fb.watch": "Facebook",
    "snapchat.com": "Snapchat",
    "pinterest.com": "Pinterest",
    "pin.it": "Pinterest",
    "reddit.com": "Reddit",
    "redd.it": "Reddit",
    "threads.net": "Threads",
    "twitch.tv": "Twitch",
    "vimeo.com": "Vimeo",
    "dailymotion.com": "Dailymotion",
    "soundcloud.com": "SoundCloud",
    "likee.video": "Likee",
    "bilibili.com": "Bilibili",
}

# Du meilleur au plus léger : on descend en qualité si le fichier dépasse la limite.
# Sur TikTok, yt-dlp préfère d'office les formats sans filigrane.
VIDEO_FORMATS = [
    "bv*[height<=1080][ext=mp4]+ba[ext=m4a]/b[height<=1080][ext=mp4]/b[height<=1080]/b",
    "bv*[height<=720][ext=mp4]+ba[ext=m4a]/b[height<=720][ext=mp4]/b[height<=720]",
    "bv*[height<=480]+ba/b[height<=480]/worst",
]
AUDIO_FORMAT = "ba/b"

MAX_FILES = 10  # carrousels Instagram, threads X, etc.


class DownloadError(Exception):
    """Message d'erreur lisible pour l'utilisateur."""


@dataclass
class Media:
    path: Path
    title: str
    duration: int | None
    is_video: bool


def platform_name(url: str) -> str:
    host = (urlparse(url).hostname or "").lower()
    for domain, name in PLATFORMS.items():
        if host == domain or host.endswith("." + domain):
            return name
    return "Autre"


def _base_options(folder: Path, max_bytes: int) -> dict:
    opts = {
        "outtmpl": str(folder / "%(id)s_%(autonumber)s.%(ext)s"),
        "quiet": True,
        "no_warnings": True,
        "noprogress": True,
        "noplaylist": True,
        "playlist_items": f"1:{MAX_FILES}",
        "max_filesize": max_bytes,
        "socket_timeout": 30,
        "retries": 3,
    }
    cookies = os.getenv("COOKIES_FILE")
    if cookies and Path(cookies).is_file():
        opts["cookiefile"] = cookies
    return opts


def _entries(info: dict) -> list[dict]:
    if info.get("_type") == "playlist":
        return [e for e in info.get("entries") or [] if e]
    return [info]


def _collect(folder: Path, info: dict, is_video: bool, max_bytes: int) -> list[Media]:
    entries = _entries(info)
    files = sorted(
        p for p in folder.iterdir()
        if p.is_file() and not p.name.endswith((".part", ".ytdl", ".temp"))
    )
    if not files:
        return []
    if any(p.stat().st_size > max_bytes for p in files):
        return []
    media = []
    for i, path in enumerate(files):
        entry = entries[i] if i < len(entries) else info
        media.append(Media(
            path=path,
            title=(entry.get("title") or info.get("title") or "media")[:200],
            duration=int(entry["duration"]) if entry.get("duration") else None,
            is_video=is_video and path.suffix.lower() in {".mp4", ".mov", ".webm", ".mkv"},
        ))
    return media


def _clear(folder: Path) -> None:
    for p in folder.iterdir():
        if p.is_file():
            p.unlink()


def download(url: str, folder: Path, audio_only: bool, max_bytes: int) -> list[Media]:
    """Télécharge le lien dans `folder`. Bloquant : à appeler dans un thread."""
    attempts = [AUDIO_FORMAT] if audio_only else VIDEO_FORMATS
    for fmt in attempts:
        opts = _base_options(folder, max_bytes)
        opts["format"] = fmt
        if audio_only:
            opts["postprocessors"] = [{
                "key": "FFmpegExtractAudio",
                "preferredcodec": "mp3",
                "preferredquality": "192",
            }]
        else:
            opts["merge_output_format"] = "mp4"
        try:
            with yt_dlp.YoutubeDL(opts) as ydl:
                info = ydl.extract_info(url, download=True)
        except yt_dlp.utils.DownloadError as e:
            text = str(e).lower()
            if "requested format is not available" in text:
                _clear(folder)
                continue
            if "login" in text or "cookies" in text or "private" in text:
                raise DownloadError("🔒 Ce contenu est privé ou demande une connexion.") from e
            if "postprocessing" in text and audio_only:
                raise DownloadError("🔇 Impossible d'extraire le son (la vidéo n'en a peut-être pas).") from e
            if "unsupported url" in text:
                raise DownloadError("🤷 Ce site n'est pas supporté.") from e
            raise DownloadError("❌ Impossible de télécharger ce lien (supprimé, privé ou bloqué).") from e
        if info is None:
            _clear(folder)
            continue
        media = _collect(folder, info, not audio_only, max_bytes)
        if media:
            return media
        _clear(folder)  # trop lourd : on réessaie en qualité plus basse
    limit_mb = max_bytes // (1024 * 1024)
    raise DownloadError(f"📦 Le fichier dépasse {limit_mb} Mo, même en basse qualité.")
