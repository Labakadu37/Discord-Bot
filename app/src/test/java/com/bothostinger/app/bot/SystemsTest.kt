package com.bothostinger.app.bot

import com.bothostinger.app.bot.engine.BotContext
import com.bothostinger.app.bot.engine.BotEngine
import com.bothostinger.app.bot.engine.Perm
import com.bothostinger.app.bot.engine.parseDuration
import com.bothostinger.app.bot.modules.LevelsModule
import com.bothostinger.app.bot.modules.Modules
import com.bothostinger.app.data.BotDatabase
import com.bothostinger.app.data.obj
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

/** Chaque système du bot, piloté par de vrais évènements Discord, contre une fausse API. */
class SystemsTest : EngineTestBase() {

    // ---------------------------------------------------------------- commandes

    @Test
    fun allCommandDefinitionsAreAcceptedByDiscordRules() {
        val cmds = engine.commandsJson()
        assertTrue("au moins 40 commandes, ${cmds.length()} trouvées", cmds.length() >= 40)
        val names = mutableSetOf<String>()
        val nameRule = Regex("^[-_a-z0-9]{1,32}$")
        fun checkOptions(where: String, options: JSONArray?) {
            if (options == null) return
            assertTrue("$where : 25 options max", options.length() <= 25)
            var seenOptional = false
            val optNames = mutableSetOf<String>()
            for (k in 0 until options.length()) {
                val o = options.getJSONObject(k)
                val name = o.getString("name")
                assertTrue("$where : nom d'option invalide '$name'", nameRule.matches(name))
                assertTrue("$where : option en double '$name'", optNames.add(name))
                assertTrue("$where/$name : description", o.getString("description").length in 1..100)
                if (o.getInt("type") == 1) {
                    checkOptions("$where $name", o.optJSONArray("options"))
                } else {
                    val required = o.optBoolean("required")
                    assertFalse("$where : '$name' obligatoire après une option facultative", required && seenOptional)
                    if (!required) seenOptional = true
                }
            }
        }
        for (k in 0 until cmds.length()) {
            val c = cmds.getJSONObject(k)
            val name = c.getString("name")
            assertTrue("nom invalide '$name'", nameRule.matches(name))
            assertTrue("commande en double '$name'", names.add(name))
            assertTrue("$name : description", c.getString("description").length in 1..100)
            checkOptions("/$name", c.optJSONArray("options"))
        }
    }

    @Test
    fun readyRegistersEveryCommand() {
        engine.onReady(JSONObject("""{"session_id":"s","user":{"id":"bot","username":"MonBot"},"application":{"id":"app"},"guilds":[]}"""))
        val call = fake.await("PUT", "/applications/app/commands")
        assertEquals(engine.commandCount, JSONArray(call.body).length())
    }

    @Test
    fun ping() {
        send(command("ping"))
        val cb = callback()
        assertEquals(4, cb.getInt("type"))
        assertTrue(cb.getJSONObject("data").getJSONArray("embeds").getJSONObject(0).getString("title").contains("Pong"))
    }

    @Test
    fun disabledSystemDoesNotAnswer() {
        tearDown()
        boot(disabled = setOf("economie"))
        assertFalse(engine.commandsJson().toString().contains("\"daily\""))
        send(command("daily"))
        val cb = callback()
        assertEquals(64, cb.getJSONObject("data").getInt("flags"))
    }

    // ---------------------------------------------------------------- modération

    @Test
    fun banSendsDmThenBansWithAuditReason() {
        send(command("ban", options = mapOf("membre" to "u2", "raison" to "spam"), users = listOf(user("u2", "victime"))))
        fake.await("POST", "/channels/dm1/messages")
        val ban = fake.await("PUT", "/guilds/g1/bans/u2")
        assertTrue(ban.reason!!.contains("spam"))
        assertTrue(callback().getJSONObject("data").getJSONArray("embeds").getJSONObject(0).getString("description").contains("victime"))
    }

    @Test
    fun cannotBanYourselfOrTheOwner() {
        send(command("ban", options = mapOf("membre" to "u1"), users = listOf(user("u1"))))
        assertEquals(64, callback().getJSONObject("data").getInt("flags"))
        send(command("ban", options = mapOf("membre" to "owner"), users = listOf(user("owner"))))
        assertEquals(64, callback().getJSONObject("data").getInt("flags"))
        fake.assertNoMoreCalls()
    }

