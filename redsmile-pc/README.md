# RedSmile PC 🔴 (pour le fun)

Page web autonome qui fait apparaître plein de popups RedSmile avec un son fort.
**Juste pour rigoler** : rien n'est installé, rien n'est modifié sur le PC, tout reste dans l'onglet du navigateur.

## Utilisation

1. Ouvre **index.html** dans un navigateur (double-clic sur le fichier).
2. Lis l'avertissement, puis clique **😈 Lancer**. Rien ne démarre avant ce clic.
3. Pour tout arrêter : touche **Échap**, bouton **STOP**, ou ferme l'onglet.

Ça s'arrête aussi tout seul au bout de 15 secondes.

## Sécurités

- Ne se partage pas, ne s'installe pas, ne se lance pas tout seul.
- Nombre de popups **borné** (60 max) : le PC ne se bloque pas.
- Toujours fermable (Échap / STOP / fermer l'onglet). Rien ne touche au système.

## Modifier

Change les textes/durées dans `template.html` (section `<script>`), puis relance :

```sh
python3 build.py   # réinjecte l'image et régénère index.html
```
