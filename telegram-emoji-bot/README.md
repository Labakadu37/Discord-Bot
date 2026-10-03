# 🤖 Bot Telegram : créateur de packs d'emojis

Avec @Stickers, il faut envoyer chaque emoji sous forme d'**image 100x100**.
Avec ce bot, tu lui **envoies directement les emojis** (même ceux d'autres packs) et il les met dans **ton** pack.

## Ce que tu peux lui envoyer

| Tu envoies… | Le bot… |
|---|---|
| Des emojis premium d'autres packs (autant que tu veux dans un seul message) | les copie dans ton pack, dans l'ordre |
| Un lien `t.me/addemoji/...` ou `t.me/addstickers/...` | copie **tout le pack** |
| Des emojis normaux 🔥💀⭐ | les transforme en image (style Apple, comme sur Telegram) |
| Un sticker (même animé ou vidéo) | le réduit en 100x100 et l'ajoute |
| Une image (PNG, WEBP, photo) | la réduit en 100x100 et l'ajoute (mets un emoji en légende pour choisir l'emoji associé) |

Les doublons et les emojis déjà présents dans le pack sont ignorés.
Quand un pack est plein (200 emojis maximum), le bot continue tout seul dans un nouveau pack « Nom 2 ».

## Commandes

- `/newpack Nom du pack` : crée un nouveau pack (il est créé dès le premier emoji envoyé)
- `/packs` : liste tes packs et te laisse choisir celui à remplir
- `/remove` : en réponse à un message qui contient des emojis de ton pack, les retire du pack
- `/help` : aide

## Installation

1. Sur Telegram, va voir **@BotFather**, envoie `/newbot` et suis les étapes. Il te donne un **token**
   (genre `123456789:AAH...`).
2. Installe [Node.js](https://nodejs.org) (version 18 ou plus).
3. Dans ce dossier :
   ```bash
   cp .env.example .env      # puis colle ton token après BOT_TOKEN=
   npm install
   npm start
   ```
4. Ouvre ton bot sur Telegram, envoie `/newpack Jz Brawl`, puis tes emojis. C'est tout ✨

Le bot doit rester allumé pour répondre (sur ton PC, sur Replit, sur un serveur...).
Tes packs sont enregistrés dans `data.json`.

## Bon à savoir

- Le nom technique du pack finit toujours par `_by_<nom_de_ton_bot>` (règle de Telegram pour les packs créés par un bot).
  Ce pack t'appartient. Pour y ajouter ou retirer des emojis, passe par ce bot.
- Pour utiliser des emojis personnalisés dans tes messages, Telegram demande **Telegram Premium**.
- `EMOJI_STYLE` dans `.env` choisit le style des emojis normaux : `apple` (par défaut), `google` ou `twitter`.
- Les stickers vidéo sont convertis avec ffmpeg (installé automatiquement via `ffmpeg-static`).

## Tests

```bash
npm test
```

Les tests lancent le bot sur un faux serveur Telegram, donc ils n'ont pas besoin de token.