    @Test
    fun muteUsesDiscordTimeout() {
        send(command("mute", options = mapOf("membre" to "u2", "duree" to "1h30m"), users = listOf(user("u2"))))
        val patch = fake.await("PATCH", "/guilds/g1/members/u2")
        val until = Instant.parse(patch.json.getString("communication_disabled_until")).toEpochMilli()
        val expected = System.currentTimeMillis() + 90 * 60_000
        assertTrue(kotlin.math.abs(until - expected) < 10_000)
        send(command("mute", options = mapOf("membre" to "u2", "duree" to "n'importe quoi"), users = listOf(user("u2"))))
        assertEquals(64, callback().getJSONObject("data").getInt("flags"))
    }

    @Test
    fun clearBulkDeletesRecentMessagesOfOneMember() {
        val now = System.currentTimeMillis()
        fake.channelMessages = JSONArray()
            .put(JSONObject().put("id", snowflake(now - 1000)).put("author", user("u2")))
            .put(JSONObject().put("id", snowflake(now - 2000)).put("author", user("u3")))
            .put(JSONObject().put("id", snowflake(now - 3000)).put("author", user("u2")))
            .put(JSONObject().put("id", snowflake(now - 30L * 86_400_000)).put("author", user("u2"))) // trop vieux
        send(command("clear", options = mapOf("nombre" to 10, "membre" to "u2"), users = listOf(user("u2"))))
        assertEquals(5, callback().getInt("type")) // réponse différée
        val bulk = fake.await("POST", "/channels/c1/messages/bulk-delete")
        assertEquals(2, bulk.json.getJSONArray("messages").length())
        val done = fake.await("PATCH", "/webhooks/app/t$n/messages/@original")
        assertTrue(done.embed().getString("description").contains("2"))
    }

    @Test
    fun warnsAreCounted() {
        repeat(2) {
            send(command("warn", options = mapOf("membre" to "u2", "raison" to "insulte $it"), users = listOf(user("u2"))))
            callback()
        }
        send(command("warns", options = mapOf("membre" to "u2"), users = listOf(user("u2"))))
        val list = callback().getJSONObject("data").getJSONArray("embeds").getJSONObject(0)
        assertTrue(list.getString("title").contains("(2)"))
        assertTrue(list.getString("description").contains("insulte 1"))
    }

    @Test
    fun lockDeniesSendMessagesForEveryone() {
        send(command("lock"))
        val put = fake.await("PUT", "/channels/c1/permissions/g1")
        assertEquals(Perm.SEND, put.json.getString("deny").toLong() and Perm.SEND)
    }

    // ---------------------------------------------------------------- niveaux

    private fun chat(from: String = "u1") = engine.onDispatch(
        "MESSAGE_CREATE",
        JSONObject().put("id", "m${n++}").put("guild_id", "g1").put("channel_id", "c1").put("author", user(from)).put("content", ""),
    )

    @Test
    fun messagesGiveXpAndLevelUpGrantsRewardRole() {
        ctx.db.edit("g1") { g ->
            g.obj("levels").obj("u1").put("xp", LevelsModule.xpForNext(0) - 5).put("level", 0)
            g.obj("config").obj("levels").obj("rewards").put("1", "r1")
        }
        chat()
        fake.await("PUT", "/guilds/g1/members/u1/roles/r1")
        val announce = fake.await("POST", "/channels/c1/messages")
        assertTrue(announce.embed().getString("description").contains("niveau 1"))
        val level = ctx.db.read("g1") { it.getJSONObject("levels").getJSONObject("u1").getInt("level") }
        assertEquals(1, level)
        // Anti-spam : un 2e message dans la minute ne rapporte rien.
        val total = ctx.db.read("g1") { it.getJSONObject("levels").getJSONObject("u1").getLong("total") }
        chat()
        Thread.sleep(300)
        assertEquals(total, ctx.db.read("g1") { it.getJSONObject("levels").getJSONObject("u1").getLong("total") })
    }

    @Test
    fun botsDoNotEarnXp() {
        engine.onDispatch(
            "MESSAGE_CREATE",
            JSONObject().put("id", "m").put("guild_id", "g1").put("channel_id", "c1").put("author", user("b").put("bot", true)),
        )
        Thread.sleep(300)
        assertNull(ctx.db.read("g1") { it.optJSONObject("levels")?.optJSONObject("b") })
    }

