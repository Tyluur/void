package content.minigame.warriors_guild

import content.entity.combat.killer
import world.gregs.voidps.engine.Script
import world.gregs.voidps.engine.client.message
import world.gregs.voidps.engine.client.ui.close
import world.gregs.voidps.engine.client.ui.closeInterfaces
import world.gregs.voidps.engine.client.ui.open
import world.gregs.voidps.engine.entity.character.move.tele
import world.gregs.voidps.engine.entity.character.player.Player
import world.gregs.voidps.engine.entity.item.floor.FloorItems
import world.gregs.voidps.engine.timer.Timer
import world.gregs.voidps.engine.timer.toTicks
import world.gregs.voidps.network.login.protocol.encode.sendVarbit
import world.gregs.voidps.type.Tile
import java.util.concurrent.TimeUnit

object WarriorsGuildCyclops : Script {

    private val CYCLOPS_LOBBY = Tile(2843, 3535, 2)
    private const val ALL_TYPES_COST = 30
    private const val SINGLE_TYPE_COST = 200
    private const val ALL_TYPES_DRAIN = 3
    private const val SINGLE_TYPE_DRAIN = 20
    private const val DRAIN_INTERVAL_SECONDS = 60

    sealed class PaymentMode {
        object AllTypes : PaymentMode()
        data class SingleType(val activity: ActivityType) : PaymentMode()
    }

    sealed class ActivityType(val variable: String) {
        object Balance : ActivityType("wg_points_barrels")
        object Strength : ActivityType("wg_points_strength")
        object Combat : ActivityType("wg_points_combat")
        object Attack : ActivityType("wg_points_attack")
        object Defence : ActivityType("wg_points_defence")
    }

    private fun parseMode(mode: String): PaymentMode = when (mode) {
        "all" -> PaymentMode.AllTypes
        "balance" -> PaymentMode.SingleType(ActivityType.Balance)
        "strength" -> PaymentMode.SingleType(ActivityType.Strength)
        "combat" -> PaymentMode.SingleType(ActivityType.Combat)
        "attack" -> PaymentMode.SingleType(ActivityType.Attack)
        "defence" -> PaymentMode.SingleType(ActivityType.Defence)
        else -> PaymentMode.AllTypes
    }

    private fun PaymentMode.toVariableString(): String = when (this) {
        is PaymentMode.AllTypes -> "all"
        is PaymentMode.SingleType -> when (this.activity) {
            is ActivityType.Balance -> "balance"
            is ActivityType.Strength -> "strength"
            is ActivityType.Combat -> "combat"
            is ActivityType.Attack -> "attack"
            is ActivityType.Defence -> "defence"
        }
    }

    private var Player.cyclopsPaymentMode: PaymentMode
        get() = parseMode(get("wg_cyclops_option", "all"))
        set(value) {
            this["wg_cyclops_option"] = value.toVariableString()
        }

    private var Player.lastSingleMode: PaymentMode
        get() = parseMode(get("wg_cyclops_last_single", "combat"))
        set(value) {
            if (value is PaymentMode.SingleType) this["wg_cyclops_last_single"] = value.toVariableString()
        }

    private var Player.inCyclopsRoom: Boolean
        get() = get("wg_in_cyclops_room", false)
        set(value) {
            this["wg_in_cyclops_room"] = value
        }

    private fun Player.getTokens(activity: ActivityType): Int = get(activity.variable, 0)
    private fun Player.setTokens(activity: ActivityType, count: Int) {
        this[activity.variable] = count
    }

    /**
     * Varbit IDs for displaying token counts on interface 1058.
     * Matches darkan implementation: 8662-8666.
     */
    private const val VARBIT_STRENGTH = 8662
    private const val VARBIT_DEFENCE = 8663
    private const val VARBIT_ATTACK = 8664
    private const val VARBIT_COMBAT = 8665
    private const val VARBIT_BALANCE = 8666

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

        interfaceOption("Enter", "warriors_guild_cyclops_selection:enter") {
            if (enterRoom(this)) {
                close("warriors_guild_cyclops_selection")
                closeInterfaces()
            }
        }

