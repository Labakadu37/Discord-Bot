# Bot Telegram de téléchargement

Envoie un lien, le bot renvoie la vidéo (sans filigrane TikTok quand c'est possible) ou l'audio en MP3.

**Plateformes :** TikTok, Instagram, YouTube, X (Twitter), Facebook, Snapchat, Pinterest, Reddit, Threads, Twitch, Vimeo, SoundCloud… (tout ce que gère [yt-dlp](https://github.com/yt-dlp/yt-dlp)).

## Fonctions

- Détection du lien dans n'importe quel message, choix 🎬 Vidéo / 🎵 Audio MP3
- Carrousels (Instagram, X…) envoyés en album, jusqu'à 10 fichiers
- Qualité réduite automatiquement si la vidéo dépasse la limite de taille
- File d'attente : plusieurs utilisateurs en même temps, 1 téléchargement à la fois par personne
- Commandes : `/start`, `/aide`, `/moi` (tes téléchargements), `/stats` (admins : utilisateurs, téléchargements, plateformes)
- Base SQLite des utilisateurs et des statistiques

## 📱 Sur ton téléphone Android (APK)

1. Sur ton téléphone, ouvre la page **Releases** du dépôt GitHub → **Bot Telegram (Android)** → télécharge `BotTelegram.apk`.
2. Ouvre le fichier et autorise « installer des applis inconnues » si Android le demande.
3. Dans l'appli : colle le token de @BotFather (et ton ID pour `/stats`), appuie sur **▶ Démarrer**.
4. Appuie sur **🔋 Ne pas couper le bot pour la batterie**, sinon Android finit par l'arrêter.

Le bot tourne tant que la notification « Bot Telegram en marche » est là. Coche « Relancer le bot quand le téléphone redémarre » pour qu'il revienne tout seul.

**Différences avec la version PC** (il n'y a pas ffmpeg sur Android) :
- l'audio est envoyé dans son format d'origine (souvent M4A) au lieu du MP3 ;
- sur YouTube, la vidéo est limitée à 360p (TikTok, Instagram, X… sont en qualité normale).

L'APK est recompilé automatiquement par GitHub Actions à chaque modification du dossier `telegram-bot/` (workflow « Bot Telegram Android »). Pour le compiler toi-même : Android Studio ou `cd android && ./gradlew assembleRelease`.

## 💻 Sur un PC ou un serveur

1. Sur Telegram, parle à **@BotFather** → `/newbot` → récupère le token.
2. Copie `.env.example` en `.env` et mets ton token dans `TELEGRAM_TOKEN` (et ton ID dans `ADMIN_IDS`, trouvable avec @userinfobot). Sans `.env`, le bot te pose les questions au premier lancement.
3. Lance :

**Avec Docker (recommandé, ffmpeg inclus)**
```bash
docker build -t telegram-dl .
docker run -d --restart unless-stopped --env-file .env -v $(pwd)/data:/app/data telegram-dl
```

**Sans Docker** (il faut Python 3.10+ et [ffmpeg](https://ffmpeg.org/download.html) installés)
```bash
pip install -r requirements.txt
python bot.py
```

## Bon à savoir

- **Mets yt-dlp à jour souvent** (`pip install -U yt-dlp`, ou reconstruis l'image Docker). TikTok, Instagram et YouTube changent régulièrement et les vieilles versions cassent.
- **Hébergement :** YouTube, TikTok et Instagram bloquent souvent les serveurs de datacenter. Si les téléchargements échouent sur ton hébergeur, essaie depuis ton PC, ou ajoute un fichier cookies (`COOKIES_FILE`, exporté depuis ton navigateur au format Netscape) — utilise un compte secondaire pour ça.
- **Limite 50 Mo :** c'est la limite de l'API Telegram pour les bots. Pour aller jusqu'à 2 Go, lance un [serveur Bot API local](https://github.com/tdlib/telegram-bot-api) et mets son adresse dans `LOCAL_BOT_API_URL`.
- Les comptes privés ne sont pas accessibles.
