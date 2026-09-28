package com.bothostinger.app.bot

import com.bothostinger.app.bot.engine.Perm
import com.bothostinger.app.bot.engine.Template
import com.bothostinger.app.bot.engine.TemplateScope
import com.bothostinger.app.bot.modules.BirthdayModule
import com.bothostinger.app.bot.modules.Calculator
import com.bothostinger.app.data.ActionKind
import com.bothostinger.app.data.AutoResponse
import com.bothostinger.app.data.CustomAction
import com.bothostinger.app.data.CustomCommand
import com.bothostinger.app.data.CustomOption
import com.bothostinger.app.data.MatchMode
import com.bothostinger.app.data.OptionKind
import com.bothostinger.app.data.Studio
import com.bothostinger.app.data.obj
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId

/** Studio, AutoMod, comptage, starboard, vocaux, anniversaires, casino, utilitaires. */
class NewSystemsTest : EngineTestBase() {

    /** Serveur avec de vrais rôles et salons (pour les recherches par nom). */
    private fun guildWithRolesAndChannels() = engine.onDispatch(
        "GUILD_CREATE",
        JSONObject(
            """{"id":"g1","name":"Serveur Test","owner_id":"owner","member_count":10,
            "roles":[{"id":"g1","name":"@everyone","permissions":"0"},{"id":"r-vip","name":"VIP","permissions":"0"},
                     {"id":"r-mod","name":"Modo","permissions":"${Perm.MANAGE_MESSAGES}"}],
            "channels":[{"id":"c-annonces","name":"annonces","type":0},{"id":"c1","name":"general","type":0}]}"""
        ),
    )

    private fun chat(content: String, from: String = "u1", channel: String = "c1", roles: List<String> = emptyList(), extra: (JSONObject) -> Unit = {}) {
        n++
        val msg = JSONObject().put("id", "m$n").put("guild_id", "g1").put("channel_id", channel)
            .put("author", user(from)).put("content", content)
            .put("member", JSONObject().put("roles", JSONArray(roles)))
        extra(msg)
        engine.onDispatch("MESSAGE_CREATE", msg)
    }

    private fun data(): JSONObject = callback().getJSONObject("data")

    // ---------------------------------------------------------------- variables

    @Test
    fun templateVariables() {
        val scope = TemplateScope(
            user = user("42", "alice").put("global_name", "Alice"),
            guildId = "g1", channelId = "c1",
            options = mapOf("cible" to "<@7>"), ctx = ctx, uses = 3,
        )
        assertEquals(
            "Alice <@42> Serveur Test 10 <#c1> <@7> 3 {inconnu}",
            Template.render("{user} {user.mention} {server} {server.members} {channel} {option:cible} {uses} {inconnu}", scope),
        )
        repeat(50) {
            val r = Template.render("{random:5-7}", scope).toInt()
            assertTrue(r in 5..7)
            assertTrue(Template.render("{choose:a|b}", scope) in setOf("a", "b"))
        }
    }

    // ---------------------------------------------------------------- Studio

    @Test
    fun customCommandWithOptionConditionsAndActions() {
        tearDown()
        val cmd = CustomCommand(
            name = "vip", description = "Devenir VIP",
            options = listOf(CustomOption("ami", "Un ami", OptionKind.USER)),
            embedTitle = "Bienvenue au club {user}", embedDescription = "Parrainé par {option:ami}",
            cost = 100, cooldownSeconds = 60,
            actions = listOf(
                CustomAction(ActionKind.ADD_ROLE, target = "vip"), // par nom, sans majuscule
                CustomAction(ActionKind.SEND_CHANNEL, target = "#annonces", text = "{user} est VIP !"),
                CustomAction(ActionKind.ADD_COINS, amount = 20, member = "{option:ami}"),
            ),
        )
        boot(studio = Studio(commands = listOf(cmd)))
        guildWithRolesAndChannels()
        val registered = engine.commandsJson().toString()
        assertTrue(registered.contains("\"vip\""))

        // Pas assez de pièces.
        send(command("vip", options = mapOf("ami" to "222"), users = listOf(user("222"))))
        assertEquals(64, data().getInt("flags"))

        ctx.db.edit("g1") { it.obj("eco").obj("u1").put("bal", 150) }
        send(command("vip", options = mapOf("ami" to "222"), users = listOf(user("222"))))
        val embed = data().getJSONArray("embeds").getJSONObject(0)
        assertEquals("Bienvenue au club membre-u1", embed.getString("title"))
        assertEquals("Parrainé par <@222>", embed.getString("description"))
        fake.await("PUT", "/guilds/g1/members/u1/roles/r-vip")
        val announce = fake.await("POST", "/channels/c-annonces/messages")
        assertEquals("membre-u1 est VIP !", announce.json.getString("content"))
        Thread.sleep(200)
        assertEquals(50L, ctx.db.read("g1") { it.getJSONObject("eco").getJSONObject("u1").getLong("bal") })
        assertEquals(20L, ctx.db.read("g1") { it.getJSONObject("eco").getJSONObject("222").getLong("bal") })

        // Délai entre deux utilisations.
        send(command("vip", options = mapOf("ami" to "222"), users = listOf(user("222"))))
        assertTrue(data().getJSONArray("embeds").getJSONObject(0).getString("description").contains("Doucement"))
    }

