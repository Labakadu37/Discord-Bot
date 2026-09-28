package com.bothostinger.app.bot.modules

import com.bothostinger.app.bot.engine.Command
import com.bothostinger.app.bot.engine.Embeds
import com.bothostinger.app.bot.engine.Module
import com.bothostinger.app.bot.engine.field
import com.bothostinger.app.bot.engine.intOpt
import com.bothostinger.app.bot.engine.stringOpt
import kotlin.random.Random

class FunModule : Module(
    id = "fun",
    title = "Fun",
    description = "Boule magique, dés, pile ou face, pierre-feuille-ciseaux, blagues…",
) {
    private val answers = listOf(
        "Oui, clairement.", "C'est certain.", "Sans aucun doute.", "Compte là-dessus.", "Très probablement.",
        "Les signes disent oui.", "Réessaie plus tard.", "Je ne peux pas le dire maintenant.", "Mieux vaut ne pas te le dire…",
        "Concentre-toi et redemande.", "N'y compte pas.", "Ma réponse est non.", "Mes sources disent non.", "Très peu probable.",
    )

    private val jokes = listOf(
        "Pourquoi les plongeurs plongent-ils toujours en arrière ?|Parce que sinon ils tombent dans le bateau.",
        "Que dit un informaticien quand il s'ennuie ?|Je me fichier.",
        "Pourquoi les bots Discord ne mentent jamais ?|Parce qu'ils ont trop de logs.",
        "Qu'est-ce qu'un crocodile qui surveille la pharmacie ?|Un Lacoste garde.",
        "Comment appelle-t-on un chat tombé dans un pot de peinture le jour de Noël ?|Un chat-peint de Noël.",
        "Pourquoi le livre de maths est-il triste ?|Parce qu'il a trop de problèmes.",
        "Que fait une fraise sur un cheval ?|Tagada tagada.",
        "Quel est le comble pour un électricien ?|De ne pas être au courant.",
        "Pourquoi les poissons détestent l'ordinateur ?|À cause du Net.",
        "Que dit une imprimante dans l'eau ?|J'ai papier.",
    )

    override val commands = listOf(
        Command("8ball", "Pose une question à la boule magique", options = listOf(stringOpt("question", "Ta question", maxLength = 250))) { i ->
            i.reply(Embeds.base("🎱 Boule magique").field("Question", i.string("question").orEmpty()).field("Réponse", "**${answers.random()}**"))
        },
        Command("pile-ou-face", "Lance une pièce") { i ->
            i.reply(Embeds.base("🪙 Pile ou face", "C'est… **${if (Random.nextBoolean()) "Pile" else "Face"}** !"))
        },
        Command("de", "Lance un dé", options = listOf(intOpt("faces", "Nombre de faces (6 par défaut)", required = false, min = 2, max = 1000))) { i ->
            val faces = i.long("faces")?.toInt() ?: 6
            i.reply(Embeds.base("🎲 Lancer de dé", "Dé à $faces faces : **${Random.nextInt(1, faces + 1)}**"))
        },
        Command("choisir", "Le bot choisit pour toi", options = listOf(stringOpt("options", "Choix séparés par des virgules : pizza, sushi, burger"))) { i ->
            val choices = i.string("options").orEmpty().split(',', '|', ';').map { it.trim() }.filter { it.isNotEmpty() }
            if (choices.size < 2) return@Command i.error("Donne au moins 2 choix séparés par des virgules.")
            i.reply(Embeds.base("🤔 Je choisis…", "**${choices.random()}**"))
        },
        Command(
            "pfc", "Pierre, feuille, ciseaux contre le bot",
            options = listOf(stringOpt("choix", "Ton choix", choices = listOf("🪨 Pierre" to "pierre", "📄 Feuille" to "feuille", "✂️ Ciseaux" to "ciseaux"))),
        ) { i ->
            val names = mapOf("pierre" to "🪨 Pierre", "feuille" to "📄 Feuille", "ciseaux" to "✂️ Ciseaux")
            val player = i.string("choix") ?: "pierre"
            val bot = names.keys.random()
            val beats = mapOf("pierre" to "ciseaux", "feuille" to "pierre", "ciseaux" to "feuille")
            val result = when {
                player == bot -> "Égalité ! 🤝"
                beats[player] == bot -> "Tu as gagné ! 🎉"
                else -> "J'ai gagné ! 😎"
            }
            i.reply(Embeds.base("✊ Pierre, feuille, ciseaux", "Toi : **${names[player]}**\nMoi : **${names[bot]}**\n\n$result"))
        },
        Command("blague", "Une blague au hasard") { i ->
            val (question, answer) = jokes.random().split('|')
            i.reply(Embeds.base("😂 Blague", "$question\n\n||$answer||"))
        },
    )
}
