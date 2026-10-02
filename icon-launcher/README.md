# RedSmile 🔴

Appli Android perso :

1. Tu ouvres **RedSmile** et tu tapes ton **mot de passe** (la première fois, tu le crées).
2. L'appli se ferme et tu retombes sur ton écran d'accueil.
3. Par-dessus, l'animation de bienvenue démarre et dure **toute la musique** (ZELENUYU Slowed, 1 min 49).
   Elle suit le morceau grâce à une carte rythmique calculée à partir de la chanson
   (`res/raw/zelenuyu_beats.json` : 103 BPM, 190 beats, drops et breaks) :
   - **intro** : le smiley grandit dans le noir, « Bienvenue sur RedSmile » s'écrit lettre par lettre ;
   - **drops** : à chaque beat le smiley cogne et une onde rouge part ; les beats forts font flasher
     l'écran, le faire trembler, glitcher le texte et gicler des gouttes rouges ; 26 copies du smiley
     volent partout et rebondissent sur les bords ;
   - **breaks** (quand la basse coupe) : les copies se rangent en cercle et tournent doucement autour du smiley ;
   - **retour du drop** : flash, tremblement, les copies explosent depuis le cercle ;
   - **fin** : tout converge et le smiley se pose **pile à sa place dans le fond d'écran**
     (RedSmile devient ton fond d'écran, accueil + écran verrouillé).

Touche l'écran pour faire poser le smiley tout de suite : la musique continue jusqu'au bout
(bouton **Arrêter la musique** dans la notification).

## Message d'accueil

À chaque déverrouillage du téléphone, une carte RedSmile descend du haut de l'écran et te parle
(et le dit à voix haute, sauf en silencieux / vibreur) :

| Moment | Exemple |
| --- | --- |
| Matin (5 h – 11 h) | « Bonjour ! Comment s'est passée ta nuit ? » |
| Midi (11 h – 14 h) | « Salut ! C'est l'heure de manger, bon appétit ! » |
| Après-midi en semaine (14 h – 18 h) | « Te revoilà ! Comment s'est passée ta journée à l'école ? » |
| Après-midi le week-end | « Bon après-midi ! Tu profites bien du week-end ? » |
| Soir (18 h – 22 h) | « Bonsoir ! Comment s'est passée ta journée ? » |
| Nuit | « Il est tard... Pense à dormir un peu. » |

Comme une vraie personne, RedSmile ne redit pas bonjour si tu as juste regardé l'heure : elle salue au
premier déverrouillage de chaque moment de la journée, ou si le téléphone est resté verrouillé
au moins 30 minutes (« Re ! »). Si la batterie est à 20 % ou moins, elle te le rappelle.
Elle salue aussi juste après un redémarrage du téléphone.

Dans la boîte à outils (onglet Infos) : **👋 Tester le message** et **🔊 Voix** (activer / couper la voix).

> ⚠️ **Forcer l'arrêt** bloque complètement l'appli : Android ne la relance plus, même au redémarrage,
> tant que tu ne la rouvres pas toi-même. C'est une règle d'Android, aucune appli ne peut la contourner.
> Pour que RedSmile ne soit pas endormie par l'économie de batterie, accepte la demande
> « Ignorer l'optimisation de la batterie » (ou Paramètres → Batterie → Applis jamais en veille → RedSmile).

## Boîte à outils (bulle)

Après le mot de passe, une petite **bulle RedSmile** reste sur l'écran, par-dessus toutes les applis,
même quand RedSmile est fermée (et elle revient après un redémarrage du téléphone).

- **Toucher 2 fois la bulle** : ouvre la boîte à outils.
- **Glisser** : déplacer la bulle. **Appui long** : la cacher (les messages d'accueil continuent ; rouvre RedSmile pour la remettre).

**Infos** : IP publique, IP locales (Wi-Fi / mobile, IPv4 et IPv6), type de réseau et VPN, modèle,
version d'Android, processeur, batterie (%, température, tension), mémoire vive, stockage, écran,
temps depuis l'allumage. Touche une valeur pour la copier.

**Terminal** : un vrai shell Android (`/system/bin/sh`) qui garde son dossier et ses variables entre
les commandes, avec des raccourcis (`ip`, `ping`, `df`, `ps`…), l'historique (↑) et ■ Stop pour
arrêter une commande. Sans root : seules les commandes autorisées à une appli marchent, et
pas de paquets à installer comme dans Termux.

**Termux** (en haut de la boîte à outils) : ouvre Termux, un vrai Linux avec Python, `pip`, `git`…
S'il n'est pas installé, le bouton ouvre sa page F-Droid ; ensuite tape `pkg update && pkg install python`.

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
