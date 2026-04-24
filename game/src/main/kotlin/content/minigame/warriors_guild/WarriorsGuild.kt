package content.minigame.warriors_guild

import content.entity.combat.killer
import content.entity.effect.movementDelay
import content.entity.obj.door.Door
import content.entity.obj.door.enterDoor
import world.gregs.voidps.engine.Script
import world.gregs.voidps.engine.client.message
import world.gregs.voidps.engine.client.ui.close
import world.gregs.voidps.engine.client.ui.open
import world.gregs.voidps.engine.entity.character.mode.interact.ItemOnObjectInteract
import world.gregs.voidps.engine.entity.item.Item
import kotlinx.coroutines.delay
import world.gregs.voidps.engine.client.instruction.handle.interactPlayer
import world.gregs.voidps.engine.entity.character.npc.NPCs
import world.gregs.voidps.engine.entity.character.player.Player
import world.gregs.voidps.engine.entity.character.player.skill.Skill
import world.gregs.voidps.engine.entity.obj.GameObject
import world.gregs.voidps.engine.entity.character.player.equip.equipped
import world.gregs.voidps.engine.inv.add
import world.gregs.voidps.engine.inv.inventory
import world.gregs.voidps.engine.inv.remove
import world.gregs.voidps.engine.queue.softQueue
import world.gregs.voidps.engine.timer.Timer
import world.gregs.voidps.engine.timer.toTicks
import world.gregs.voidps.network.login.protocol.encode.sendVarbit
import world.gregs.voidps.network.login.protocol.visual.update.player.EquipSlot
import world.gregs.voidps.type.Direction
import java.util.concurrent.TimeUnit

/**
 * Handles the Warriors' Guild minigame, specifically the animated armour room.
 *
 * This class manages:
 * - Entrance requirements (attack + strength >= 130)
 * - Animated armour spawning using the animator
 * - Warriors Guild points system for defeating animated armours
 * - Interface overlay updates to show token count
 * - Door access control
 *
 * Players can use armour pieces on the animator to spawn corresponding animated armours.
 * Defeating these armours awards Warriors Guild points based on the armour tier.
 *
 * @property armourSets List of armour sets with their corresponding item IDs
 * @property allArmourItems Flat list of all armour items for item-on-object handling
 * @property ARMOR_POINTS Point values awarded for each armour tier (bronze to rune)
 */
class WarriorsGuild : Script {

    companion object {
        /**
         * Armour sets indexed by tier (0=bronze, 1=iron, 2=steel, 3=black, 4=mithril, 5=adamant, 6=rune)
         */
        private val armourSets = listOf(
            ArmourSet(0, listOf("bronze_platelegs", "bronze_platebody", "bronze_full_helm")), // Bronze
            ArmourSet(1, listOf("iron_full_helm", "iron_platebody", "iron_platelegs")), // Iron
            ArmourSet(2, listOf("steel_full_helm", "steel_platebody", "steel_platelegs")), // Steel
            ArmourSet(3, listOf("black_full_helm", "black_platebody", "black_platelegs")), // Black
            ArmourSet(4, listOf("mithril_full_helm", "mithril_platebody", "mithril_platelegs")), // Mithril
            ArmourSet(5, listOf("adamant_full_helm", "adamant_platebody", "adamant_platelegs")), // Adamant
            ArmourSet(6, listOf("rune_platebody", "rune_platelegs", "rune_full_helm"))  // Rune
        )

        /**
         * Flat list of all armour items for item-on-object handler registration
         */
        val allArmourItems = armourSets.flatMap { it.armourIds }

        /**
         * Point values awarded for defeating each tier of animated armour
         * Index corresponds to armour tier: 0=bronze(5), 1=iron(10), 2=steel(15), 3=black(20), 4=mithril(50), 5=adamant(60), 6=rune(80)
         */
        val ARMOR_POINTS = intArrayOf(5, 10, 15, 20, 50, 60, 80)

        /**
         * List of defenders from best to worst.
         */
        private val DEFENDERS = listOf(
            "dragon_defender",
            "rune_defender",
            "adamant_defender",
            "mithril_defender",
            "black_defender",
            "steel_defender",
            "iron_defender",
            "bronze_defender"
        )

        /**
         * Retrieves the next defender the player should receive from cyclopses.
         * Checks equipment and inventory in order from best to worst.
         *
         * @param player The player to check
         * @return The item ID of the defender to drop (next tier up from what they have), or "bronze_defender" if none found
         */
        fun getBestDefender(player: Player): String {
            for (i in DEFENDERS.indices) {
                if (player.equipped(EquipSlot.Shield).id == DEFENDERS[i] || player.inventory.contains(DEFENDERS[i])) {
                    return DEFENDERS[if (i - 1 < 0) 0 else i - 1]
                }
            }
            return DEFENDERS.last()
        }
    }

