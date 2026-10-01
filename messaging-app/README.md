# 📱 SMS App

Application mobile (Android / iPhone) pour **envoyer et recevoir de vrais SMS** vers n'importe quel numéro de téléphone, grâce à [Twilio](https://www.twilio.com).

```
 ┌──────────────┐   HTTPS + temps réel  ┌──────────────┐    API     ┌──────────┐    SMS    ┌─────────────┐
 │  App mobile  │ ◄───────────────────► │   Serveur    │ ◄────────► │  Twilio  │ ◄───────► │ Vrai numéro │
 │   (Expo)     │      (Socket.io)      │  (Node.js)   │  webhooks  │          │           │ (téléphone) │
 └──────────────┘                       └──────────────┘            └──────────┘           └─────────────┘
```

- `server/` : serveur Node.js qui garde tes identifiants Twilio, envoie les SMS, reçoit les SMS entrants et stocke l'historique (SQLite).
- `mobile/` : app React Native (Expo) avec liste des conversations, discussion en temps réel, statut de chaque SMS (envoyé / distribué / échec).

> ⚠️ Les identifiants Twilio restent **uniquement sur le serveur**, jamais dans l'app. L'app se connecte au serveur avec un mot de passe (`APP_TOKEN`).

## 💰 Ce que ça coûte

Twilio est payant (un compte d'essai gratuit avec du crédit est disponible) :
- un numéro de téléphone Twilio : ~1 à 3 €/mois selon le pays ;
- chaque SMS envoyé/reçu : quelques centimes (voir [les tarifs](https://www.twilio.com/fr-fr/sms/pricing)).

En **compte d'essai**, tu ne peux envoyer des SMS qu'aux numéros que tu as vérifiés dans la console Twilio.

> 🇫🇷 Pour un numéro français, Twilio demande un justificatif (« Regulatory Bundle »). Un numéro d'un autre pays (ex : US) peut quand même envoyer des SMS vers la France.

## 🚀 Installation

### Prérequis
- [Node.js](https://nodejs.org) **22.13 ou plus récent**
- Un compte [Twilio](https://www.twilio.com/try-twilio) avec un numéro capable d'envoyer des SMS
- L'app **Expo Go** sur ton téléphone ([Android](https://play.google.com/store/apps/details?id=host.exp.exponent) / [iPhone](https://apps.apple.com/app/expo-go/id982107779))
- [ngrok](https://ngrok.com) (gratuit) pour rendre ton serveur accessible depuis Internet

### 1. Lancer le serveur

```bash
cd messaging-app/server
npm install
cp .env.example .env
```

Remplis `.env` :

| Variable | Où la trouver |
|---|---|
| `APP_TOKEN` | Invente un mot de passe long, ou génère-le : `node -e "console.log(require('crypto').randomBytes(24).toString('hex'))"` |
| `TWILIO_ACCOUNT_SID` / `TWILIO_AUTH_TOKEN` | Page d'accueil de la [console Twilio](https://console.twilio.com) |
| `TWILIO_PHONE_NUMBER` | Ton numéro Twilio, ex : `+33757000000` |
| `PUBLIC_URL` | L'adresse donnée par ngrok (étape 2) |
| `DEFAULT_COUNTRY_CODE` | `33` pour la France (permet de taper `06…` au lieu de `+336…`) |

### 2. Exposer le serveur sur Internet

Dans un autre terminal :

```bash
ngrok http 3000
```

Copie l'adresse `https://xxxx.ngrok-free.app` dans `PUBLIC_URL` du `.env`, puis démarre le serveur :

```bash
npm start
```

### 3. Brancher Twilio sur ton serveur

Dans la console Twilio → **Phone Numbers → Manage → Active numbers** → clique sur ton numéro → section **Messaging** :
- **A message comes in** : Webhook, `https://xxxx.ngrok-free.app/webhooks/sms`, méthode `HTTP POST`.

Enregistre. Les SMS envoyés à ton numéro Twilio arriveront maintenant dans l'app.

### 4. Lancer l'app mobile

```bash
cd messaging-app/mobile
npm install
npm start
```

Scanne le QR code avec **Expo Go** (Android) ou l'appareil photo (iPhone). Au premier lancement, entre :
- l'adresse du serveur : `https://xxxx.ngrok-free.app`
- le token : la valeur de `APP_TOKEN`

C'est prêt : appuie sur ✏️, tape un numéro et envoie ton premier SMS 🎉

## 📦 Créer un vrai fichier APK / app iPhone

Pour installer l'app sans Expo Go, utilise [EAS Build](https://docs.expo.dev/build/setup/) :

```bash
npm install -g eas-cli
eas login
eas build -p android --profile preview   # génère un .apk installable
```

Pense à changer `com.example.smsapp` dans `mobile/app.json` par ton propre identifiant.

## 🔒 Sécurité

- Toutes les routes `/api` et la connexion temps réel exigent `APP_TOKEN`. Ne le partage pas : quiconque l'a peut envoyer des SMS à tes frais.
- Les webhooks Twilio sont vérifiés par signature (`X-Twilio-Signature`) : personne ne peut injecter de faux SMS.
- Avec ngrok gratuit, l'adresse change à chaque redémarrage : mets à jour `PUBLIC_URL`, le webhook Twilio et l'adresse dans l'app. Pour un usage permanent, héberge le serveur (Railway, Render, un VPS…).

## 🛠️ API du serveur

Toutes les routes `/api/*` attendent l'en-tête `Authorization: Bearer <APP_TOKEN>`.

| Méthode | Route | Description |
|---|---|---|
| `GET` | `/api/me` | Numéro Twilio utilisé |
| `GET` | `/api/conversations` | Liste des conversations (dernier message, non lus) |
| `GET` | `/api/conversations/:numero/messages?before=<id>` | Messages d'une conversation (50 par page) |
| `POST` | `/api/conversations/:numero/read` | Marque la conversation comme lue |
| `DELETE` | `/api/conversations/:numero` | Supprime la conversation |
| `POST` | `/api/messages` | Envoie un SMS — corps JSON `{ "to": "+33612345678", "body": "Salut" }` |
| `POST` | `/webhooks/sms` | Webhook Twilio : SMS reçu |
| `POST` | `/webhooks/status` | Webhook Twilio : statut d'un SMS envoyé |

Événements Socket.io envoyés à l'app : `message` (nouveau message) et `status` (statut mis à jour).
