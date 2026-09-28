package com.bothostinger.app.bot.modules

import com.bothostinger.app.bot.engine.Command
import com.bothostinger.app.bot.engine.Embeds
import com.bothostinger.app.bot.engine.Interaction
import com.bothostinger.app.bot.engine.Module
import com.bothostinger.app.bot.engine.button
import com.bothostinger.app.bot.engine.displayName
import com.bothostinger.app.bot.engine.progressBar
import com.bothostinger.app.bot.engine.rows
import com.bothostinger.app.bot.engine.userOpt
import com.bothostinger.app.data.obj
import com.bothostinger.app.bot.engine.field
import com.bothostinger.app.bot.engine.intOpt
import com.bothostinger.app.bot.engine.stringOpt
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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

    /** Question, bonne réponse, puis 3 mauvaises réponses. */
    private val questions = listOf(
        listOf("Quelle est la capitale de l'Australie ?", "Canberra", "Sydney", "Melbourne", "Perth"),
        listOf("Combien de joueurs dans une équipe de football sur le terrain ?", "11", "10", "12", "9"),
        listOf("Quel est le plus grand océan ?", "Pacifique", "Atlantique", "Indien", "Arctique"),
        listOf("Qui a peint la Joconde ?", "Léonard de Vinci", "Michel-Ange", "Raphaël", "Picasso"),
        listOf("Quelle planète est surnommée la planète rouge ?", "Mars", "Vénus", "Jupiter", "Mercure"),
        listOf("En quelle année a eu lieu la prise de la Bastille ?", "1789", "1799", "1776", "1815"),
        listOf("Quel est le symbole chimique de l'or ?", "Au", "Or", "Ag", "Go"),
        listOf("Combien de côtés a un hexagone ?", "6", "5", "7", "8"),
        listOf("Quel animal est le plus rapide sur terre ?", "Guépard", "Lion", "Antilope", "Lévrier"),
        listOf("Quelle est la langue la plus parlée au Brésil ?", "Portugais", "Espagnol", "Brésilien", "Anglais"),
        listOf("Combien font 7 × 8 ?", "56", "54", "64", "48"),
        listOf("Quel pays a la forme d'une botte ?", "Italie", "Espagne", "Grèce", "Portugal"),
        listOf("Quel est l'os le plus long du corps humain ?", "Fémur", "Tibia", "Humérus", "Péroné"),
        listOf("Dans quel jeu construit-on avec des blocs dans un monde ouvert ?", "Minecraft", "Fortnite", "Among Us", "Rocket League"),
        listOf("Quelle est la monnaie du Japon ?", "Yen", "Won", "Yuan", "Roupie"),
        listOf("Combien de continents y a-t-il ?", "7", "5", "6", "8"),
        listOf("Quel gaz les plantes absorbent-elles ?", "Dioxyde de carbone", "Oxygène", "Azote", "Hélium"),
        listOf("Qui a écrit « Les Misérables » ?", "Victor Hugo", "Émile Zola", "Molière", "Albert Camus"),
        listOf("Quelle est la plus haute montagne du monde ?", "Everest", "K2", "Mont Blanc", "Kilimandjaro"),
        listOf("Combien de minutes dans une journée ?", "1 440", "1 200", "2 400", "1 080"),
        listOf("Quel est le plus petit pays du monde ?", "Vatican", "Monaco", "Malte", "Andorre"),
        listOf("Combien de pattes a une araignée ?", "8", "6", "10", "12"),
        listOf("Quelle couleur obtient-on en mélangeant bleu et jaune ?", "Vert", "Violet", "Orange", "Marron"),
        listOf("Quel est le métal liquide à température ambiante ?", "Mercure", "Plomb", "Étain", "Zinc"),
        listOf("En informatique, que signifie « IA » ?", "Intelligence artificielle", "Internet avancé", "Interface automatique", "Information analogique"),
    )

    private class Quiz(val userId: String, val correct: Int, val answers: List<String>, val question: String, val at: Long = System.currentTimeMillis())
    private val quizzes = ConcurrentHashMap<String, Quiz>()

    override val commands = listOf(
        Command("quiz", "Question de culture générale (bonne réponse = 50 pièces)") { i ->
            val q = questions.random()
            val answers = q.drop(1).shuffled()
            val id = UUID.randomUUID().toString().take(8)
            quizzes[id] = Quiz(i.userId, answers.indexOf(q[1]), answers, q[0])
            i.reply(
                Embeds.base("🧠 Quiz", "**${q[0]}**\n\nTu as 30 secondes !"),
                components = rows(answers.mapIndexed { n, a -> button("qz:$id:$n", a, 2) }),
            )
        },
        Command(
            "ship", "Compatibilité amoureuse entre deux membres",
            options = listOf(userOpt("membre1", "Premier membre"), userOpt("membre2", "Deuxième membre", required = false)),
        ) { i ->
            val a = i.user("membre1") ?: return@Command i.error("Membre introuvable.")
            val b = i.user("membre2") ?: i.user
            // Toujours le même score pour le même duo, dans n'importe quel ordre.
            val ids = listOf(a.optString("id"), b.optString("id")).sorted()
            val score = ((ids.joinToString("+").hashCode().toLong() and 0x7fffffff) % 101).toInt()
            val verdict = when {
                score >= 90 -> "Âmes sœurs ! 💞"
                score >= 70 -> "Très belle alchimie 💕"
                score >= 50 -> "Ça peut marcher 😊"
                score >= 25 -> "Amitié d'abord 🤝"
                else -> "Mieux vaut rester amis… 😅"
            }
            i.reply(
                Embeds.base(
                    "💘 ${displayName(a)} × ${displayName(b)}",
                    "${progressBar(score / 100.0, 14)} **$score %**\n$verdict",
                )
            )
        },
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

    override fun onComponent(i: Interaction): Boolean {
        if (!i.customId.startsWith("qz:")) return false
        val parts = i.customId.split(':')
        val quiz = quizzes[parts.getOrNull(1)]
        if (quiz == null) {
            i.error("Ce quiz est terminé.")
            return true
        }
        if (quiz.userId != i.userId) {
            i.error("Ce n'est pas ton quiz ! Lance le tien avec /quiz.")
            return true
        }
        quizzes.remove(parts[1])
        val choice = parts.getOrNull(2)?.toIntOrNull() ?: -1
        val late = System.currentTimeMillis() - quiz.at > 30_000
        val good = choice == quiz.correct && !late
        val gid = i.guildId
        if (good && gid != null && "economie" in ctx.enabledModules) {
            db.edit(gid) { g -> g.obj("eco").obj(i.userId).let { it.put("bal", it.optLong("bal") + 50) } }
        }
        val text = when {
            late -> "⏱️ Trop tard ! La réponse était **${quiz.answers[quiz.correct]}**."
            good -> "✅ Bonne réponse : **${quiz.answers[quiz.correct]}** !" + if ("economie" in ctx.enabledModules) " +50 🪙" else ""
            else -> "❌ Raté ! La réponse était **${quiz.answers[quiz.correct]}**."
        }
        val buttons = quiz.answers.mapIndexed { n, a ->
            button("qz:done:$n", a, if (n == quiz.correct) 3 else if (n == choice) 4 else 2, disabled = true)
        }
        i.updateMessage(Embeds.base("🧠 Quiz", "**${quiz.question}**\n\n$text", if (good) Embeds.GREEN else Embeds.RED), rows(buttons))
        return true
    }

    override fun tick(now: Long) {
        quizzes.entries.removeIf { now - it.value.at > 5 * 60_000 }
    }
}
