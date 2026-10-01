# RedSmile 🔴

Appli Android perso : on l'ouvre, on entre son **mot de passe**, et **toutes les icônes des applis**
s'affichent avec l'image RedSmile (déjà intégrée à l'appli, rien à choisir).

## Comment ça marche

Android ne permet pas à une appli de modifier les icônes des autres applis.
La seule façon de le faire, c'est de remplacer l'**écran d'accueil** (le « launcher ») :
RedSmile affiche toutes tes applis, avec des icônes générées à partir de l'image.

## Utilisation

1. Installe l'APK sur ton téléphone (autorise « sources inconnues » si demandé).
2. Ouvre **RedSmile** : la première fois, crée ton mot de passe (4 caractères minimum, à taper deux fois).
3. Appuie sur **Écran d'accueil** et choisis *RedSmile* comme appli d'accueil par défaut.

Après le mot de passe, un **écran de chargement** s'affiche : le RedSmile tourne en rond pendant
que la musique (ZELENUYU Slowed) joue, puis tes applis apparaissent (touche l'écran pour passer).
Le **fond d'écran** (accueil et verrouillage) est aussi remplacé par l'image RedSmile.

Ensuite, à chaque fois que l'écran s'éteint, RedSmile se reverrouille : il faut retaper le mot de passe
pour voir et ouvrir les applis. Le bouton 🔒 verrouille tout de suite.

### Sur le vrai écran d'accueil Samsung

Android n'autorise aucune appli à changer les icônes de l'écran d'accueil Samsung. Par contre, RedSmile
peut y **ajouter des raccourcis** avec l'icône RedSmile, et chaque raccourci ouvre la vraie appli :

1. Remets l'écran d'accueil Samsung par défaut (Paramètres → Applis → Applis par défaut → Appli d'accueil → One UI Home).
2. Ouvre RedSmile → **📌 Écran du téléphone** → coche les applis (ou **Toutes**).
3. Appuie sur **Ajouter** dans chaque fenêtre Samsung.
4. Enlève les anciennes icônes de l'écran d'accueil (appui long → Supprimer de l'écran d'accueil ;
   ça ne désinstalle pas l'appli).

Si tu changes d'image ou l'option Logo, les raccourcis déjà posés se mettent à jour tout seuls.
Les icônes du tiroir d'applis (la liste complète des applis) restent celles d'origine : ça, Android ne permet pas de le changer.

Options :

| Option | Effet |
| --- | --- |
| **Logo** | Ajoute le logo de chaque appli dans le coin de l'icône (sinon toutes les icônes sont identiques). |
| **Mosaïque** | Chaque appli reçoit un morceau différent de l'image : la grille entière forme l'image. |
| **🖼 Choisir une image** | Remplace RedSmile par une autre image de la galerie. |
| **Image RedSmile** | Revient à l'image RedSmile. |

Appui long sur une icône : la mettre sur l'écran d'accueil du téléphone, ou ouvrir ses infos.

**Mot de passe oublié ?** Paramètres → Applis → RedSmile → Stockage → Effacer les données.
Au prochain lancement, tu en recrées un nouveau.

Pour revenir à ton ancien écran d'accueil : Paramètres → Applis → Applis par défaut → Appli d'accueil.

## Compiler

Avec Android Studio, ou en ligne de commande (JDK 17+, SDK Android 34) :

```sh
./gradlew assembleDebug
# APK : app/build/outputs/apk/debug/app-debug.apk
```

> iPhone : iOS n'autorise aucune appli à changer les icônes des autres, donc ce projet est uniquement pour Android.
