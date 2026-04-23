package content.minigame.warriors_guild

import world.gregs.voidps.engine.Script
import world.gregs.voidps.engine.data.definition.Areas
import world.gregs.voidps.engine.entity.character.player.Player
import world.gregs.voidps.engine.entity.character.player.skill.Skill
import world.gregs.voidps.engine.entity.character.setAnimation
import world.gregs.voidps.engine.entity.obj.GameObject
import world.gregs.voidps.engine.entity.obj.Objects
import world.gregs.voidps.engine.map.collision.CollisionStrategies
import world.gregs.voidps.type.random
import world.gregs.voidps.type.Tile
import java.util.concurrent.TimeUnit

class WarriorsGuildTimer : Script {

    companion object {
        private const val TICK_INTERVAL = 14
        private const val DUMMY_SWITCH_INTERVAL = 14
        private const val CATAPULT_INTERVAL = 14
        
        private val DUMMY_LOCATIONS = listOf(
            Tile(2860, 3549, 0),
            Tile(2860, 3547, 0),
            Tile(2859, 3545, 0),
            Tile(2857, 3545, 0),
            Tile(2855, 3546, 0),
            Tile(2855, 3548, 0),
            Tile(2856, 3550, 0),
            Tile(2858, 3550, 0)
        )
        
        private val DUMMY_ROTATIONS = listOf(1, 1, 2, 2, 3, 3, 0, 0)
        
        private val DUMMY_IDS = listOf(15624, 15625, 15626, 15627, 15628, 15629, 15630)
        
        private val CATAPULT_TARGET = Tile(2842, 3541, 1)
        private val CATAPULT_BASE = Tile(2842, 3550, 1)
        private val CATAPULT_ANIM = 4164
        private val CATAPULT_PROJECTILE_IDS = listOf(679, 680, 681, 682)
        
        private val DEFENCE_ANIMATIONS = listOf(4169, 4168, 4171, 4170)
    }

    private var tickCount = 0
    private var currentDummyIndex = 0
    private var projectileType = 0
    private var lastDummyUid = 0.0

    init {
        // Timer will be started/stopped by WarriorsGuild.kt via softTimers
        timerTick("wg_tick") {
            tickCount++
            
            if (tickCount % DUMMY_SWITCH_INTERVAL == 0) {
                switchDummy()
                fireCatapult()
                lastDummyUid += 0.000000001
            }
            
            Timer.CONTINUE
        }
    }

    private fun switchDummy() {
        currentDummyIndex = random.nextInt(DUMMY_LOCATIONS.size)
        val tile = DUMMY_LOCATIONS[currentDummyIndex]
        val rotation = DUMMY_ROTATIONS[currentDummyIndex]
        val dummyId = DUMMY_IDS.random()
        
        // Remove old dummy if exists
        // Spawn new temporary dummy
        // TODO: Implement temporary object spawning
    }

    private fun fireCatapult() {
        projectileType = random.nextInt(4)
        
        // Send catapult animation
        // TODO: Send object animation to catapult object (15616)
        
        // Send projectile
        // TODO: Send projectile from CATAPULT_BASE to CATAPULT_TARGET
        
        // Check for players in catapult area and process hits
        processCatapultHits()
    }

    private fun processCatapultHits() {
        // TODO: Get all players in catapult area
        // For each player, check if they're at the target tile and process hit
    }

    fun getCurrentDummyUid(): Double = lastDummyUid
    
    fun getProjectileType(): Int = projectileType
}
