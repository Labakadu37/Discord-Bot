package com.bothostinger.app.bot

import com.bothostinger.app.data.BotSettings
import org.json.JSONArray
import org.json.JSONObject

/** Statut affiché par tous les bots hébergés avec l'app. */
object Presence {
    const val NAME = "BotHostinger"

    /** Discord n'affiche le statut « Stream » violet qu'avec un lien Twitch ou YouTube. */
    const val STREAM_URL = "https://www.twitch.tv/bothostinger"

    fun json(settings: BotSettings): JSONObject {
        val activity = if (settings.presenceMode == "streaming") {
            JSONObject().put("type", 1).put("name", NAME).put("url", STREAM_URL)
        } else {
            JSONObject().put("type", 0).put("name", NAME)
        }
        return JSONObject()
            .put("since", JSONObject.NULL)
            .put("afk", false)
            .put("status", settings.status)
            .put("activities", JSONArray().put(activity))
    }
}
