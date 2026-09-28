package com.bothostinger.app.bot.modules

import com.bothostinger.app.bot.engine.Command
import com.bothostinger.app.bot.engine.Embeds
import com.bothostinger.app.bot.engine.Interaction
import com.bothostinger.app.bot.engine.Module
import com.bothostinger.app.bot.engine.Perm
import com.bothostinger.app.bot.engine.button
import com.bothostinger.app.bot.engine.message
import com.bothostinger.app.bot.engine.roleMention
import com.bothostinger.app.bot.engine.roleOpt
import com.bothostinger.app.bot.engine.rows
import com.bothostinger.app.bot.engine.stringOpt

class RolesModule : Module(
    id = "roles",
    title = "Rôles",
    description = "Menus de rôles : les membres cliquent sur un bouton pour prendre ou enlever un rôle.",
) {
    override val setup = "/roles-boutons avec jusqu'à 10 rôles. Mon rôle doit être au-dessus des rôles proposés."

    override val commands = listOf(
        Command(
            "roles-boutons", "Publier un menu de rôles à boutons", Perm.MANAGE_ROLES,
            listOf(stringOpt("titre", "Titre du menu", maxLength = 200)) +
                (1..10).map { roleOpt("role$it", "Rôle $it", required = it == 1) } +
                stringOpt("description", "Texte au-dessus des boutons", required = false, maxLength = 1500),
        ) { publish(it) },
    )

    private fun publish(i: Interaction) {
        val gid = i.guildId!!
        val roles = (1..10).mapNotNull { n -> i.snowflake("role$n")?.let { it to i.role("role$n") } }.distinctBy { it.first }
        val invalid = roles.filter { (id, role) -> id == gid || role?.optBoolean("managed") == true }
        if (invalid.isNotEmpty()) return i.error("${invalid.joinToString { roleMention(it.first) }} ne peut pas être donné par un bot.")

        val buttons = roles.map { (id, role) -> button("rr:$id", role?.optString("name") ?: "Rôle", 2) }
        val text = (i.string("description") ?: "Clique sur un bouton pour prendre ou retirer le rôle.") +
            "\n\n" + roles.joinToString("\n") { "• ${roleMention(it.first)}" }
        rest.createMessage(i.channelId, message(embed = Embeds.base(i.string("titre") ?: "Rôles", text), components = rows(buttons)))
        i.success("Menu de rôles publié.", ephemeral = true)
    }

    override fun onComponent(i: Interaction): Boolean {
        if (!i.customId.startsWith("rr:")) return false
        val gid = i.guildId ?: return true
        val role = i.customId.removePrefix("rr:")
        if (role in i.memberRoles) {
            rest.removeRole(gid, i.userId, role, "Menu de rôles")
            i.success("Rôle ${roleMention(role)} retiré.", ephemeral = true)
        } else {
            rest.addRole(gid, i.userId, role, "Menu de rôles")
            i.success("Rôle ${roleMention(role)} ajouté !", ephemeral = true)
        }
        return true
    }
}
