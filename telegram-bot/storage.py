"""Utilisateurs et statistiques du bot (SQLite)."""

import sqlite3
from contextlib import closing
from datetime import datetime, timezone
from pathlib import Path


class Storage:
    def __init__(self, path: str):
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        self.path = path
        with closing(self._connect()) as db, db:
            db.executescript("""
                CREATE TABLE IF NOT EXISTS users (
                    id INTEGER PRIMARY KEY,
                    username TEXT,
                    first_name TEXT,
                    joined_at TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS downloads (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    user_id INTEGER NOT NULL,
                    platform TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    ok INTEGER NOT NULL,
                    created_at TEXT NOT NULL
                );
            """)

    def _connect(self) -> sqlite3.Connection:
        return sqlite3.connect(self.path)

    @staticmethod
    def _now() -> str:
        return datetime.now(timezone.utc).isoformat(timespec="seconds")

    def save_user(self, user_id: int, username: str | None, first_name: str | None) -> None:
        with closing(self._connect()) as db, db:
            db.execute(
                """INSERT INTO users (id, username, first_name, joined_at) VALUES (?, ?, ?, ?)
                   ON CONFLICT(id) DO UPDATE SET username = excluded.username,
                                                first_name = excluded.first_name""",
                (user_id, username, first_name, self._now()),
            )

    def log_download(self, user_id: int, platform: str, kind: str, ok: bool) -> None:
        with closing(self._connect()) as db, db:
            db.execute(
                "INSERT INTO downloads (user_id, platform, kind, ok, created_at) VALUES (?, ?, ?, ?, ?)",
                (user_id, platform, kind, int(ok), self._now()),
            )

    def user_count(self, user_id: int) -> int:
        with closing(self._connect()) as db:
            return db.execute(
                "SELECT COUNT(*) FROM downloads WHERE user_id = ? AND ok = 1", (user_id,)
            ).fetchone()[0]

    def stats(self) -> dict:
        today = datetime.now(timezone.utc).date().isoformat()
        with closing(self._connect()) as db:
            one = lambda q, *a: db.execute(q, a).fetchone()[0]
            return {
                "users": one("SELECT COUNT(*) FROM users"),
                "new_today": one("SELECT COUNT(*) FROM users WHERE joined_at >= ?", today),
                "downloads": one("SELECT COUNT(*) FROM downloads WHERE ok = 1"),
                "today": one("SELECT COUNT(*) FROM downloads WHERE ok = 1 AND created_at >= ?", today),
                "failed": one("SELECT COUNT(*) FROM downloads WHERE ok = 0"),
                "platforms": db.execute(
                    """SELECT platform, COUNT(*) FROM downloads WHERE ok = 1
                       GROUP BY platform ORDER BY COUNT(*) DESC LIMIT 10"""
                ).fetchall(),
            }
