# 🤖 Bot Telegram : créateur de packs d'emojis

Avec @Stickers, il faut envoyer chaque emoji sous forme d'**image 100x100**.
Avec ce bot, tu lui **envoies directement les emojis** (même ceux d'autres packs) et il les met dans **ton** pack.

## Commandes

| Commande | Ce qu'elle fait |
|---|---|
| `/createpack Nom` | Crée un pack. Tu peux mettre des emojis juste après le nom pour les ajouter direct : `/createpack Jz Brawl 🔥💀` |
| `/addemoji 🔥💀…` | Ajoute des emojis à ton pack (voir ce que tu peux envoyer plus bas) |
| `/removeemoji 💀` | Retire des emojis de ton pack |
| `/renamepack Nouveau nom` | Renomme ton pack (le lien reste le même) |
| `/copypack lien [Nom]` | Copie un pack entier dans un **nouveau** pack : `/copypack https://t.me/addemoji/XXX Ma copie` |
| `/seticon 🔥` | Choisit l'icône du pack (un emoji de ton pack) |
| `/mypacks` | Liste tes packs (avec le nombre d'emojis et le lien) et te laisse choisir celui à utiliser |
| `/deletepack` | Supprime ton pack (le bot demande confirmation) |
| `/cancel` | Annule un pack en attente de création |
| `/help` | Affiche l'aide |

Toutes les commandes utilisent **ton pack actuel** : le dernier créé, ou celui choisi avec `/mypacks`.

💡 Tu peux aussi **répondre à un message** avec `/addemoji` ou `/removeemoji` : le bot prend les emojis
(ou le sticker, l'image) de ce message.

## Ce que `/addemoji` accepte

| Tu envoies… | Le bot… |
|---|---|
| Des emojis premium d'autres packs (autant que tu veux dans un seul message) | les copie dans ton pack, dans l'ordre |
| Un lien `t.me/addemoji/...` ou `t.me/addstickers/...` | ajoute **tout le pack** |
| Des emojis normaux 🔥💀⭐ | les transforme en image (style Apple, comme sur Telegram) |
| Un sticker (même animé ou vidéo) | le réduit en 100x100 et l'ajoute |
| Une image (PNG, WEBP, photo) avec `/addemoji` en légende | la réduit en 100x100 et l'ajoute (mets un emoji en légende pour choisir l'emoji associé) |

Les doublons et les emojis déjà présents dans le pack sont ignorés.
Quand un pack est plein (200 emojis maximum), le bot continue tout seul dans un nouveau pack « Nom 2 ».

## Partout : en privé et dans les groupes

- **En privé** : les commandes marchent, et tu peux même envoyer tes emojis sans commande.
- **Dans un groupe** : ajoute le bot au groupe, puis utilise les commandes (`/addemoji`, `/createpack`…).
  Chaque personne a ses propres packs. Les messages normaux du groupe sont ignorés.
- **Le pack** est un vrai pack d'emojis Telegram : son lien `t.me/addemoji/...` marche pour tout le monde,
  et tu peux utiliser ses emojis dans toutes tes discussions (Telegram demande **Premium** pour ça).
  Dans un groupe, un admin peut aussi le mettre comme « pack d'emojis du groupe » (il faut des boosts),
  et là tous les membres peuvent l'utiliser dans ce groupe, même sans Premium.

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
4. Ouvre ton bot sur Telegram, envoie `/createpack Jz Brawl`, puis `/addemoji` avec tes emojis. C'est tout ✨

Le bot doit rester allumé pour répondre (sur ton PC, sur Replit, sur un serveur...).
Tes packs sont enregistrés dans `data.json`.

## Bon à savoir

- Le nom technique du pack finit toujours par `_by_<nom_de_ton_bot>` (règle de Telegram pour les packs créés par un bot).
  Ce pack t'appartient. Pour le modifier, passe par ce bot.
- Telegram ne peut pas créer un pack vide : avec `/createpack Nom` seul, le pack est créé au premier `/addemoji`.
- Si le bot dit de lui envoyer `/start` en privé : ouvre-le en privé une fois, puis réessaie dans le groupe.
- `EMOJI_STYLE` dans `.env` choisit le style des emojis normaux : `apple` (par défaut), `google` ou `twitter`.
- Les stickers vidéo sont convertis avec ffmpeg (installé automatiquement via `ffmpeg-static`).

## Tests

```bash
npm test
```

Les tests lancent le bot sur un faux serveur Telegram, donc ils n'ont pas besoin de token.
