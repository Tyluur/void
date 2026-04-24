package content.minigame.warriors_guild

import world.gregs.voidps.engine.Script
import world.gregs.voidps.engine.client.command.*
import world.gregs.voidps.engine.client.message
import world.gregs.voidps.engine.entity.character.player.Player
import world.gregs.voidps.engine.entity.character.player.Players
import world.gregs.voidps.engine.entity.character.player.name

class WarriorsGuildCommands : Script {

    init {
        adminCommand(
            "max_tokens",
            stringArg("player-name", "target player (default self)", optional = true),
            desc = "Set all Warriors Guild tokens to maximum",
            handler = ::maxTokens
        )
    }

    fun maxTokens(player: Player, args: List<String>) {
        val target = if (args.isNotEmpty()) Players.find(args[0]) else player

        if (target == null) {
            return player.message("Player not found.")
        }

        // Set all token types to maximum (arbitrary high value for testing)
        target.setTokens(ActivityType.Balance, 9999)
        target.setTokens(ActivityType.Strength, 9999)
        target.setTokens(ActivityType.Combat, 9999)
        target.setTokens(ActivityType.Attack, 9999)
        target.setTokens(ActivityType.Defence, 9999)

        target.message("All Warriors Guild tokens set to maximum.")
        if (target != player) {
            player.message("Set all Warriors Guild tokens to maximum for ${target.name}.")
        }
    }
}
