package com.image3d.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private val SECTIONS = listOf(
    "Comment ça marche ?" to
        "1. Détourage : un petit réseau (U²-Net) sépare l'objet du fond.\n" +
        "2. TripoSR, une IA de Stability AI et Tripo, « imagine » l'objet en entier (y compris l'arrière) à partir d'une seule image. Elle produit un volume 3D invisible (un triplan).\n" +
        "3. Le téléphone interroge ce volume en des millions de points pour savoir où se trouve la matière, puis construit le maillage (surface nets).\n" +
        "4. L'IA donne aussi la couleur en chaque point : le modèle est colorié.\n" +
        "5. Un squelette automatique est ajouté pour animer l'objet.",
    "100 % local" to
        "Aucun serveur, aucun compte, aucune limite de générations. Internet sert seulement à télécharger l'IA la première fois. " +
        "Tes images ne quittent jamais le téléphone.",
    "Conseils pour de bons résultats" to
        "• Un seul sujet, entier, bien éclairé, vu de face ou de trois-quarts.\n" +
        "• Évite les objets coupés par le bord de la photo.\n" +
        "• Les objets, jouets, figurines, meubles, animaux et personnages stylisés marchent très bien.\n" +
        "• Si le détourage rate, utilise une image au fond déjà transparent ou uni et désactive « Supprimer le fond ».\n" +
        "• Commence en « Rapide » pour tester, puis « Détaillé » pour le résultat final.",
    "Durée" to
        "Selon le téléphone : de 30 secondes à quelques minutes. L'étape la plus longue est « L'IA imagine la forme ». " +
        "Tu peux quitter l'application pendant le calcul.",
    "Animations" to
        "Le squelette automatique est une chaîne d'os verticale : il permet de faire tourner, flotter, sauter, danser, onduler, " +
        "trembler comme une gelée… n'importe quel objet. Toutes les animations sont incluses dans l'export GLB et apparaissent " +
        "comme des « actions » dans Blender ou des clips dans Unity/Godot.",
    "Formats d'export" to
        "• GLB : couleurs + squelette + animations (Blender, Unity, Godot, Unreal, sites web, Windows 3D Viewer).\n" +
        "• OBJ : maillage + couleurs par sommet.\n" +
        "• STL : impression 3D (en millimètres, Z vers le haut).\n" +
        "Les fichiers sont enregistrés dans Téléchargements/Image3D.",
    "Crédits et licences" to
        "TripoSR — Stability AI & Tripo AI, licence MIT.\n" +
        "U²-Net (u2netp) — Qin et al., licence Apache 2.0.\n" +
        "ONNX Runtime — Microsoft, licence MIT.",
)

@Composable
fun InfoScreen(back: () -> Unit) {
    Scaffold(topBar = { BackBar("Infos", back) }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SECTIONS.forEach { (title, body) ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Text(body, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
