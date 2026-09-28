package com.bothostinger.app.bot.modules

import com.bothostinger.app.bot.engine.Module
import com.bothostinger.app.data.Studio

/**
 * Tous les systèmes du bot, dans l'ordre d'affichage (et de traitement des
 * évènements : l'AutoMod passe avant les niveaux, par exemple).
 */
object Modules {
    fun all(studio: Studio = Studio()): List<Module> = listOf(
        GeneralModule(),
        StudioModule(studio),
        ModerationModule(),
        AutoModModule(),
        LevelsModule(),
        EconomyModule(),
        CasinoModule(),
        GiveawayModule(),
        TicketModule(),
        WelcomeModule(),
        LogsModule(),
        SuggestionModule(),
        RolesModule(),
        StarboardModule(),
        CountingModule(),
        TempVoiceModule(),
        BirthdayModule(),
        ServerStatsModule(),
        FunModule(),
        UtilityModule(),
    )
}