        timerStart("cyclops_token_drain") { TimeUnit.SECONDS.toTicks(DRAIN_INTERVAL_SECONDS) }
        timerTick("cyclops_token_drain", ::onDrainTick)

        /**
         * Handles cyclops deaths in the Warriors Guild.
         * Checks if the player is in the cyclops room and has tokens remaining.
         * If so, gives a 1/50 chance to drop the next defender the player needs.
         */
        npcDeath("cyclopse_hammer,cyclopse_hammer_steel,cyclopse_club_lepard,cyclopse_club,cyclopse_club_mohawk,cyclopse_knife") { death ->
            val player = killer as? Player ?: return@npcDeath
            if (!player.inCyclopsRoom) {
                player.message("Your time has expired and the cyclops will no longer drop defenders.")
                return@npcDeath
            }
            if ((0..49).random() == 0) {
                val defender = WarriorsGuild.getBestDefender(player)
                FloorItems.add(
                    tile = tile,
                    id = defender,
                    amount = 1,
                    revealTicks = FloorItems.NEVER,
                    disappearTicks = 300,
                    owner = player
                )
            }
        }

        /**
         * Handles the cyclops room entrance gates (15641, 15644 — double door).
         * From the lobby side, opens the payment interface (1058).
         * From inside the room, allows the player to walk out freely.
         * These are walk-through gates, not openable doors.
         */
        objectOperate("Open", "warriors_guild_cyclops_gate,warriors_guild_cyclops_gate_2") {
            val gate = it.target
            val isLobbySide = tile.y == gate.tile.y
            if (isLobbySide) {
                open("warriors_guild_cyclops_selection")
            } else {
                inCyclopsRoom = false
                softTimers.stop("cyclops_token_drain")
                tele(gate.tile)
            }
        }

        /**
         * Handles the catapult/defense area gate (15647).
         * Walk-through gate; removes the defensive shield when leaving the area.
         */
        objectOperate("Open", "warriors_guild_cyclops_gate_3") {
            val gate = it.target
            val isLobbySide = tile.y == gate.tile.y
            if (isLobbySide) {
                tele(gate.tile.addY(1))
            } else {
                // TODO: Remove defensive shield (8856) if equipped when leaving
                tele(gate.tile)
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

    private fun onDrainTick(player: Player): Int {
        if (!player.inCyclopsRoom) return Timer.CANCEL
        val mode = player.cyclopsPaymentMode
        val canContinue = when (mode) {
            is PaymentMode.AllTypes -> drainAllTypes(player)
            is PaymentMode.SingleType -> drainSingleType(player, mode.activity)
        }
        if (!canContinue) {
            player.message("You have run out of tokens.")
            exitRoom(player)
            return Timer.CANCEL
        }
        return Timer.CONTINUE
    }

    private fun drainAllTypes(player: Player): Boolean {
        val activities = listOf(
            ActivityType.Balance, ActivityType.Strength, ActivityType.Combat,
            ActivityType.Attack, ActivityType.Defence
        )
        if (activities.any { player.getTokens(it) < ALL_TYPES_DRAIN }) return false
        activities.forEach { player.setTokens(it, player.getTokens(it) - ALL_TYPES_DRAIN) }
        return true
    }

    private fun drainSingleType(player: Player, activity: ActivityType): Boolean {
        val current = player.getTokens(activity)
        if (current < SINGLE_TYPE_DRAIN) return false
        player.setTokens(activity, current - SINGLE_TYPE_DRAIN)
        return true
    }

    private fun exitRoom(player: Player) {
        player.inCyclopsRoom = false
        player.tele(CYCLOPS_LOBBY)
        player.softTimers.stop("cyclops_token_drain")
    }

    val Player.cyclopsDamageMult: Double
        get() = if (inCyclopsRoom && cyclopsPaymentMode is PaymentMode.AllTypes) 1.1 else 1.0

    val Player.cyclopsDefenderMult: Double
        get() = if (inCyclopsRoom && cyclopsPaymentMode is PaymentMode.AllTypes) 2.0 else 1.0
}
