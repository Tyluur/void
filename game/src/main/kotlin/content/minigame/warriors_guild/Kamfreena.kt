package content.minigame.warriors_guild

import com.github.michaelbull.logging.InlineLogger
import content.entity.player.dialogue.Neutral
import content.entity.player.dialogue.type.npc
import world.gregs.voidps.engine.Script
import world.gregs.voidps.engine.client.ui.open

class Kamfreena : Script {

    private val logger = InlineLogger()

    init {
        logger.info { "Kamfreena class loaded" }

        /**
         * Handles Kamfreena dialogue for cyclops room entry.
         * Ported from Darkan's implementation.
         */
        npcOperate("Talk-to", "kamfreena") {
            logger.info { "Kamfreena dialogue triggered" }
            val defender = WarriorsGuild.getBestDefender(this)
            if (defender == "bronze_defender") {
                npc<Neutral>("It seems that you do not have a defender.")
            } else {
                npc<Neutral>("Ah, I see that you have one of the defenders already! Well done.")
            }
            npc<Neutral>("I'll release some cyclopses that might drop the next defender for you. Have fun in there.")
            npc<Neutral>("Oh, and be careful; the cyclopses will occasionally summon a cyclossus. They are rather mean and can only be hurt with a rune or dragon defender.")
            open("warriors_guild_cyclops_selection")
        }
    }
}
