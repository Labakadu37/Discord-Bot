# PocketBot 🤖📱

Application Android (**.apk**) pour héberger **gratuitement** ton bot Discord directement sur ton téléphone — dans l'esprit de BotGhost, mais sans serveur : c'est ton téléphone qui garde le bot en ligne.

## Fonctionnalités

- **Token → bot en ligne** : colle le token de ton bot, appuie sur « Mettre le bot en ligne ».
- **Tourne en arrière-plan** : service au premier plan + notification permanente, reconnexion automatique, reprise de session Discord, démarrage automatique avec le téléphone (option).
- **Commandes slash `/`** créées depuis l'app, enregistrées automatiquement sur Discord (réponse publique ou visible seulement par l'utilisateur).
- **Commandes à préfixe** (`!ping`, `!dis …`).
- **Variables** dans les réponses : `{user}` `{username}` `{mention}` `{server}` `{channel}` `{args}`.
- **Statut** (en ligne / inactif / ne pas déranger / invisible) et **activité** (Joue à, Écoute, Regarde, Participe à, statut perso), modifiables en direct.
- **Message de bienvenue** pour les nouveaux membres.
- **Console** pour voir l'activité du bot en temps réel.
- Lien d'**invitation** du bot généré automatiquement.

Le token est stocké uniquement sur le téléphone et n'est envoyé qu'à Discord.

## Télécharger l'APK

L'APK est construit automatiquement par GitHub Actions à chaque push :

1. Onglet **Actions** du dépôt → dernier run **Build APK**.
2. En bas, section **Artifacts** → télécharge **PocketBot-apk** (un zip qui contient `PocketBot.apk`).
3. Sur le téléphone, ouvre l'APK et autorise l'installation depuis des sources inconnues.

Pour une vraie release : crée un tag `v1.0.0` → l'APK est attaché à la release GitHub.

## Créer ton bot Discord

1. Va sur <https://discord.com/developers/applications> → **New Application**.
2. Onglet **Bot** → **Reset Token** → copie le token dans l'app.
3. Si tu utilises des commandes à **préfixe** : active **MESSAGE CONTENT INTENT**.
   Si tu utilises le **message de bienvenue** : active **SERVER MEMBERS INTENT**.
4. Démarre le bot dans l'app, puis appuie sur **Inviter le bot sur un serveur**.

## Conseils

- Autorise PocketBot à ignorer l'**optimisation de batterie** (bouton dans l'app), sinon certains téléphones (Xiaomi, Huawei, Samsung…) coupent le bot écran éteint.
- Le bot est en ligne **tant que le téléphone est allumé et connecté à Internet**.

## Compiler soi-même

Prérequis : JDK 17+ et le SDK Android (API 35).

```bash
./gradlew testReleaseUnitTest   # tests (faux serveur Discord)
./gradlew assembleRelease       # → app/build/outputs/apk/release/app-release.apk
```

## Structure

```
app/src/main/java/com/pocketbot/app/
├── MainActivity.kt
├── bot/
│   ├── DiscordGateway.kt   # WebSocket Discord : identify, heartbeat, resume, évènements
│   ├── DiscordRest.kt      # API HTTP : commandes slash, réponses, messages
│   ├── BotService.kt       # service au premier plan qui garde le bot en ligne
│   ├── BootReceiver.kt     # démarrage automatique
│   └── BotRuntime.kt       # état + logs partagés avec l'interface
├── data/ConfigStore.kt     # token, commandes et réglages (stockage local)
└── ui/                     # écrans Jetpack Compose (Bot, Commandes, Réglages, Console)
```
