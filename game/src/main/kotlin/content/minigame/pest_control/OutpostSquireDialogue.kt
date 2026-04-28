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
 * Outpost Squire dialogue implementation.
 * Ported from 2009scape's OutpostSquireDialogue.java.
 *
 * NPC IDs: squire_pest_control, squire_2_pest_control, squire_3_pest_control, squire_4_pest_control
 * These squires are at the main outpost and have lore dialogue about the Void Knights.
 */
class OutpostSquireDialogue : Script {

    init {
        // Register dialogue for all outpost squires
        npcOperate("Talk-to", "squire_pest_control") { (target) ->
            dialogue(target)
        }
        npcOperate("Talk-to", "squire_2_pest_control") { (target) ->
            dialogue(target)
        }
        npcOperate("Talk-to", "squire_3_pest_control") { (target) ->
            dialogue(target)
        }
        npcOperate("Talk-to", "squire_4_pest_control") { (target) ->
            dialogue(target)
        }
    }

    suspend fun Player.dialogue(npc: NPC) {
        talkWith(npc)
        npc<Neutral>("Hi, how can I help you?")
        choice {
            option("Who are you?") {
                whoAreYouBranch()
            }
            option("What is this place?") {
                whatIsThisPlaceBranch()
            }
            option("I'm fine thanks.") {
                player<Neutral>("I'm fine thanks.")
            }
        }
    }

    private suspend fun Player.whoAreYouBranch() {
        player<Quiz>("Who are you?")
        npc<Neutral>("I'm a Squire for the Void Knights.")
        player<Quiz>("The who?")
        npc<Neutral>("The Void Knights, they are great warriors of balance who do Guthix's work here in Gielinor.")
        choice {
            option("Wow, can I join?") {
                player<Quiz>("Wow, can I join?")
                npc<Neutral>("Entry is strictly invite only, however we do need help continuing Guthix's work.")
                player<Quiz>("What kind of work?")
                whatKindOfWorkBranch()
            }
            option("What kind of work?") {
                player<Quiz>("What kind of work?")
                whatKindOfWorkBranch()
            }
            option("What's 'Gielinor'?") {
                player<Confused>("What's 'Gielinor'?")
                npc<Neutral>("It is the name that Guthix gave to this world, so we honour him with its use.")
            }
            option("Uh huh, sure.") {
                player<Neutral>("Uh huh, sure.")
            }
        }
    }

    private suspend fun Player.whatIsThisPlaceBranch() {
        player<Quiz>("What is this place?")
        npc<Neutral>("This is our outpost. From here we send launchers out to the nearby islands to beat back the invaders.")
        choice {
            option("What invaders?") {
                player<Quiz>("What invaders?")
                npc<Neutral>("Recently there have been breaches into our realm from somewhere else, and strange creatures have been pouring through. We can't let that happen, and we'd be very grateful if you'd help us.")
                howCanIHelpBranch()
            }
            option("How can I help?") {
                player<Quiz>("How can I help?")
                howCanIHelpBranch()
            }
            option("Good luck with that.") {
                player<Neutral>("Good luck with that.")
            }
        }
    }

    private suspend fun Player.whatKindOfWorkBranch() {
        npc<Neutral>("Ah well you see we try to keep Gielinor as Guthix intended, it's very challenging. Actually we've been having some problems recently, maybe you could help us?")
        choice {
            option("Yeah OK, what's the problem?") {
                player<Quiz>("Yeah ok, what's the problem?")
                npc<Neutral>("Well the order has become quite diminished over the years, it's a very long process to learn the skills of a Void Knight. Recently there have been breaches into our realm from somewhere else, and strange creatures have been pouring through. We can't let that happen, and we'd be very grateful if you'd help us.")
                howCanIHelpBranch()
            }
            option("What's 'Gielinor'?") {
                player<Confused>("What's 'Gielinor'?")
                npc<Neutral>("It is the name that Guthix gave to this world, so we honour him with its use.")
            }
            option("I'd rather not, sorry.") {
                player<Neutral>("I'd rather not sorry.")
            }
        }
    }

    private suspend fun Player.howCanIHelpBranch() {
        choice {
            option("How can I help?") {
                player<Quiz>("How can I help?")
                npc<Neutral>("We send launchers from our outpost to the nearby islands. If you go and wait in the lander there that'd really help.")
            }
            option("Sorry, but I can't.") {
                player<Neutral>("Sorry, but I can't.")
            }
        }
    }
}
