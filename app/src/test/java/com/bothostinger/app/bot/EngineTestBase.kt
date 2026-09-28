package com.bothostinger.app.bot

import com.bothostinger.app.bot.engine.BotContext
import com.bothostinger.app.bot.engine.BotEngine
import com.bothostinger.app.bot.engine.Perm
import com.bothostinger.app.bot.modules.Modules
import com.bothostinger.app.data.BotDatabase
import com.bothostinger.app.data.Studio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

/** Outils communs : un moteur de bot complet branché sur une fausse API Discord. */
abstract class EngineTestBase {
    @get:Rule val tmp = TemporaryFolder()

    protected lateinit var fake: FakeDiscord
    protected lateinit var scope: CoroutineScope
    protected lateinit var ctx: BotContext
    protected lateinit var engine: BotEngine
    protected var n = 0

    protected fun boot(disabled: Set<String> = emptySet(), studio: Studio = Studio()) {
        fake = FakeDiscord()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        ctx = BotContext(
            rest = DiscordRest(OkHttpClient(), "tok", fake.apiBase),
            db = BotDatabase(tmp.newFolder()),
            scope = scope,
            enabledModules = Modules.all().map { it.id }.toSet() - disabled,
        )
        engine = BotEngine(ctx, Modules.all(studio))
        ctx.botId = "bot"
        engine.onDispatch("GUILD_CREATE", JSONObject("""{"id":"g1","name":"Serveur Test","owner_id":"owner","member_count":10,"roles":[],"channels":[]}"""))
    }

    @Before
    open fun setUp() = boot()

    @After
    open fun tearDown() {
        scope.cancel()
        fake.shutdown()
    }

    // ---------------------------------------------------------------- aides

    protected fun user(id: String, name: String = id) = JSONObject().put("id", id).put("username", name)

    protected fun command(
        name: String,
        sub: String? = null,
        options: Map<String, Any> = emptyMap(),
        users: List<JSONObject> = emptyList(),
        roles: List<JSONObject> = emptyList(),
        by: String = "u1",
        permissions: Long = Perm.ADMIN,
        memberRoles: List<String> = emptyList(),
        channel: String = "c1",
    ): JSONObject {
        n++
        var opts = JSONArray(options.map { (k, v) -> JSONObject().put("name", k).put("type", 3).put("value", v) })
        if (sub != null) opts = JSONArray().put(JSONObject().put("type", 1).put("name", sub).put("options", opts))
        val resolved = JSONObject()
            .put("users", JSONObject().apply { users.forEach { put(it.getString("id"), it) } })
            .put("members", JSONObject().apply { users.forEach { put(it.getString("id"), JSONObject().put("roles", JSONArray())) } })
            .put("roles", JSONObject().apply { roles.forEach { put(it.getString("id"), it) } })
        return interaction(2, JSONObject().put("name", name).put("options", opts).put("resolved", resolved), by, permissions, memberRoles, channel)
    }

    protected fun click(customId: String, messageId: String, by: String = "u1", permissions: Long = 0, memberRoles: List<String> = emptyList(), channel: String = "c1", message: JSONObject? = null): JSONObject {
        n++
        return interaction(3, JSONObject().put("custom_id", customId), by, permissions, memberRoles, channel)
            .put("message", (message ?: JSONObject()).put("id", messageId))
    }

    protected fun interaction(type: Int, data: JSONObject, by: String, permissions: Long, memberRoles: List<String>, channel: String) =
        JSONObject()
            .put("id", "i$n").put("token", "t$n").put("type", type).put("application_id", "app")
            .put("guild_id", "g1").put("channel_id", channel)
            .put("member", JSONObject().put("user", user(by, "membre-$by")).put("permissions", permissions.toString()).put("roles", JSONArray(memberRoles)))
            .put("data", data)

    protected fun send(json: JSONObject) = engine.onDispatch("INTERACTION_CREATE", json)

    /** Réponse directe (callback) à l'interaction courante. */
    protected fun callback(): JSONObject = fake.await("POST", "/interactions/i$n/t$n/callback").json

    protected fun snowflake(ms: Long) = ((ms - 1420070400000L) shl 22).toString()

    protected fun FakeDiscord.Call.embed(): JSONObject =
        (json.optJSONObject("data") ?: json).getJSONArray("embeds").getJSONObject(0)
}