    init {
        /**
         * Handles opening the Warriors Guild entrance door.
         * Checks if player meets requirements (attack + strength >= 130) before allowing entry.
         */
        objectOperate("Open", "warriors_guild_internal_door_3_closed") {
            if (!canEnter(this)) {
                return@objectOperate
            }
            enterDoor(it.target, ticks = 3)
        }

        /**
         * Handles using armour items on the animator to spawn animated armours.
         * Registers handler for all armour items that can be used on the animator.
         */
        itemOnObjectOperate(allArmourItems.joinToString(","), "warriors_guild_animator", handler = ::handleAnimator)

        /**
         * Handles the death of animated armour NPCs.
         * Awards Warriors Guild points to the killer based on the armour tier.
         * Clears the spawned state and updates the interface overlay.
         */
        npcDeath("animated_bronze_armour,animated_iron_armour,animated_steel_armour,animated_black_armour,animated_mithril_armour,animated_adamant_armour,animated_rune_armour") { death ->
            val player = killer as? Player ?: return@npcDeath
            val npcId = id.substringAfter("_").substringBefore("_armour")
            val index = when (npcId) {
                "bronze" -> 0
                "iron" -> 1
                "steel" -> 2
                "black" -> 3
                "mithril" -> 4
                "adamant" -> 5
                "rune" -> 6
                else -> 0
            }
            player["wg_points_combat"] = player.get("wg_points_combat", 0) + ARMOR_POINTS[index]
            player.clear("wg_animator_spawned")
            player.message("You gain ${ARMOR_POINTS[index]} Warriors Guild points.")
            updateWarriorPointsInterface(player)
        }

        /**
         * Handles opening the Warriors Guild interface overlay.
         * Updates the interface to show current Warriors Guild points.
         */
        interfaceOpened("warriors_guild") {
            updateWarriorPointsInterface(this)
        }

        /**
         * Handles player entering the Warriors Guild area.
         * Starts the guild timer, opens the interface overlay, and updates points display.
         */
        entered("warriors_guild") {
            softTimers.start("wg_tick")
            if (!interfaces.contains("warriors_guild")) {
                open("warriors_guild")
            }
            updateWarriorPointsInterface(this)
        }

        /**
         * Handles player exiting the Warriors Guild area.
         * Stops the guild timer, closes the interface overlay, and resets keg-related state.
         */
        exited("warriors_guild") {
            softTimers.stop("wg_tick")
            close("warriors_guild")
            resetKegs(this)
        }

        /**
         * Configures the Warriors Guild timer to tick every 600ms.
         */
        timerStart("wg_tick") { TimeUnit.MILLISECONDS.toTicks(600) }

        /**
         * Handles timer ticks for the Warriors Guild.
         * Placeholder for future timer-based logic (handled by WarriorsGuildTimer).
         */
        timerTick("wg_tick") {
            // Timer tick logic will be handled by WarriorsGuildTimer
            Timer.CONTINUE
        }
    }

    /**
     * Checks if a player meets the requirements to enter the Warriors Guild.
     *
     * @param player The player to check
     * @return true if the player can enter (attack + strength >= 130), false otherwise
     */
    private fun canEnter(player: Player): Boolean {
        val attackLevel = player.levels.get(Skill.Attack)
        val strengthLevel = player.levels.get(Skill.Strength)
        if (attackLevel + strengthLevel < 130) {
            player.message("You do not meet the requirements to enter this Guild.")
            return false
        }
        return true
    }

