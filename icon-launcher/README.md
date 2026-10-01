# Mes Icônes 🖼

Appli Android : tu choisis une image dans ta galerie et **toutes les icônes de tes applis** sont refaites avec cette image.

## Comment ça marche

Android ne permet pas à une appli de modifier les icônes des autres applis.
La seule façon de le faire, c'est de remplacer l'**écran d'accueil** (le « launcher ») :
*Mes Icônes* affiche toutes tes applis, avec des icônes générées à partir de ton image.

## Utilisation

1. Installe l'APK (`app-debug.apk`) sur ton téléphone (autorise « sources inconnues » si demandé).
2. Ouvre **Mes Icônes** et appuie sur **🖼 Choisir une image**.
3. Appuie sur **Écran d'accueil** et choisis *Mes Icônes* comme appli d'accueil par défaut.
   Le bouton Home affiche maintenant tes applis avec les nouvelles icônes.

Options :

| Option | Effet |
| --- | --- |
| **Logo** | Garde le logo de chaque appli au centre (sinon toutes les icônes sont identiques). |
| **Mosaïque** | Chaque appli reçoit un morceau différent de l'image : la grille entière forme ta photo. |
| **Réinitialiser** | Revient aux icônes d'origine. |

Appui long sur une icône : ouvre les infos de l'appli (désinstaller, permissions…).
Pour revenir à ton ancien écran d'accueil : Paramètres → Applis → Applis par défaut → Appli d'accueil.

## Compiler

Avec Android Studio, ou en ligne de commande (JDK 17+, SDK Android 34) :

```sh
./gradlew assembleDebug
# APK : app/build/outputs/apk/debug/app-debug.apk
```

> iPhone : iOS n'autorise aucune appli à changer les icônes des autres. La seule méthode est l'appli
> Raccourcis (une icône à la fois), donc ce projet est uniquement pour Android.
