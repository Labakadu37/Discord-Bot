package com.bothostinger.app.bot.modules

import com.bothostinger.app.bot.engine.Module

/** Tous les systèmes du bot, dans l'ordre d'affichage. Le même bot pour tout le monde. */
object Modules {
    fun all(): List<Module> = listOf(
        GeneralModule(),
        ModerationModule(),
        LevelsModule(),
        EconomyModule(),
        GiveawayModule(),
        TicketModule(),
        WelcomeModule(),
        LogsModule(),
        SuggestionModule(),
        RolesModule(),
        FunModule(),
        UtilityModule(),
    )
}
