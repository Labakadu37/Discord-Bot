# RedSmile 🔴

Appli Android perso :

1. Tu ouvres **RedSmile** et tu tapes ton **mot de passe** (la première fois, tu le crées).
2. L'appli se ferme et tu retombes sur ton écran d'accueil.
3. Par-dessus, l'animation de bienvenue démarre avec la musique (ZELENUYU Slowed) :
   le smiley surgit en tournant, flash rouge et écran qui tremble, puis il se démultiplie en une
   nuée de smileys qui volent partout pendant que le grand bat comme un cœur ;
   « Bienvenue sur RedSmile » s'écrit lettre par lettre avec un effet glitch.
4. Tous les smileys foncent au centre (2e flash), puis le smiley vient se poser **pile à sa place
   dans le fond d'écran** : RedSmile devient ton fond d'écran (accueil + écran verrouillé).

Touche l'écran pendant l'animation pour passer directement à la fin.

## Boîte à outils (bulle)

Après le mot de passe, une petite **bulle RedSmile** reste sur l'écran, par-dessus toutes les applis,
même quand RedSmile est fermée (et elle revient après un redémarrage du téléphone).

- **Toucher 2 fois la bulle** : ouvre la boîte à outils.
- **Glisser** : déplacer la bulle. **Appui long** : la cacher (rouvre RedSmile pour la remettre).

**Infos** : IP publique, IP locales (Wi-Fi / mobile, IPv4 et IPv6), type de réseau et VPN, modèle,
version d'Android, processeur, batterie (%, température, tension), mémoire vive, stockage, écran,
temps depuis l'allumage. Touche une valeur pour la copier.

**Terminal** : un vrai shell Android (`/system/bin/sh`) qui garde son dossier et ses variables entre
les commandes, avec des raccourcis (`ip`, `ping`, `df`, `ps`…), l'historique (↑) et ■ Stop pour
arrêter une commande. Sans root : seules les commandes autorisées à une appli marchent, et
pas de paquets à installer comme dans Termux.

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
