# RedSmile PC — v1 (.exe Windows)

Vrai programme Windows (`RedSmile.exe`), pour le fun. Fenêtre noire avec le smiley rouge dessiné.
Rien n'est installé, rien n'est modifié sur le PC.

## Lancer
Double-clic sur **RedSmile.exe**.
1. Un avertissement s'affiche → clique **OK** pour lancer (ou Annuler).
2. Une fenêtre noire avec le smiley rouge s'ouvre, avec un petit son.
3. **Échap** ou fermer la fenêtre pour quitter.

## Windows Defender / SmartScreen
Un .exe non signé peut déclencher « Windows a protégé votre PC ».
Pour le lancer (c'est ton fichier perso) : clique sur **Informations complémentaires** → **Exécuter quand même**.
Ne désactive pas ton antivirus.

## Recompiler (sur Linux)
```sh
sudo apt-get install mingw-w64
./build.sh
```

## Prochaines versions
v1 = juste la fenêtre + le smiley + avertissement + son. On ajoutera le reste version par version.