    @Test
    fun customCommandRequiredRole() {
        tearDown()
        boot(studio = Studio(commands = listOf(CustomCommand(name = "secret", useEmbed = false, content = "chut", requiredRole = "VIP"))))
        guildWithRolesAndChannels()
        send(command("secret"))
        assertEquals(64, data().getInt("flags"))
        send(command("secret", memberRoles = listOf("r-vip")))
        assertEquals("chut", data().getString("content"))
    }

    @Test
    fun customCommandCannotReplaceBuiltInAndLimitIs100() {
        tearDown()
        val many = (1..150).map { CustomCommand(name = "c$it", useEmbed = false, content = "x") } + CustomCommand(name = "ban", content = "pirate")
        boot(studio = Studio(commands = many))
        val cmds = engine.commandsJson()
        assertEquals(100, cmds.length())
        val ban = (0 until cmds.length()).map { cmds.getJSONObject(it) }.first { it.getString("name") == "ban" }
        assertEquals("Bannir un membre", ban.getString("description"))
    }

    @Test
    fun autoResponses() {
        tearDown()
        boot(
            studio = Studio(
                responses = listOf(
                    AutoResponse(trigger = "salut", mode = MatchMode.CONTAINS, response = "Yo {user.mention} !"),
                    AutoResponse(trigger = "!dis", mode = MatchMode.STARTS, response = "{args}", reply = false),
                )
            )
        )
        assertTrue(engine.intents and DiscordGateway.INTENT_MESSAGE_CONTENT != 0)
        chat("Bon, salut tout le monde")
        val r = fake.await("POST", "/channels/c1/messages")
        assertEquals("Yo <@u1> !", r.json.getString("content"))
        assertTrue(r.json.has("message_reference"))
        chat("salutations") // « salut » n'est pas un mot entier ici
        chat("!dis bonjour à tous")
        val r2 = fake.await("POST", "/channels/c1/messages")
        assertEquals("bonjour à tous", r2.json.getString("content"))
        assertFalse(r2.json.has("message_reference"))
        chat("salut", channel = "c1") // anti-spam : même réponse < 3 s dans le salon
        fake.assertNoMoreCalls()
    }

    // ---------------------------------------------------------------- AutoMod

    @Test
    fun automodDeletesSpamButIgnoresStaff() {
        guildWithRolesAndChannels()
        repeat(6) { chat("message $it", from = "spammer") }
        fake.await("DELETE", "/channels/c1/messages/m.+")
        fake.await("PATCH", "/guilds/g1/members/spammer") // mute 1 min
        fake.calls.clear()

        repeat(8) { chat("modo $it", from = "u-mod", roles = listOf("r-mod")) }
        Thread.sleep(300)
        assertTrue(fake.calls.none { it.method == "DELETE" })
    }

    @Test
    fun automodFiltersAndBadWordsIgnoreAccents() {
        send(command("automod", "filtre", mapOf("filtre" to "invitations", "actif" to true)))
        callback()
        send(command("automod", "mots-ajouter", mapOf("mots" to "Énervé, truc")))
        callback()
        send(command("automod", "sanction", mapOf("type" to "warn")))
        callback()

        chat("rejoins discord.gg/abcdef")
        fake.await("DELETE", "/channels/c1/messages/m$n")
        chat("je suis ENERVE")
        fake.await("DELETE", "/channels/c1/messages/m$n")
        Thread.sleep(200)
        assertEquals(2, ctx.db.read("g1") { it.getJSONObject("warns").getJSONArray("u1").length() })

        fake.calls.clear()
        chat("trucage n'est pas interdit") // mot entier uniquement
        chat("un lien https://example.com") // filtre liens désactivé par défaut
        Thread.sleep(300)
        assertTrue(fake.calls.none { it.method == "DELETE" })
    }

    // ---------------------------------------------------------------- comptage

