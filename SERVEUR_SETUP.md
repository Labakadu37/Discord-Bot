# JZS Brawl — Setup Serveur KataBump

## Architecture (comme Nexus)
```
KataBump Server (51.68.34.78:20249)
    └── /jzs/libJZSUI.so  ← notre mod natif ARM64

JZS-Brawl.apk (sur ton téléphone)
    └── Au démarrage → télécharge libJZSUI.so depuis le serveur
    └── Le charge → affiche "JZS Brawl v1.0" dans le jeu
```

## Étapes à faire dans la console KataBump

### Étape 1 — Créer le fichier setup_jzs.js
Copie-colle le contenu de `setup_jzs.js` dans un nouveau fichier sur ton serveur.

### Étape 2 — Exécuter setup (UNE seule fois)
```
node setup_jzs.js
```
Ce script crée le dossier `jzs/` et y place `libJZSUI.so`.

### Étape 3 — Ajouter la route dans index.js
Ajoute ces 2 lignes dans ton `index.js`, AVANT `app.listen()`:
```javascript
const jzsRoute = require('./jzs_route');
jzsRoute(app);
```

### Étape 4 — Restart le serveur
Clique sur **Restart** dans le panel KataBump.

### Vérification
Ouvre dans un navigateur:
```
http://51.68.34.78:20249/jzs/version
```
Doit répondre: `{"version":"1.0","name":"JZS Brawl"}`

## Mettre à jour le mod
Pour envoyer une nouvelle version du mod:
1. Compile le nouveau `libJZSUI.so`
2. Remplace le fichier `jzs/libJZSUI.so` sur le serveur
3. Tous les joueurs reçoivent la mise à jour au prochain lancement du jeu
