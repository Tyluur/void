package content.minigame.warriors_guild

import content.entity.obj.door.doorTarget
import content.entity.player.dialogue.type.statement
import world.gregs.voidps.engine.Script
import world.gregs.voidps.engine.client.ui.close
import world.gregs.voidps.engine.client.ui.open
import world.gregs.voidps.engine.data.definition.Areas
import world.gregs.voidps.engine.entity.character.player.Player
import world.gregs.voidps.engine.entity.character.player.skill.Skill
import world.gregs.voidps.engine.queue.softQueue
import world.gregs.voidps.engine.timer.Timer
import java.util.concurrent.TimeUnit

class WarriorsGuild : Script() {

    init {
        objectOperate("Open", "door_338_closed") {
            if (!canEnter(this)) {
                return@objectOperate
            }
            val target = doorTarget(this, it.target) ?: return@objectOperate
            tele(target)
        }

        entered("warriors_guild") {
            softTimers.start("wg_tick")
            if (!interfaces.contains("warriors_guild")) {
                open("warriors_guild")
            }
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
            statement("You do not meet the requirements to enter this Guild.")
            return false
        }
        return true
    }

    private fun resetKegs(player: Player) {
        player["wg_keg_count"] = 0
        player["wg_keg_ticks"] = 0
        // TODO: Remove keg hat from equipment if equipped
    }
}