    @Test
    fun countingGame() {
        send(command("comptage", "salon", mapOf("salon" to "c7"), permissions = Perm.MANAGE_GUILD))
        callback()
        chat("1", from = "a", channel = "c7")
        fake.await("PUT", "/channels/c7/messages/m$n/reactions/.+/@me")
        chat("2", from = "b", channel = "c7")
        fake.await("PUT", "/channels/c7/messages/m$n/reactions/.+/@me")
        chat("3", from = "b", channel = "c7") // deux fois de suite
        val broken = fake.await("POST", "/channels/c7/messages")
        assertTrue(broken.embed().getString("description").contains("deux fois de suite"))
        assertEquals(0, ctx.db.read("g1") { it.getJSONObject("counting").getInt("current") })
        assertEquals(2, ctx.db.read("g1") { it.getJSONObject("counting").getInt("record") })
    }

    // ---------------------------------------------------------------- starboard

    @Test
    fun starboardPostsOnceThenUpdates() {
        send(command("starboard", "activer", mapOf("salon" to "c-star", "seuil" to 2)))
        callback()
        fake.singleMessage = JSONObject()
            .put("id", "m-top").put("content", "Quel message !").put("author", user("u5"))
            .put("reactions", JSONArray().put(JSONObject().put("count", 2).put("emoji", JSONObject().put("name", "⭐"))))
        val reaction = JSONObject().put("guild_id", "g1").put("channel_id", "c1").put("message_id", "m-top")
            .put("user_id", "u6").put("emoji", JSONObject().put("name", "⭐"))
        engine.onDispatch("MESSAGE_REACTION_ADD", reaction)
        val post = fake.await("POST", "/channels/c-star/messages")
        assertTrue(post.json.getString("content").contains("**2**"))
        assertEquals("Quel message !", post.embed().getString("description"))

        engine.onDispatch("MESSAGE_REACTION_ADD", reaction)
        fake.await("PATCH", "/channels/c-star/messages/msg.+")
    }

    // ---------------------------------------------------------------- vocaux temporaires

    @Test
    fun tempVoiceCreatesMovesAndDeletes() {
        send(command("vocal", "configurer", mapOf("salon" to "hub"), permissions = Perm.MANAGE_CHANNELS))
        callback()
        engine.onDispatch(
            "VOICE_STATE_UPDATE",
            JSONObject().put("guild_id", "g1").put("user_id", "u3").put("channel_id", "hub")
                .put("member", JSONObject().put("user", user("u3", "bob"))),
        )
        val created = fake.await("POST", "/guilds/g1/channels")
        assertEquals("Salon de bob", created.json.getString("name"))
        assertEquals(2, created.json.getInt("type"))
        val move = fake.await("PATCH", "/guilds/g1/members/u3")
        val tempId = move.json.getString("channel_id")

        engine.onDispatch("VOICE_STATE_UPDATE", JSONObject().put("guild_id", "g1").put("user_id", "u3").put("channel_id", tempId))
        engine.onDispatch("VOICE_STATE_UPDATE", JSONObject().put("guild_id", "g1").put("user_id", "u3").put("channel_id", JSONObject.NULL))
        fake.await("DELETE", "/channels/$tempId")
    }

    // ---------------------------------------------------------------- anniversaires

    @Test
    fun birthdayIsWishedOnceWithRole() {
        val today = LocalDateTime.of(2026, 3, 14, 10, 0)
        val clock = Clock.fixed(today.atZone(ZoneId.of("Europe/Paris")).toInstant(), ZoneId.of("Europe/Paris"))
        send(command("anniversaire", "salon", mapOf("salon" to "c-bday", "role" to "r-cake"), permissions = Perm.MANAGE_GUILD))
        callback()
        send(command("anniversaire", "definir", mapOf("jour" to 14, "mois" to 3)))
        callback()
        val module = BirthdayModule(clock).also { it.ctx = ctx }
        module.tick(System.currentTimeMillis())
        val wish = fake.await("POST", "/channels/c-bday/messages")
        assertEquals("<@u1>", wish.json.getString("content"))
        fake.await("PUT", "/guilds/g1/members/u1/roles/r-cake")
        module.tick(System.currentTimeMillis())
        fake.assertNoMoreCalls()
    }

    // ---------------------------------------------------------------- casino et boutique

