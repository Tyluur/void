package content.minigame.warriors_guild

import world.gregs.voidps.engine.Script
import world.gregs.voidps.engine.client.message
import world.gregs.voidps.engine.client.ui.close
import world.gregs.voidps.engine.client.ui.closeInterfaces
import world.gregs.voidps.engine.entity.character.player.Player
import world.gregs.voidps.engine.entity.character.move.tele
import world.gregs.voidps.network.login.protocol.encode.sendVarbit
import world.gregs.voidps.type.Tile

class WarriorsGuildCyclopsSelection : Script {

    private val ALL_TYPES_COST = 30
    private val SINGLE_TYPE_COST = 200

    /**
     * Varbit IDs for displaying token counts on interface 1058.
     * Matches darkan implementation: 8662-8666.
     */
    private val VARBIT_STRENGTH = 8662
    private val VARBIT_DEFENCE = 8663
    private val VARBIT_ATTACK = 8664
    private val VARBIT_COMBAT = 8665
    private val VARBIT_BALANCE = 8666

    /**
     * Maps each [ActivityType] to its corresponding varbit ID for the interface.
     */
    private val activityVarbits = mapOf(
        ActivityType.Strength to VARBIT_STRENGTH,
        ActivityType.Defence to VARBIT_DEFENCE,
        ActivityType.Attack to VARBIT_ATTACK,
        ActivityType.Combat to VARBIT_COMBAT,
        ActivityType.Balance to VARBIT_BALANCE
    )

    init {
        /**
         * Sends current token counts to interface 1058 when opened.
         */
        interfaceOpened("warriors_guild_cyclops_selection") {
            val activities = listOf(
                ActivityType.Balance, ActivityType.Strength, ActivityType.Combat,
                ActivityType.Attack, ActivityType.Defence
            )
            for (activity in activities) {
                val varbit = activityVarbits[activity] ?: continue
                client?.sendVarbit(varbit, getTokens(activity))
            }
        }

        interfaceOption("Select", "warriors_guild_cyclops_selection:last_single") {
            val last = lastSingleMode
            if (last is PaymentMode.SingleType) {
                cyclopsPaymentMode = last
                message("Selected: ${last.activity.javaClass.simpleName} (200 tokens)")
            } else {
                message("No previous single-type selection found.")
            }
        }

        interfaceOption("Select", "warriors_guild_cyclops_selection:all_types") {
            cyclopsPaymentMode = PaymentMode.AllTypes
            message("Selected: All Types (30 of each token)")
        }

        interfaceOption("Select", "warriors_guild_cyclops_selection:balance") {
            setSingleType(this, ActivityType.Balance)
        }

        interfaceOption("Select", "warriors_guild_cyclops_selection:strength") {
            setSingleType(this, ActivityType.Strength)
        }

        interfaceOption("Select", "warriors_guild_cyclops_selection:combat") {
            setSingleType(this, ActivityType.Combat)
        }

        interfaceOption("Select", "warriors_guild_cyclops_selection:attack") {
            setSingleType(this, ActivityType.Attack)
        }

        interfaceOption("Select", "warriors_guild_cyclops_selection:defence") {
            setSingleType(this, ActivityType.Defence)
        }

        interfaceOption("Ok", "warriors_guild_cyclops_selection:enter") {
            if (enterRoom(this)) {
                close("warriors_guild_cyclops_selection")
                closeInterfaces()
                // Teleport player into cyclops room after deducting tokens
                tele(Tile(2847, 3541, 2))
            }
        }
    }

    private fun setSingleType(player: Player, activity: ActivityType) {
        val mode = PaymentMode.SingleType(activity)
        player.cyclopsPaymentMode = mode
        player.lastSingleMode = mode
        player.message("Selected: ${activity.javaClass.simpleName} (200 tokens)")
    }

    private fun enterRoom(player: Player): Boolean {
        val mode = player.cyclopsPaymentMode
        if (!hasEnoughTokens(player, mode)) {
            player.message("You don't have enough tokens for this option.")
            return false
        }
        if (!deductEntryCost(player, mode)) {
            player.message("Token deduction failed.")
            return false
        }
        player.inCyclopsRoom = true
        player.softTimers.start("cyclops_token_drain")
        player.message("You enter the cyclops room.")
        return true
    }

    private fun hasEnoughTokens(player: Player, mode: PaymentMode): Boolean = when (mode) {
        is PaymentMode.AllTypes -> listOf(
            ActivityType.Balance, ActivityType.Strength, ActivityType.Combat,
            ActivityType.Attack, ActivityType.Defence
        ).all { player.getTokens(it) >= ALL_TYPES_COST }

        is PaymentMode.SingleType -> player.getTokens(mode.activity) >= SINGLE_TYPE_COST
    }

    private fun deductEntryCost(player: Player, mode: PaymentMode): Boolean {
        return when (mode) {
            is PaymentMode.AllTypes -> {
                val activities = listOf(
                    ActivityType.Balance, ActivityType.Strength, ActivityType.Combat,
                    ActivityType.Attack, ActivityType.Defence
                )
                if (activities.any { player.getTokens(it) < ALL_TYPES_COST }) return false
                activities.forEach { player.setTokens(it, player.getTokens(it) - ALL_TYPES_COST) }
                true
            }

            is PaymentMode.SingleType -> {
                val current = player.getTokens(mode.activity)
                if (current < SINGLE_TYPE_COST) return false
                player.setTokens(mode.activity, current - SINGLE_TYPE_COST)
                true
            }
        }
    }
}
