# RedSmile 🔴

Appli Android perso :

1. Tu ouvres **RedSmile** et tu tapes ton **mot de passe** (la première fois, tu le crées).
2. L'appli se ferme et tu retombes sur ton écran d'accueil.
3. Par-dessus, un overlay **« Bienvenue sur RedSmile »** s'affiche : le smiley rouge tourne en rond
   pendant que la musique (ZELENUYU Slowed) joue.
4. Le smiley arrête de tourner et vient se poser **pile à sa place dans le fond d'écran** :
   RedSmile devient ton fond d'écran (accueil + écran verrouillé), puis l'overlay disparaît.

Touche l'écran pendant l'overlay pour passer directement à la fin.

## Première utilisation

- Android demande l'autorisation **« Afficher par-dessus les autres applis »** : active-la pour RedSmile,
  reviens dans l'appli et appuie à nouveau sur **Entrer**. C'est à faire une seule fois.
- Autorise les notifications si tu veux voir « Bienvenue sur RedSmile » dans la barre de notifications
  pendant la musique (pas obligatoire).

**Mot de passe oublié ?** Paramètres → Applis → RedSmile → Stockage → Effacer les données.

## Compiler

Avec Android Studio, ou en ligne de commande (JDK 17+, SDK Android 34) :

```sh
./gradlew assembleDebug
# APK : app/build/outputs/apk/debug/app-debug.apk
```