    @Test
    fun slotsAndBlackjackKeepMoneyConsistent() {
        ctx.db.edit("g1") { it.obj("eco").obj("u1").put("bal", 1000) }
        send(command("slots", options = mapOf("mise" to 100)))
        callback()
        val afterSlots = ctx.db.read("g1") { it.getJSONObject("eco").getJSONObject("u1").getLong("bal") }
        assertTrue(afterSlots in listOf(900L, 1050L, 1200L, 1400L, 1600L, 1900L))

        send(command("blackjack", options = mapOf("mise" to 100)))
        val start = data()
        if (start.has("components")) {
            val id = start.getJSONArray("components").getJSONObject(0).getJSONArray("components").getJSONObject(0)
                .getString("custom_id").substringAfterLast(':')
            send(click("bj:stand:$id", "m", by = "u2"))
            assertEquals(64, data().getInt("flags")) // pas sa partie
            send(click("bj:stand:$id", "m"))
            assertEquals(7, callback().getInt("type"))
        }
        val end = ctx.db.read("g1") { it.getJSONObject("eco").getJSONObject("u1").getLong("bal") }
        assertTrue("solde $end", end in listOf(afterSlots - 100, afterSlots, afterSlots + 100, afterSlots + 150))
    }

    @Test
    fun shopSellsRoles() {
        send(command("boutique", "ajouter", mapOf("nom" to "Couleur Or", "prix" to 300, "role" to "r-gold"), permissions = Perm.MANAGE_GUILD))
        callback()
        send(command("boutique", "acheter", mapOf("article" to "couleur or")))
        assertEquals(64, data().getInt("flags")) // pas assez d'argent
        ctx.db.edit("g1") { it.obj("eco").obj("u1").put("bal", 500) }
        send(command("boutique", "acheter", mapOf("article" to "couleur or")))
        fake.await("PUT", "/guilds/g1/members/u1/roles/r-gold")
        callback()
        assertEquals(200L, ctx.db.read("g1") { it.getJSONObject("eco").getJSONObject("u1").getLong("bal") })
        send(command("boutique", "acheter", mapOf("article" to "couleur or"), memberRoles = listOf("r-gold")))
        assertEquals(64, data().getInt("flags")) // déjà le rôle
    }

    // ---------------------------------------------------------------- utilitaires

    @Test
    fun calculator() {
        assertEquals(30.0, Calculator.eval("(12 + 8) * 3 / 2"), 1e-9)
        assertEquals(1024.0, Calculator.eval("2^10"), 1e-9)
        assertEquals(-2.5, Calculator.eval("-5/2"), 1e-9)
        assertEquals(3.5, Calculator.eval("1,5 + 2"), 1e-9)
        assertTrue(runCatching { Calculator.eval("1/0") }.isFailure)
        assertTrue(runCatching { Calculator.eval("2+") }.isFailure)
        assertTrue(runCatching { Calculator.eval("System.exit(0)") }.isFailure)
    }

    @Test
    fun embedBuilderOpensModalThenPosts() {
        send(command("embed", options = mapOf("salon" to "c9")))
        val modal = callback()
        assertEquals(9, modal.getInt("type"))
        assertEquals("embed:c9", modal.getJSONObject("data").getString("custom_id"))

        n++
        val fields = JSONArray()
        mapOf("title" to "Règles", "description" to "Soyez gentils", "color" to "#ff0000").forEach { (k, v) ->
            fields.put(JSONObject().put("type", 1).put("components", JSONArray().put(JSONObject().put("type", 4).put("custom_id", k).put("value", v))))
        }
        send(interaction(5, JSONObject().put("custom_id", "embed:c9").put("components", fields), "u1", Perm.ADMIN, emptyList(), "c1"))
        val post = fake.await("POST", "/channels/c9/messages")
        assertEquals("Règles", post.embed().getString("title"))
        assertEquals(0xFF0000, post.embed().getInt("color"))
    }

    @Test
    fun quizGivesCoinsForRightAnswer() {
        send(command("quiz"))
        val quiz = data()
        val buttons = quiz.getJSONArray("components").getJSONObject(0).getJSONArray("components")
        assertEquals(4, buttons.length())
        // On retrouve la bonne réponse en essayant sur une copie de la partie : ici on clique la 1re et on vérifie la cohérence.
        send(click(buttons.getJSONObject(0).getString("custom_id"), "m"))
        val result = callback()
        assertEquals(7, result.getInt("type"))
        val won = result.toString().contains("Bonne réponse")
        val bal = ctx.db.read("g1") { it.optJSONObject("eco")?.optJSONObject("u1")?.optLong("bal") }
        assertEquals(if (won) 50L else null, bal)
    }

    @Test
    fun calculCommand() {
        send(command("calcul", options = mapOf("expression" to "6*7")))
        assertTrue(data().getJSONArray("embeds").getJSONObject(0).getString("description").contains("**42**"))
    }

    @Test
    fun roleCommandGivesRole() {
        send(command("role", "ajouter", mapOf("membre" to "u2", "role" to "r-vip"), users = listOf(user("u2"))))
        fake.await("PUT", "/guilds/g1/members/u2/roles/r-vip")
        assertTrue(fake.await("POST", "/interactions/i$n/t$n/callback").embed().getString("description").contains("donné"))
    }
}
