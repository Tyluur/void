package content.minigame.warriors_guild

import content.entity.combat.killer
import content.entity.effect.movementDelay
import content.entity.obj.door.Door
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
import world.gregs.voidps.engine.inv.inventory
import world.gregs.voidps.engine.inv.remove
import world.gregs.voidps.engine.queue.softQueue
import world.gregs.voidps.engine.timer.Timer
import world.gregs.voidps.engine.timer.toTicks
import world.gregs.voidps.network.login.protocol.encode.sendVarbit
import world.gregs.voidps.type.Direction
import java.util.concurrent.TimeUnit

class WarriorsGuild : Script {

    companion object {
        private val armourSets = listOf(
            ArmourSet(0, listOf("bronze_platelegs", "bronze_platebody", "bronze_full_helm")), // Bronze
            ArmourSet(1, listOf("iron_full_helm", "iron_platebody", "iron_platelegs")), // Iron
            ArmourSet(2, listOf("steel_full_helm", "steel_platebody", "steel_platelegs")), // Steel
            ArmourSet(3, listOf("black_full_helm", "black_platebody", "black_platelegs")), // Black
            ArmourSet(4, listOf("mithril_full_helm", "mithril_platebody", "mithril_platelegs")), // Mithril
            ArmourSet(5, listOf("adamant_full_helm", "adamant_platebody", "adamant_platelegs")), // Adamant
            ArmourSet(6, listOf("rune_platebody", "rune_platelegs", "rune_full_helm"))  // Rune
        )

        val allArmourItems = armourSets.flatMap { it.armourIds }
        val ARMOR_POINTS = intArrayOf(5, 10, 15, 20, 50, 60, 80)
    }

    init {
        objectOperate("Open", "door_338_closed") {
            if (!canEnter(this)) {
                return@objectOperate
            }
            Door.openDoor(this, it.target, ticks = 3)
        }

        itemOnObjectOperate(allArmourItems.joinToString(","), "warriors_guild_animator", handler = ::handleAnimator)

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
            player["wg_animator_points"] = player.get("wg_animator_points", 0) + ARMOR_POINTS[index]
            player.clear("wg_animator_spawned")
            player.message("You gain ${ARMOR_POINTS[index]} Warriors Guild points.")
            updateWarriorPointsInterface(player)
        }

        interfaceOpened("warriors_guild") {
            updateWarriorPointsInterface(this)
        }

        entered("warriors_guild") {
            softTimers.start("wg_tick")
            if (!interfaces.contains("warriors_guild")) {
                open("warriors_guild")
            }
            updateWarriorPointsInterface(this)
        }

        exited("warriors_guild") {
            softTimers.stop("wg_tick")
            close("warriors_guild")
            resetKegs(this)
        }

        timerStart("wg_tick") { TimeUnit.MILLISECONDS.toTicks(600) }

        timerTick("wg_tick") {
            // Timer tick logic will be handled by WarriorsGuildTimer
            Timer.CONTINUE
        }
    }

    private fun canEnter(player: Player): Boolean {
        val attackLevel = player.levels.get(Skill.Attack)
        val strengthLevel = player.levels.get(Skill.Strength)
        if (attackLevel + strengthLevel < 130) {
            player.message("You do not meet the requirements to enter this Guild.")
            return false
        }
        return true
    }

    private fun resetKegs(player: Player) {
        player["wg_keg_count"] = 0
        player["wg_keg_ticks"] = 0
    }

    private fun updateWarriorPointsInterface(player: Player) {
        val points = player.get("wg_animator_points", 0)
        player.client?.sendVarbit(8662, points)
    }

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

    private data class ArmourSet(val index: Int, val armourIds: List<String>)

    private fun getArmourSet(itemId: String): ArmourSet? {
        return armourSets.firstOrNull { itemId in it.armourIds }
    }
}