    /**
     * Resets keg-related player state when leaving the Warriors Guild.
     *
     * @param player The player to reset state for
     */
    private fun resetKegs(player: Player) {
        player["wg_keg_count"] = 0
        player["wg_keg_ticks"] = 0
    }

    /**
     * Updates the Warriors Guild interface overlay with the player's current Warriors Guild points.
     * Sends all token types to their corresponding varbits (matching darkan implementation).
     *
     * @param player The player to update the interface for
     */
    private fun updateWarriorPointsInterface(player: Player) {
        // Varbit IDs for displaying token counts on overlay/interface
        // Matches darkan implementation: 8662-8666
        val balancePoints = player.get("wg_points_barrels", 0)
        val strengthPoints = player.get("wg_points_strength", 0)
        val attackPoints = player.get("wg_points_attack", 0)
        val combatPoints = player.get("wg_points_combat", 0)
        val defencePoints = player.get("wg_points_defence", 0)

        player.client?.sendVarbit(8666, balancePoints)  // Balance
        player.client?.sendVarbit(8662, strengthPoints)  // Strength
        player.client?.sendVarbit(8664, attackPoints)   // Attack
        player.client?.sendVarbit(8665, combatPoints)   // Combat
        player.client?.sendVarbit(8663, defencePoints)  // Defence
    }

    /**
     * Handles using an armour item on the animator to spawn an animated armour.
     * Performs the following sequence:
     * 1. Checks if player is already in combat with an animated armour
     * 2. Validates the player has a full armour set
     * 3. Removes the armour pieces from inventory
     * 4. Plays animation sequence with player walkback (3 tiles north)
     * 5. Spawns the corresponding animated armour NPC
     * 6. Initiates combat with the player
     *
     * @param player The player using the animator
     * @param interact The item-on-object interaction details
     */
    private fun handleAnimator(player: Player, interact: ItemOnObjectInteract) {
        val item = interact.item
        val obj = interact.target
        if (player.contains("wg_animator_spawned")) {
            player.message("You are already in combat with an animated armour.")
            return
        }

        val armourSet = getArmourSet(item.id)
        if (armourSet == null) {
            return
        }

        // Check if player has full armour set
        for (armourId in armourSet.armourIds) {
            if (!player.inventory.contains(armourId)) {
                player.message("You don't have the full armour set!")
                return
            }
        }

        // Delete armour pieces from inventory
        for (armourId in armourSet.armourIds) {
            player.inventory.remove(armourId)
        }

        player.anim("human_pickup")
        player.movementDelay = 10
        player.softQueue("animated_armour_spawn") {
            delay(1)
            player.message("The animator hums, something appears to be working.")
            delay(1)
            player.message("You stand back.")
            player.walkTo(player.tile.add(Direction.NORTH.delta).add(Direction.NORTH.delta).add(Direction.NORTH.delta))
            delay(1)
            player.message("The animator hums, something appears to be working.")
            delay(2)
            player.movementDelay = 0
            val npcId = when (armourSet.index) {
                0 -> "animated_bronze_armour"
                1 -> "animated_iron_armour"
                2 -> "animated_steel_armour"
                3 -> "animated_black_armour"
                4 -> "animated_mithril_armour"
                5 -> "animated_adamant_armour"
                6 -> "animated_rune_armour"
                else -> "animated_bronze_armour"
            }
            val npc = NPCs.add(npcId, obj.tile, Direction.SOUTH)
            npc.anim("animated_armour_spawn")
            npc.say("IM ALIVE!")
            npc.interactPlayer(player, "Attack")
            player["wg_animator_spawned"] = true
        }
    }

    /**
     * Data class representing a complete armour set for the animator.
     *
     * @property index The tier index (0=bronze, 1=iron, 2=steel, 3=black, 4=mithril, 5=adamant, 6=rune)
     * @property armourIds List of item IDs that make up the complete armour set
     */
    private data class ArmourSet(val index: Int, val armourIds: List<String>)

    /**
     * Retrieves the armour set that contains the specified item ID.
     *
     * @param itemId The item ID to look up
     * @return The ArmourSet if found, null otherwise
     */
    private fun getArmourSet(itemId: String): ArmourSet? {
        return armourSets.firstOrNull { itemId in it.armourIds }
    }
}