    // ---------------------------------------------------------------- économie

    @Test
    fun dailyOncePerDayAndPayNeedsFunds() {
        send(command("daily"))
        assertFalse(callback().getJSONObject("data").has("flags"))
        send(command("daily"))
        assertEquals(64, callback().getJSONObject("data").getInt("flags"))
        val bal = ctx.db.read("g1") { it.getJSONObject("eco").getJSONObject("u1").getLong("bal") }
        assertTrue(bal >= 230)

        send(command("payer", options = mapOf("membre" to "u2", "montant" to bal + 1), users = listOf(user("u2"))))
        assertEquals(64, callback().getJSONObject("data").getInt("flags"))
        send(command("payer", options = mapOf("membre" to "u2", "montant" to 100), users = listOf(user("u2"))))
        callback()
        assertEquals(100L, ctx.db.read("g1") { it.getJSONObject("eco").getJSONObject("u2").getLong("bal") })
        assertEquals(bal - 100, ctx.db.read("g1") { it.getJSONObject("eco").getJSONObject("u1").getLong("bal") })
    }

    // ---------------------------------------------------------------- giveaways

    @Test
    fun giveawayFullCycle() {
        send(command("giveaway", "lancer", mapOf("duree" to "1h", "lot" to "Nitro")))
        val posted = fake.await("POST", "/channels/c1/messages")
        assertTrue(posted.embed().getString("description").contains("Nitro"))
        callback()
        val msgId = ctx.db.read("g1") { it.getJSONObject("giveaways").keys().next() }

        send(click("gw:join", msgId, by = "u7"))
        assertEquals(64, callback().getJSONObject("data").getInt("flags"))
        val refreshed = fake.await("PATCH", "/channels/c1/messages/$msgId")
        assertTrue(refreshed.body.contains("Participer (1)"))

        ctx.db.edit("g1") { it.getJSONObject("giveaways").getJSONObject(msgId).put("endsAt", 0) }
        ctx.modules.first { it.id == "giveaways" }.tick(System.currentTimeMillis())
        val ended = fake.await("PATCH", "/channels/c1/messages/$msgId")
        assertTrue(ended.body.contains("TERMINÉ"))
        val winner = fake.await("POST", "/channels/c1/messages")
        assertTrue(winner.json.getString("content").contains("<@u7>"))
    }

    // ---------------------------------------------------------------- tickets

    @Test
    fun ticketOpenIsPrivateAndCanBeClosed() {
        send(command("ticket-panel", options = mapOf("role_support" to "staff")))
        fake.await("POST", "/channels/c1/messages")
        callback()

        send(click("tk:open", "panel", by = "u5"))
        assertEquals(5, callback().getInt("type"))
        val created = fake.await("POST", "/guilds/g1/channels")
        val overwrites = created.json.getJSONArray("permission_overwrites")
        val everyone = (0 until overwrites.length()).map { overwrites.getJSONObject(it) }.first { it.getString("id") == "g1" }
        assertEquals(Perm.VIEW, everyone.getString("deny").toLong())
        val ids = (0 until overwrites.length()).map { overwrites.getJSONObject(it).getString("id") }
        assertTrue(ids.containsAll(listOf("u5", "bot", "staff")))
        // Le bot enregistre le ticket puis poste le message d'accueil dans le nouveau salon.
        val welcome = fake.await("POST", "/channels/\\d+/messages")
        val ticketChannel = ctx.db.read("g1") { it.getJSONObject("tickets").getJSONObject("open").getString("u5") }
        assertEquals("/channels/$ticketChannel/messages", welcome.path)
        fake.await("PATCH", "/webhooks/app/t$n/messages/@original")

        send(click("tk:open", "panel", by = "u5"))
        fake.await("GET", "/channels/$ticketChannel")
        assertEquals(64, callback().getJSONObject("data").getInt("flags")) // déjà un ticket

        send(click("tk:close", "m", by = "u9", channel = ticketChannel))
        assertEquals(64, callback().getJSONObject("data").getInt("flags")) // pas le droit

        send(click("tk:close", "m", by = "u5", channel = ticketChannel))
        assertEquals(4, callback().getInt("type"))
        fake.await("DELETE", "/channels/$ticketChannel", seconds = 10) // supprimé après 5 s
    }

