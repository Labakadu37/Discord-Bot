package com.monimage.launcher

import java.util.Calendar
import kotlin.random.Random

/** Choisit le message d'accueil selon l'heure, le jour et le temps passé loin du téléphone. */
object Greetings {

    enum class Slot { MORNING, NOON, AFTERNOON, EVENING, NIGHT }

    /** Une personne ne redit pas bonjour si on a juste regardé l'heure : il faut être parti un moment. */
    const val MIN_AWAY_MS = 30 * 60 * 1000L

    fun slotOf(hour: Int): Slot = when (hour) {
        in 5..10 -> Slot.MORNING
        in 11..13 -> Slot.NOON
        in 14..17 -> Slot.AFTERNOON
        in 18..21 -> Slot.EVENING
        else -> Slot.NIGHT
    }

    /** Identifiant « jour + moment » pour savoir si on a déjà salué dans ce créneau. */
    fun slotKey(cal: Calendar): String =
        "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.DAY_OF_YEAR)}-${slotOf(cal.get(Calendar.HOUR_OF_DAY))}"

    /**
     * Faut-il saluer ? Oui au premier déverrouillage de chaque moment de la journée,
     * ou si le téléphone est resté verrouillé au moins [MIN_AWAY_MS].
     */
    fun shouldGreet(now: Calendar, lastSlotKey: String?, awayMs: Long?): Boolean =
        slotKey(now) != lastSlotKey || (awayMs != null && awayMs >= MIN_AWAY_MS)

    /** [firstOfSlot] : premier bonjour de ce moment de la journée (sinon c'est un « re »). */
    fun message(now: Calendar, firstOfSlot: Boolean, batteryPercent: Int?, random: Random = Random): String {
        val day = now.get(Calendar.DAY_OF_WEEK)
        val weekend = day == Calendar.SATURDAY || day == Calendar.SUNDAY
        val pool = when (slotOf(now.get(Calendar.HOUR_OF_DAY))) {
            Greetings.Slot.MORNING -> if (firstOfSlot) listOf(
                "Bonjour ! Comment s'est passée ta nuit ?",
                "Bien dormi ? Bonjour à toi !",
                "Debout ! Bonjour, j'espère que tu as bien dormi.",
                "Bonjour ! Prêt pour la journée ?",
            ) else listOf(
                "Re ! Ça va ce matin ?",
                "Te revoilà ! La matinée se passe bien ?",
            )
            Greetings.Slot.NOON -> if (firstOfSlot) listOf(
                "Salut ! C'est l'heure de manger, bon appétit !",
                "Hey ! La matinée s'est bien passée ?",
                "Coucou ! Tu as mangé quoi ce midi ?",
            ) else listOf(
                "Re ! Bon appétit si tu manges encore.",
                "Te revoilà ! Ça va ce midi ?",
            )
            Greetings.Slot.AFTERNOON -> when {
                weekend -> listOf(
                    "Bon après-midi ! Tu profites bien du week-end ?",
                    "Salut ! Il se passe quoi cet après-midi ?",
                )
                firstOfSlot -> listOf(
                    "Te revoilà ! Comment s'est passée ta journée à l'école ?",
                    "Salut ! Alors, l'école, c'était comment aujourd'hui ?",
                    "De retour ! Pas trop dur les cours aujourd'hui ?",
                    "Hey ! Bien rentré ? Raconte, c'était comment ?",
                )
                else -> listOf(
                    "Re ! Tout va bien ?",
                    "Te revoilà ! Pas trop de devoirs ?",
                )
            }
            Greetings.Slot.EVENING -> if (firstOfSlot) listOf(
                "Bonsoir ! Comment s'est passée ta journée ?",
                "Bonsoir ! Tu as passé une bonne journée ?",
                "Salut ! Bonne soirée à toi.",
            ) else listOf(
                "Re ! Bonne soirée.",
                "Te revoilà ! La soirée se passe bien ?",
            )
            Greetings.Slot.NIGHT -> listOf(
                "Il est tard... Pense à dormir un peu.",
                "Encore debout ? N'oublie pas de te reposer.",
                "Bonne nuit bientôt ? Demain il faut être en forme.",
            )
        }
        val battery = if (batteryPercent != null && batteryPercent <= 20) {
            " Au fait, il te reste $batteryPercent % de batterie, pense à charger."
        } else ""
        return pool.random(random) + battery
    }
}
