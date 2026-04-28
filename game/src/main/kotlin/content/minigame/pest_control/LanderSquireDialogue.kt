package content.minigame.pest_control

import content.entity.player.dialogue.Confused
import content.entity.player.dialogue.Neutral
import content.entity.player.dialogue.Quiz
import content.entity.player.dialogue.type.choice
import content.entity.player.dialogue.type.npc
import content.entity.player.dialogue.type.player
import world.gregs.voidps.engine.Script
import world.gregs.voidps.engine.client.ui.dialogue.talkWith
import world.gregs.voidps.engine.entity.character.npc.NPC
import world.gregs.voidps.engine.entity.character.player.Player

/**
 * Lander Squire dialogue implementation.
 * Ported from 2009scape's SquireTypeDialogue.java.
 *
 * NPC IDs: squire_novice_pest_control, squire_intermediate_pest_control, squire_veteran_pest_control
 * These squires explain the lander system and requirements for each difficulty.
 */
class LanderSquireDialogue : Script {

    init {
        // Register dialogue for all lander squires
        npcOperate("Talk-to", "squire_novice_pest_control") { (target) ->
            dialogue(target, "novice")
        }
        npcOperate("Talk-to", "squire_intermediate_pest_control") { (target) ->
            dialogue(target, "intermediate")
        }
        npcOperate("Talk-to", "squire_veteran_pest_control") { (target) ->
            dialogue(target, "veteran")
        }
    }

    suspend fun Player.dialogue(npc: NPC, difficulty: String) {
        talkWith(npc)
        npc<Neutral>("Hi, how can I help you?")
        choice {
            option("Who are you?") {
                whoAreYouBranch()
            }
            option("What's going on here?") {
                whatsGoingOnBranch(difficulty)
            }
            option("I'm fine thanks.") {
                player<Neutral>("I'm fine thanks.")
            }
        }
    }

    private suspend fun Player.whoAreYouBranch() {
        player<Quiz>("Who are you?")
        npc<Neutral>("I'm a squire for the Void Knights.")
        player<Quiz>("The who?")
        npc<Neutral>("The Void Knights, they are great warriors of balance who do Guthix's work here in Gielinor.")
    }

    private suspend fun Player.whatsGoingOnBranch(difficulty: String) {
        player<Quiz>("What's going on here?")
        npc<Neutral>("This is where we launch our landers to combat the invasion of the nearby islands. You'll see three landers - one for novice, intermediate and veteran adventurers. Each has a different difficulty, but they therefore offer varying rewards.")
        player<Quiz>("And this lander is...?")
        val difficultyName = when (difficulty) {
            "novice" -> "novice"
            "intermediate" -> "intermediate"
            "veteran" -> "veteran"
            else -> difficulty
        }
        npc<Neutral>("The $difficultyName.")
        player<Quiz>("So how do they work?")
        val combatLevel = when (difficulty) {
            "novice" -> 40
            "intermediate" -> 70
            "veteran" -> 100
            else -> 40
        }
        npc<Neutral>("Simple. We send each lander out every five minutes, however we need at least five volunteers or it's a suicide mission. The lander can only hold a maximum of twenty five people though, so we do send them out early if they get full. If you'd be willing to help us then just wait in the lander and we'll launch it as soon as it's ready. However you will need a combat level of $combatLevel to use this lander.")
    }
}