    // ---------------------------------------------------------------- bienvenue

    @Test
    fun welcomeMessageAndAutorole() {
        send(command("bienvenue", "activer", mapOf("salon" to "c9")))
        callback()
        send(command("autorole", "definir", mapOf("role" to "r5"), roles = listOf(JSONObject().put("id", "r5").put("name", "Membre"))))
        callback()
        engine.onDispatch("GUILD_MEMBER_ADD", JSONObject().put("guild_id", "g1").put("user", user("u8", "nouveau")))
        fake.await("PUT", "/guilds/g1/members/u8/roles/r5")
        val welcome = fake.await("POST", "/channels/c9/messages")
        val text = welcome.embed().getString("description")
        assertTrue(text, text.contains("<@u8>") && text.contains("Serveur Test") && text.contains("11"))
    }

    // ---------------------------------------------------------------- suggestions, rôles, sondages

    @Test
    fun suggestionsNeedStaffToDecide() {
        send(command("suggestion", options = mapOf("idee" to "Un salon memes")))
        assertEquals(64, callback().getJSONObject("data").getInt("flags")) // pas de salon configuré
        send(command("suggestions-salon", options = mapOf("salon" to "c4")))
        callback()
        send(command("suggestion", options = mapOf("idee" to "Un salon memes")))
        val post = fake.await("POST", "/channels/c4/messages")
        assertTrue(post.embed().getString("title").contains("#1"))
        fake.await("PUT", "/channels/c4/messages/.+/reactions/.+/@me")

        val msg = JSONObject().put("embeds", JSONArray().put(post.embed()))
        send(click("sg:ok", "s1", permissions = 0, message = msg))
        assertEquals(64, callback().getJSONObject("data").getInt("flags"))
        send(click("sg:ok", "s1", permissions = Perm.MANAGE_GUILD, message = msg))
        val update = callback()
        assertEquals(7, update.getInt("type"))
        assertTrue(update.toString().contains("Acceptée"))
    }

    @Test
    fun roleButtonsToggle() {
        send(click("rr:r7", "m1"))
        fake.await("PUT", "/guilds/g1/members/u1/roles/r7")
        send(click("rr:r7", "m1", memberRoles = listOf("r7")))
        fake.await("DELETE", "/guilds/g1/members/u1/roles/r7")
    }

    @Test
    fun pollUsesNativeDiscordPolls() {
        send(command("sondage", options = mapOf("question" to "Pizza ?", "reponses" to "Oui | Non | Peut-être")))
        val poll = callback().getJSONObject("data").getJSONObject("poll")
        assertEquals(3, poll.getJSONArray("answers").length())
        assertEquals(24, poll.getInt("duration"))
    }

    @Test
    fun remindersAreDelivered() {
        send(command("rappel", options = mapOf("dans" to "10m", "message" to "sortir le chien")))
        callback()
        ctx.db.edit("g1") { it.getJSONArray("reminders").getJSONObject(0).put("at", 0) }
        ctx.modules.first { it.id == "utilitaire" }.tick(System.currentTimeMillis())
        val sent = fake.await("POST", "/channels/c1/messages")
        assertTrue(sent.embed().getString("description").contains("sortir le chien"))
        assertEquals(0, ctx.db.read("g1") { it.getJSONArray("reminders").length() })
    }

    // ---------------------------------------------------------------- persistance et utilitaires

    @Test
    fun databaseSurvivesRestart() {
        val dir = tmp.newFolder()
        BotDatabase(dir).apply {
            edit("g9") { it.put("hello", "monde") }
            flush()
        }
        assertEquals("monde", BotDatabase(dir).read("g9") { it.getString("hello") })
    }

    @Test
    fun durations() {
        assertEquals(600_000L, parseDuration("10m"))
        assertEquals(5_400_000L, parseDuration("1h30m"))
        assertEquals(5_400_000L, parseDuration("1h 30min"))
        assertEquals(172_800_000L, parseDuration("2j"))
        assertEquals(604_800_000L, parseDuration("1w"))
        assertEquals(45_000L, parseDuration("45s"))
        assertNull(parseDuration("10"))
        assertNull(parseDuration("bientôt"))
        assertNull(parseDuration("10m abc"))
        assertNotNull(parseDuration("1H"))
    }
}
