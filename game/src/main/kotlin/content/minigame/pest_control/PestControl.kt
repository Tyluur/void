package content.minigame.pest_control

import com.github.michaelbull.logging.InlineLogger
import content.entity.combat.Combat.Companion.combat
import content.entity.combat.dead
import content.entity.combat.hit.hit
import content.entity.combat.underAttack
import content.quest.clearInstance
import content.quest.instanceOffset
import content.quest.setInstanceLogout
import content.quest.smallInstance
import world.gregs.config.Config
import world.gregs.voidps.engine.Script
import world.gregs.voidps.engine.client.message
import world.gregs.voidps.engine.entity.World
import world.gregs.voidps.engine.entity.character.mode.EmptyMode
import world.gregs.voidps.engine.entity.character.mode.combat.CombatMovement
import world.gregs.voidps.engine.entity.character.move.tele
import world.gregs.voidps.engine.entity.character.npc.NPCs
import world.gregs.voidps.engine.entity.character.player.Player
import world.gregs.voidps.engine.entity.character.player.chat.ChatType
import world.gregs.voidps.engine.entity.character.player.combatLevel
import world.gregs.voidps.engine.entity.character.player.name
import world.gregs.voidps.engine.entity.character.player.skill.Skill
import world.gregs.voidps.engine.timedLoad
import world.gregs.voidps.engine.timer.Timer
import world.gregs.voidps.engine.timer.toTicks
import world.gregs.voidps.type.Direction
import world.gregs.voidps.type.Region
import world.gregs.voidps.type.Tile
import world.gregs.voidps.type.random
import world.gregs.voidps.engine.map.collision.Collisions
import org.rsmod.game.pathfinder.flag.CollisionFlag
import world.gregs.voidps.engine.map.collision.check
import java.util.concurrent.TimeUnit

/**
 * Pest Control configuration data loaded from Groml config file.
 */
data class PestControlConfig(
    val difficulties: Map<String, DifficultyConfig>,
    val pestData: Map<String, PestDataConfig>,
    val locations: LocationConfig,
    val timers: TimerConfig,
    val limits: LimitConfig
)

data class DifficultyConfig(
    val combatRequirement: Int,
    val entryTileX: Int,
    val entryTileY: Int,
    val entryTileLevel: Int,
    val exitTileX: Int,
    val exitTileY: Int,
    val exitTileLevel: Int,
    val portalHealth: Int,
    val rewardPoints: Int,
    val portalShieldedIds: List<String>,
    val portalUnshieldedIds: List<String>,
    val knightHealth: Int = 250
)

data class PestDataConfig(
    val pests: List<String>, val shifters: List<String>
)

data class LocationConfig(
    val entranceX: Int,
    val entranceY: Int,
    val entranceLevel: Int,
    val regionId: Int,
    val knightOffset: TileOffset,
    val portals: Map<String, TileOffset>
)

data class TileOffset(
    val x: Int, val y: Int, val level: Int
)

data class TimerConfig(
    val lobby_timer_seconds: Int,
    val game_timer_seconds: Int,
    val pest_spawn_interval_seconds: Int,
    val first_shield_drop_seconds: Int,
    val subsequent_shield_drop_interval_seconds: Int
)

data class LimitConfig(
    val minPlayersToStart: Int, val knightHealth: Int, val maxPestsPerPortal: Int, val maxPestsNearKnight: Int
)

/**
 * Pest Control minigame implementation following void's patterns.
 *
 * This script manages the Pest Control minigame including:
 * - Lander entry with combat level and follower restrictions
 * - Centralized lobby management with countdown timer
 * - Game instance creation with proper void instance system
 * - Player logout and death handlers
 *
 * Based on TzhaarFightCave pattern for consistency with void's codebase.
 */
class PestControl : Script {
    companion object {
        private val log = InlineLogger()
    }

    // Debug flag - set to true to skip 30-second lobby timer and start game immediately
    private val DEBUG_MODE = true

    // Configuration loaded from Groml file
    private lateinit var config: PestControlConfig

    /**
     * Gets difficulty config by name.
     */
    private fun difficultyConfig(name: String): DifficultyConfig? = config.difficulties[name.lowercase()]

    /**
     * Gets pest data config by name.
     */
    private fun pestDataConfig(name: String): PestDataConfig? = config.pestData[name.lowercase()]

    private val activeGames = mutableMapOf<Player, PestGameData>()
    private val gameTimers = mutableMapOf<Player, Int>()
    private val pestSpawnTimers = mutableMapOf<Player, Int>()
    private val shieldDropTimers = mutableMapOf<Player, Int>()
    private val lobbies = mutableMapOf<String, MutableList<Player>>()
    private val lobbyTimers = mutableMapOf<String, Int>()

    init {
        // Handle void knight damage - allow damage from pests
        npcCombatDamage("void_knight") { damage ->
            log.info { "VOID KNIGHT DAMAGE HANDLER: source=${damage.source}, type=${damage.type}, damage=${damage.damage}" }
            log.info { "VOID KNIGHT DAMAGE HANDLER: Before damage - constitution=${this.levels.get(Skill.Constitution)}" }
            // Apply damage directly to the void knight
            this.levels.set(Skill.Constitution, this.levels.get(Skill.Constitution) - damage.damage)
            log.info { "VOID KNIGHT DAMAGE HANDLER: After damage - constitution=${this.levels.get(Skill.Constitution)}" }
        }

        // Re-open Pest Control overlay if it's closed while game is active
        interfaceClosed("pest_control_playing") {
            // Check if player is still in an active game
            if (this["pest_control_game_active"] as? Boolean == true) {
                val gameData = activeGames[this]
                if (gameData != null) {
                    this.interfaces.open("pest_control_playing")
                    updateGameInterface(this, gameData)
                }
            }
        }

        // Handle spinner taking damage - force it to prioritize healing over combat
        npcCombatDamage("spinner_*") { damage ->
            val spinner = this
            log.debug { "SPINNER COMBAT: Spinner ${spinner.id} took damage from ${damage.source}, type=${damage.type}, damage=${damage.damage}" }
            val portalIndex = spinner["portal_index"] as? Int ?: run {
                log.debug { "SPINNER COMBAT: No portal_index attribute on spinner ${spinner.id}" }
                return@npcCombatDamage
            }
            log.debug { "SPINNER COMBAT: portal_index=$portalIndex" }
            val gameData = activeGames.values.find { it.pests.contains(spinner) } ?: run {
                log.debug { "SPINNER COMBAT: No gameData found for spinner ${spinner.id}" }
                return@npcCombatDamage
            }
            val portal = gameData.portalNPCs[portalIndex]
            log.debug { "SPINNER COMBAT: portal=$portal, portal.index=${portal?.index}, portal.dead=${portal?.dead}" }

            // Spinners ALWAYS prioritize healing their portal, never engage in combat
            if (portal != null && portal.index != -1 && !portal.dead) {
                log.debug { "SPINNER COMBAT: Canceling combat on spinner ${spinner.id} and walking to portal $portalIndex" }
                log.debug { "SPINNER COMBAT: Current mode before cancel: ${spinner.mode}" }

                // Immediately cancel combat and walk toward portal
                spinner.mode = EmptyMode
                spinner.walkTo(portal.tile)

                log.debug { "SPINNER COMBAT: Mode after cancel: ${spinner.mode}, walking to ${portal.tile}" }
            } else {
                log.debug { "SPINNER COMBAT: Portal is null, index=-1, or dead, spinner can continue combat" }
            }
        }

        // Track player damage to NPCs for Pest Control interface
        npcCombatDamage { damage ->
            val source = damage.source
            val target = this
            // Only track if source is a player
            if (source !is Player) {
                return@npcCombatDamage
            }
            // Find if this NPC is part of an active Pest Control game
            val gameData = activeGames.values.find { game ->
                game.portalNPCs.contains(target) || game.players.any { player ->
                    player.tile.region == target.tile.region
                }
            }
            if (gameData == null) {
                return@npcCombatDamage
            }
            // Only track damage to pests and portals, not void knight ( pests damage void knight, not players)
            if (target.id == "void_knight") {
                return@npcCombatDamage
            }
            // Update player damage tracking
            val currentDamage = gameData.playerDamage[source] ?: 0
            gameData.playerDamage[source] = currentDamage + damage.damage
            log.debug { "Player ${source.name} dealt ${damage.damage} damage to ${target.id}, total: ${gameData.playerDamage[source]}" }
            // Update interface for this player
            updateGameInterface(source, gameData)
        }

        // Handle portal damage to update interface
        npcCombatDamage("portal_*") { damage ->
            log.info { "Portal damaged: ${this.id}, damage: $damage" }
            val portalIndex = this["portal_index"] as? Int ?: run {
                log.warn { "Portal has no portal_index attribute: ${this.id}" }
                return@npcCombatDamage
            }
            log.info { "Portal index: $portalIndex" }
            val gameData = activeGames.values.find { it.portalNPCs[portalIndex] == this } ?: run {
                log.warn { "No game data found for portal index $portalIndex" }
                return@npcCombatDamage
            }

            // Portals can only be damaged when shield is down
            if (gameData.portalShielded[portalIndex]) {
                log.info { "Portal $portalIndex shield is active, ignoring damage" }
                return@npcCombatDamage // Shield is active, ignore damage
            }

            // Update portal health
            val currentHP = gameData.portalHealth[portalIndex]
            val damageInt = when (damage) {
                else -> damage.damage
            }
            gameData.portalHealth[portalIndex] = maxOf(0, currentHP - damageInt)
            log.info { "Portal $portalIndex health: $currentHP -> ${gameData.portalHealth[portalIndex]} (damage: $damageInt)" }

            // Update this NPC's hitpoints
            this["hitpoints"] = gameData.portalHealth[portalIndex]

            // Update interface for all players
            for (player in gameData.players) {
                updateGameInterface(player, gameData)
            }

            // Check if portal destroyed
            if (gameData.portalHealth[portalIndex] <= 0) {
                NPCs.remove(this)
                gameData.portalNPCs[portalIndex] = null
                gameData.portalsDestroyed++
                gameData.shieldsDropped++ // Count as destroyed for win condition

                // Restore 50 HP to Void Knight when portal destroyed
                gameData.knightHealth = minOf(gameData.knightHealth + 50, gameData.difficultyConfig.knightHealth)

                // Explode all spinners associated with this portal
                val spinnersToExplode = gameData.pests.filter { it.id.contains("spinner") && (it["portal_index"] as? Int) == portalIndex }
                for (spinner in spinnersToExplode) {
                    // Damage nearby players
                    for (player in gameData.players) {
                        if (spinner.tile.distanceTo(player.tile) <= 1) {
                            // Apply poison damage to nearby players
                            hit(player, offensiveType = "poison", damage = 5)
                            // Apply poison effect (if poison system exists)
                        }
                    }
                    // Remove spinner
                    NPCs.remove(spinner)
                    gameData.pests.remove(spinner)
                }

                // Update interface to show knight health restoration
                for (player in gameData.players) {
                    updateGameInterface(player, gameData)
                }

                // Check if all portals are destroyed - win condition
                if (gameData.portalsDestroyed >= 4) {
                    endGame(gameData, won = true)
                }
            }
        }
    }

    // Instance configuration (loaded from config)
    private val pestRegion: Region
    private val entrance: Tile
    private val portalOffsets: List<Tile>
    private val knightOffset: Tile
    private val lobbyTimerSeconds: Int
    private val gameTimerSeconds: Int
    private val pestSpawnIntervalSeconds: Int
    private val firstShieldDropSeconds: Int
    private val subsequentShieldDropIntervalSeconds: Int
    private val minPlayersToStart: Int
    private val knightHealth: Int
    private val maxPestsPerPortal: Int
    private val maxPestsNearKnight: Int

    /**
     * Game state data for a Pest Control game instance.
     *
     * @property difficultyName The difficulty level name
     * @property difficultyConfig The difficulty configuration
     * @property pestDataConfig The pest data configuration
     * @property players List of players in the game
     * @property knightHealth Current health of the Void Knight
     * @property portalHealth Health of each portal [purple, blue, yellow, red]
     * @property portalsDestroyed Number of portals destroyed
     * @property timeRemaining Remaining game time in seconds
     * @property portalBaseHealth Base health for portals
     * @property playerDamage Damage dealt by each player
     * @property pestCounts Number of pests at each location [purple, blue, yellow, red, knight]
     * @property portalShielded Shield state of each portal [purple, blue, yellow, red]
     * @property shieldsDropped Number of shields dropped
     * @property portalNPCs The portal NPC entities [purple, blue, yellow, red]
     * @property instanceTile The world tile where the instance region starts
     */
    data class PestGameData(
        val difficultyName: String,
        val difficultyConfig: DifficultyConfig,
        val pestDataConfig: PestDataConfig,
        val players: MutableList<Player>,
        var knightHealth: Int,
        val portalHealth: MutableList<Int>,
        var portalsDestroyed: Int = 0,
        var timeRemaining: Int,
        val portalBaseHealth: Int,
        val playerDamage: MutableMap<Player, Int> = mutableMapOf(),
        val pestCounts: MutableList<Int> = mutableListOf(0, 0, 0, 0, 0), // 4 portals + knight
        val portalShielded: MutableList<Boolean> = mutableListOf(true, true, true, true), // All portals start shielded
        var shieldsDropped: Int = 0,
        val portalNPCs: MutableList<world.gregs.voidps.engine.entity.character.npc.NPC?> = mutableListOf(
            null, null, null, null
        ),
        var instanceTile: Tile? = null,
        var knightNPC: world.gregs.voidps.engine.entity.character.npc.NPC? = null,
        val pests: MutableList<world.gregs.voidps.engine.entity.character.npc.NPC> = mutableListOf()
    )

    init {
        // Load configuration from Groml file
        val difficulties = mutableMapOf<String, DifficultyConfig>()
        val pestData = mutableMapOf<String, PestDataConfig>()
        var locations: LocationConfig? = null
        var timers: TimerConfig? = null
        var limits: LimitConfig? = null

        timedLoad("pest control config") {
            Config.fileReader("./data/minigame/pest_control/pest_control.config.toml") {

                while (nextSection()) {
                    when (val section = section()) {
                        "difficulty.novice", "difficulty.intermediate", "difficulty.veteran" -> {
                            val diffName = section.substringAfter(".")
                            var combatReq = 0
                            var entryX = 0
                            var entryY = 0
                            var entryLevel = 0
                            var exitX = 0
                            var exitY = 0
                            var exitLevel = 0
                            var portalHealth = 0
                            var rewardPoints = 0
                            var shieldedIds = emptyList<String>()
                            var unshieldedIds = emptyList<String>()
                            var knightHp = 250

                            while (nextPair()) {
                                when (key()) {
                                    "combat_requirement" -> combatReq = int()
                                    "entry_tile_x" -> entryX = int()
                                    "entry_tile_y" -> entryY = int()
                                    "entry_tile_level" -> entryLevel = int()
                                    "exit_tile_x" -> exitX = int()
                                    "exit_tile_y" -> exitY = int()
                                    "exit_tile_level" -> exitLevel = int()
                                    "portal_health" -> portalHealth = int()
                                    "reward_points" -> rewardPoints = int()
                                    "portal_shielded_ids" -> shieldedIds = list().map { it as String }
                                    "portal_unshielded_ids" -> unshieldedIds = list().map { it as String }
                                }
                            }
                            difficulties[diffName] = DifficultyConfig(
                                combatReq,
                                entryX,
                                entryY,
                                entryLevel,
                                exitX,
                                exitY,
                                exitLevel,
                                portalHealth,
                                rewardPoints,
                                shieldedIds,
                                unshieldedIds,
                                knightHp
                            )
                        }

                        "pest_data.novice", "pest_data.intermediate", "pest_data.veteran" -> {
                            val pestName = section.substringAfter(".")
                            var pests = emptyList<String>()
                            var shifters = emptyList<String>()

                            while (nextPair()) {
                                when (key()) {
                                    "pests" -> pests = list().map { it as String }
                                    "shifters" -> shifters = list().map { it as String }
                                }
                            }
                            pestData[pestName] = PestDataConfig(pests, shifters)
                        }

                        "locations" -> {
                            var entranceX = 0
                            var entranceY = 0
                            var entranceLevel = 0
                            var regionId = 0
                            var knightOffset: TileOffset? = null
                            val portals = mutableMapOf<String, TileOffset>()

                            while (nextPair()) {
                                when (key()) {
                                    "entrance_x" -> entranceX = int()
                                    "entrance_y" -> entranceY = int()
                                    "entrance_level" -> entranceLevel = int()
                                    "region_id" -> regionId = int()
                                }
                            }

                            // Read knight offset section
                            if (nextSection() && section() == "locations.knight_offset") {
                                var kx = 0
                                var ky = 0
                                var klevel = 0
                                while (nextPair()) {
                                    when (key()) {
                                        "x" -> kx = int()
                                        "y" -> ky = int()
                                        "level" -> klevel = int()
                                    }
                                }
                                knightOffset = TileOffset(kx, ky, klevel)
                            }

                            // Read portal sections
                            for (portalName in listOf("purple", "blue", "yellow", "red")) {
                                if (nextSection() && section() == "locations.portals.$portalName") {
                                    var px = 0
                                    var py = 0
                                    var plevel = 0
                                    while (nextPair()) {
                                        when (key()) {
                                            "x" -> px = int()
                                            "y" -> py = int()
                                            "level" -> plevel = int()
                                        }
                                    }
                                    portals[portalName] = TileOffset(px, py, plevel)
                                }
                            }

                            locations = LocationConfig(
                                entranceX, entranceY, entranceLevel, regionId, knightOffset!!, portals
                            )
                        }

                        "timers" -> {
                            var lobby = 30
                            var game = 1200
                            var pestSpawn = 10
                            var firstShieldDrop = 15
                            var subsequentShieldDrop = 30

                            while (nextPair()) {
                                when (key()) {
                                    "lobby_timer_seconds" -> lobby = int()
                                    "game_timer_seconds" -> game = int()
                                    "pest_spawn_interval_seconds" -> pestSpawn = int()
                                    "first_shield_drop_seconds" -> firstShieldDrop = int()
                                    "subsequent_shield_drop_interval_seconds" -> subsequentShieldDrop = int()
                                }
                            }
                            timers = TimerConfig(lobby, game, pestSpawn, firstShieldDrop, subsequentShieldDrop)
                        }

                        "limits" -> {
                            var minPlayers = 1
                            var knightHp = 250
                            var maxPestsPortal = 15
                            var maxPestsKnight = 4

                            while (nextPair()) {
                                when (key()) {
                                    "min_players_to_start" -> minPlayers = int()
                                    "knight_health" -> knightHp = int()
                                    "max_pests_per_portal" -> maxPestsPortal = int()
                                    "max_pests_near_knight" -> maxPestsKnight = int()
                                }
                            }
                            limits = LimitConfig(minPlayers, knightHp, maxPestsPortal, maxPestsKnight)
                        }
                    }
                }

            }
            difficulties.size
        }

        config = PestControlConfig(difficulties, pestData, locations!!, timers!!, limits!!)

        // Log loaded config information
        log.info { "Pest Control Loaded configuration:" }
        log.info { "  Difficulties: ${config.difficulties.keys.joinToString(", ")}" }
        for ((diffName, diffConfig) in config.difficulties) {
            log.info { "  $diffName: combat=${diffConfig.combatRequirement}, portalHealth=${diffConfig.portalHealth}, reward=${diffConfig.rewardPoints}" }
        }
        log.info { "  Pest data: ${config.pestData.keys.joinToString(", ")}" }
        for ((pestName, pestConfig) in config.pestData) {
            log.info { "  $pestName: ${pestConfig.pests.size} pests, ${pestConfig.shifters.size} shifters" }
        }
        log.info { "  Region: ${config.locations.regionId}, Entrance: (${config.locations.entranceX}, ${config.locations.entranceY})" }
        log.info { "  Timers: lobby=${config.timers.lobby_timer_seconds}s, game=${config.timers.game_timer_seconds}s, spawn=${config.timers.pest_spawn_interval_seconds}s" }
        log.info { "  Limits: minPlayers=${config.limits.minPlayersToStart}, knightHealth=${config.limits.knightHealth}, maxPestsPerPortal=${config.limits.maxPestsPerPortal}" }

        // Initialize instance configuration from config
        pestRegion = Region(config.locations.regionId)
        entrance = Tile(config.locations.entranceX, config.locations.entranceY, config.locations.entranceLevel)
        knightOffset =
            Tile(config.locations.knightOffset.x, config.locations.knightOffset.y, config.locations.knightOffset.level)
        portalOffsets = listOf(
            Tile(
                config.locations.portals["purple"]!!.x,
                config.locations.portals["purple"]!!.y,
                config.locations.portals["purple"]!!.level
            ), Tile(
                config.locations.portals["blue"]!!.x,
                config.locations.portals["blue"]!!.y,
                config.locations.portals["blue"]!!.level
            ), Tile(
                config.locations.portals["yellow"]!!.x,
                config.locations.portals["yellow"]!!.y,
                config.locations.portals["yellow"]!!.level
            ), Tile(
                config.locations.portals["red"]!!.x,
                config.locations.portals["red"]!!.y,
                config.locations.portals["red"]!!.level
            )
        )
        lobbyTimerSeconds = config.timers.lobby_timer_seconds
        gameTimerSeconds = config.timers.game_timer_seconds
        pestSpawnIntervalSeconds = config.timers.pest_spawn_interval_seconds
        firstShieldDropSeconds = config.timers.first_shield_drop_seconds
        subsequentShieldDropIntervalSeconds = config.timers.subsequent_shield_drop_interval_seconds
        minPlayersToStart = config.limits.minPlayersToStart
        knightHealth = config.limits.knightHealth
        maxPestsPerPortal = config.limits.maxPestsPerPortal
        maxPestsNearKnight = config.limits.maxPestsNearKnight

        // Initialize lobbies for each difficulty
        for (difficultyName in config.difficulties.keys) {
            lobbies[difficultyName] = mutableListOf()
            lobbyTimers[difficultyName] = lobbyTimerSeconds
        }

        // Gangplank entry (default to novice)
        objectOperate("Cross", "pest_control_gangplank_enter") {
            this.enterLander("novice")
        }
        // Alternative gangplank entry for novice lander
        objectOperate("Cross", "pest_control_gangplank_novice") {
            this.enterLander("novice")
        }

        // Exit gangplank from lander
        objectOperate("Cross", "pest_control_gangplank_exit") {
            handleExitLander(this)
        }

        // Ladder exit from lander
        objectOperate("Climb", "pest_control_ladder_exit") {
            handleExitLander(this)
        }

        // Squire talk-to for each difficulty
        npcOperate("Talk-to", "squire_novice_pest_control") {
            this.enterLander("novice")
        }
        npcOperate("Talk-to", "squire_intermediate_pest_control") {
            this.enterLander("intermediate")
        }
        npcOperate("Talk-to", "squire_veteran_pest_control") {
            this.enterLander("veteran")
        }

        // Start world timers
        worldSpawn {
            World.timers.start("pest_control_lobby")
            World.timers.start("pest_control_game")
        }

        // Lobby timer - centralized countdown
        worldTimerStart("pest_control_lobby") { TimeUnit.SECONDS.toTicks(1) }
        worldTimerTick("pest_control_lobby") {
            updateLobbyTimers()
            return@worldTimerTick Timer.CONTINUE
        }

        // Game timer - countdown and game logic
        worldTimerStart("pest_control_game") { TimeUnit.SECONDS.toTicks(1) }
        worldTimerTick("pest_control_game") {
            updateGameTimers()
            return@worldTimerTick Timer.CONTINUE
        }

        // Player event handlers
        // Handle pest death - clean up pest counts and remove from tracking
        npcDeath("*") {
            val spawnIndex = this["pest_control_spawn_index", -1]
            if (spawnIndex != -1) {
                for (gameData in activeGames.values) {
                    if (gameData.pests.contains(this)) {
                        gameData.pestCounts[spawnIndex] = maxOf(0, gameData.pestCounts[spawnIndex] - 1)
                        gameData.pests.remove(this)
                        log.info { "Pest ${this.id} died at location $spawnIndex (count: ${gameData.pestCounts[spawnIndex]})" }
                        break
                    }
                }
            }
        }

        playerLogout(::handleLogout)
        playerDeath {
            val difficultyName = get("pest_control_difficulty", "")

            if (get("pest_control_in_lobby", false)) {
                val lobby = lobbies[difficultyName]
                if (lobby != null) {
                    synchronized(lobby) {
                        lobby.remove(this)
                    }
                }
                this.interfaces.close("pest_control_waiting")
                this.clear("pest_control_difficulty")
                this.clear("pest_control_in_lobby")
            }

            if (get("pest_control_game_active", false)) {
                val gameData = activeGames[this]
                if (gameData != null) {
                    // Teleport player to entrance point instead of removing them
                    val diffConfig = difficultyConfig(difficultyName)
                    if (diffConfig != null) {
                        val entranceTile = Tile(diffConfig.exitTileX, diffConfig.exitTileY, diffConfig.exitTileLevel)
                        tele(entranceTile)
                        message("Oh dear, you died! You have been returned to the entrance.", ChatType.Game)
                    }
                    // Keep player in the game, don't remove them
                    // Don't end the game if players die - only end if knight dies or time runs out
                }
                // Don't remove player from active games - they can re-enter
                // Just clear their game state so they can re-enter
                this.interfaces.close("pest_control_playing")
                this.clear("pest_control_game_active")
            }
        }
    }

    /**
     * Handles player entering the Pest Control lander.
     *
     * Checks combat level requirement and follower restriction before adding player to lobby.
     *
     * @param player The player attempting to enter
     * @param difficultyName The difficulty level name being entered
     */
    private fun Player.enterLander(difficultyName: String) {
        val diffConfig = difficultyConfig(difficultyName) ?: run {
            message("Invalid difficulty: $difficultyName", ChatType.Game)
            return
        }

        if (combatLevel < diffConfig.combatRequirement) {
            message(
                "You need a combat level of ${diffConfig.combatRequirement} or more to enter this boat.", ChatType.Game
            )
            return
        }

        // Check for follower/familiar
        val followerIndex = get("follower_index", -1)
        if (followerIndex != -1 && NPCs.indexed(followerIndex) != null) {
            message("You can't take a follower into the lander, there isn't enough room!", ChatType.Game)
            return
        }

        // Add to centralized lobby
        val lobby = lobbies[difficultyName] ?: return
        synchronized(lobby) {
            if (lobby.isEmpty()) {
                lobbyTimers[difficultyName] = if (DEBUG_MODE) 1 else lobbyTimerSeconds
            }
            lobby.add(this)
        }

        // Teleport to lander
        val entryTile = Tile(diffConfig.entryTileX, diffConfig.entryTileY, diffConfig.entryTileLevel)
        tele(entryTile)
        message("You board the lander.", ChatType.Game)

        // Open lobby interface
        this.interfaces.open("pest_control_waiting")
        updateLanderInterface(this, difficultyName)

        // Set player state
        this["pest_control_difficulty"] = difficultyName
        this["pest_control_in_lobby"] = true
    }

    /**
     * Updates the lander waiting interface for a player.
     *
     * @param player The player to update
     * @param difficultyName The difficulty name of the lander
     */
    private fun updateLanderInterface(player: Player, difficultyName: String) {
        val lobby = lobbies[difficultyName] ?: return
        val timer = lobbyTimers[difficultyName] ?: lobbyTimerSeconds
        synchronized(lobby) {
            val minutesLeft = timer / 60
            player.interfaces.sendText(
                "pest_control_waiting", "title", difficultyName.lowercase().replaceFirstChar { it.uppercase() })
            player.interfaces.sendText(
                "pest_control_waiting",
                "departure",
                "Next Departure: $minutesLeft minutes ${if (minutesLeft % 2 != 0) "30 seconds" else ""}"
            )
            player.interfaces.sendText("pest_control_waiting", "players_ready", "Player's Ready: ${lobby.size}")
            player.interfaces.sendText(
                "pest_control_waiting", "commendations", "Commendations: ${player["pest_control_points", 0]}"
            )
        }
    }

    /**
     * Handles player exiting the lander via gangplank.
     *
     * Removes player from lobby and teleports them back.
     *
     * @param player The player exiting
     */
    private fun handleExitLander(player: Player) {
        val difficultyName = player.get("pest_control_difficulty", "")
        val diffConfig = difficultyConfig(difficultyName) ?: return

        if (player["pest_control_in_lobby", false]) {
            val lobby = lobbies[difficultyName]
            if (lobby != null) {
                synchronized(lobby) {
                    lobby.remove(player)
                }
            }
            player.interfaces.close("pest_control_waiting")
            player.clear("pest_control_difficulty")
            player.clear("pest_control_in_lobby")
            return
        }
        player.interfaces.close("pest_control_waiting")
        val exitTile = Tile(diffConfig.exitTileX, diffConfig.exitTileY, diffConfig.exitTileLevel)
        player.tele(exitTile)
        player.message("You leave the lander.", ChatType.Game)
    }

    /**
     * Updates lobby timers and starts games when conditions are met.
     *
     * Called every second by the timer system.
     * Starts game when timer reaches 0 and there are enough players.
     */
    private fun updateLobbyTimers() {
        for (difficultyName in config.difficulties.keys) {
            val lobby = lobbies[difficultyName] ?: continue
            val timer = lobbyTimers[difficultyName] ?: continue

            synchronized(lobby) {
                if (lobby.isEmpty()) {
                    lobbyTimers[difficultyName] = if (DEBUG_MODE) 1 else lobbyTimerSeconds
                    return@synchronized
                }

                lobbyTimers[difficultyName] = timer - 1

                if (timer == 0) {
                    if (lobby.size >= minPlayersToStart) {
                        startGame(difficultyName)
                    } else {
                        lobbyTimers[difficultyName] = lobbyTimerSeconds
                    }
                }

                // Update interface for all players in lobby
                for (player in lobby.toList()) {
                    updateLanderInterface(player, difficultyName)
                }
            }
        }
    }

    /**
     * Starts a Pest Control game instance for the given difficulty.
     *
     * Creates a dynamic instance following void's pattern:
     * player.smallInstance → delay(1) → player.instanceOffset → player.tele
     *
     * @param difficultyName The difficulty name to start
     */
    private fun startGame(difficultyName: String) {
        val lobby = lobbies[difficultyName] ?: return
        val players: List<Player>
        val diffConfig = difficultyConfig(difficultyName) ?: return
        val pestConfig = pestDataConfig(difficultyName) ?: return

        synchronized(lobby) {
            players = lobby.toList()
            lobby.clear()
            lobbyTimers[difficultyName] = lobbyTimerSeconds
        }

        // Create game data for this instance
        val gameData = PestGameData(
            difficultyName = difficultyName,
            difficultyConfig = diffConfig,
            pestDataConfig = pestConfig,
            players = players.toMutableList(),
            knightHealth = knightHealth,
            portalHealth = mutableListOf(
                diffConfig.portalHealth, diffConfig.portalHealth, diffConfig.portalHealth, diffConfig.portalHealth
            ),
            timeRemaining = gameTimerSeconds,
            portalBaseHealth = diffConfig.portalHealth,
            playerDamage = mutableMapOf()
        )

        // Initialize player damage tracking
        for (player in players) {
            gameData.playerDamage[player] = 0
        }

        // Store instance tile for NPC spawning
        var instanceTile: Tile? = null
        val exitTile = Tile(diffConfig.exitTileX, diffConfig.exitTileY, diffConfig.exitTileLevel)

        for (player in players) {
            // Create instance following TzhaarFightCave pattern
            val instance = player.smallInstance(pestRegion, 3)
            if (instanceTile == null) {
                instanceTile = instance.tile
                gameData.instanceTile = instanceTile
                log.info { "Instance tile: $instanceTile" }
            }
            val playerOffset = player.instanceOffset()
            player.tele(entrance.add(playerOffset))
            player.setInstanceLogout(exitTile)

            // Close waiting interface and open game interface
            player.interfaces.close("pest_control_waiting")
            player.interfaces.open("pest_control_playing")
            updateGameInterface(player, gameData)

            // Set player state
            player["pest_control_difficulty"] = difficultyName
            player["pest_control_game_active"] = true
            player.remove<String>("pest_control_in_lobby")

            // Track game state per player
            activeGames[player] = gameData
            gameTimers[player] = gameData.timeRemaining
            pestSpawnTimers[player] = pestSpawnIntervalSeconds
            shieldDropTimers[player] = firstShieldDropSeconds

            player.message("Pest Control game starting!", ChatType.Game)
        }

        // Spawn NPCs in the instance after instances are created
        if (instanceTile != null) {
            spawnGameNPCs(gameData, instanceTile)
        }

        // Debug mode: drop all shields and spawn all pests immediately
        if (DEBUG_MODE) {
            log.info { "DEBUG MODE: Dropping all shields and spawning all pests immediately" }
            // Drop all 4 shields
            repeat(4) {
                dropPortalShield(gameData)
            }
            // Spawn all pests to max capacity (limit to avoid infinite loop)
            val maxTotalPests = (maxPestsPerPortal * 4) + maxPestsNearKnight
            var attempts = 0
            while (gameData.pests.size < maxTotalPests && attempts < 200) {
                spawnPests(gameData)
                attempts++
            }
            log.info { "DEBUG MODE: Spawned ${gameData.pests.size} pests in $attempts attempts" }
            for (player in players) {
                player.message("DEBUG MODE: All shields dropped and ${gameData.pests.size} pests spawned!", ChatType.Game)
            }
        }
    }

    /**
     * Spawns portals and Void Knight for the game.
     *
     * @param gameData The game state data
     * @param instanceTile The world tile where the instance region starts
     */
    private fun spawnGameNPCs(gameData: PestGameData, instanceTile: Tile) {
        log.info { "Spawning NPCs with instance tile: $instanceTile" }

        // Spawn Void Knight at instance base + relative offset
        // Direct coordinate addition to avoid Delta wrapping issues with negative values
        val knightTile = Tile(instanceTile.x + knightOffset.x, instanceTile.y + knightOffset.y, instanceTile.level)
        log.info { "Spawning Void Knight at: $knightTile" }
        val knight = NPCs.add("void_knight", knightTile, Direction.SOUTH)
        knight["hitpoints"] = gameData.knightHealth
        gameData.knightNPC = knight
        log.info { "Void Knight spawned: ${knight.index != -1}" }

        // Spawn portals (purple, blue, yellow, red) - all start shielded
        for (i in portalOffsets.indices) {
            val portalTile =
                Tile(instanceTile.x + portalOffsets[i].x, instanceTile.y + portalOffsets[i].y, instanceTile.level)
            val portalId = gameData.difficultyConfig.portalShieldedIds[i]
            log.info { "Spawning $portalId at: $portalTile" }
            val portal = NPCs.add(portalId, portalTile, Direction.SOUTH)
            portal["hitpoints"] = gameData.portalBaseHealth
            portal["portal_index"] = i
            gameData.portalHealth[i] = gameData.portalBaseHealth
            gameData.portalNPCs[i] = portal
            log.info { "Portal spawned: ${portal.index != -1}" }
        }
    }

    /**
     * Updates game timers and checks win/lose conditions.
     * Called every second by the game timer.
     */
    private fun updateGameTimers() {
        val gamesToRemove = mutableListOf<Player>()

        for ((player, gameData) in activeGames) {
            val timer = gameTimers[player] ?: continue
            val pestTimer = pestSpawnTimers[player] ?: continue
            val shieldTimer = shieldDropTimers[player] ?: continue

            // Decrement timer
            gameTimers[player] = timer - 1
            gameData.timeRemaining = timer - 1

            // Decrement pest spawn timer
            pestSpawnTimers[player] = pestTimer - 1

            // Decrement shield drop timer
            shieldDropTimers[player] = shieldTimer - 1

            // Spawn pests when timer reaches 0
            if (pestTimer <= 0) {
                spawnPests(gameData)
                pestSpawnTimers[player] = pestSpawnIntervalSeconds
            }

            // Spinners prioritize healing their assigned portal - ALWAYS walk to portal, only heal if damaged
            for (pest in gameData.pests) {
                if (pest.index == -1 || pest.dead) continue
                if (!pest.id.contains("spinner")) continue

                log.debug { "SPINNER HEAL: Processing spinner ${pest.id}" }
                val portalIndex = pest["portal_index"] as? Int ?: run {
                    log.debug { "SPINNER HEAL: No portal_index on spinner ${pest.id}" }
                    continue
                }
                log.debug { "SPINNER HEAL: portal_index=$portalIndex" }
                val portal = gameData.portalNPCs[portalIndex]
                if (portal == null || portal.index == -1 || portal.dead) {
                    log.debug { "SPINNER HEAL: Portal is null, index=-1, or dead for spinner ${pest.id}" }
                    // Portal dead - spinner should explode, handled elsewhere
                    continue
                }

                val portalMaxHP = gameData.portalBaseHealth
                val currentHP = gameData.portalHealth[portalIndex]
                val portalLocked = gameData.shieldsDropped <= portalIndex

                log.debug { "SPINNER HEAL: portal HP=$currentHP/$portalMaxHP, portalLocked=$portalLocked, shieldsDropped=${gameData.shieldsDropped}, mode=${pest.mode}" }

                // Spinners ALWAYS cancel combat and walk toward their portal (regardless of HP state)
                // Only cancel combat mode, don't interrupt movement
                if (pest.mode is CombatMovement) {
                    log.debug { "SPINNER HEAL: Canceling combat on spinner ${pest.id}" }
                    pest.mode = EmptyMode
                }
                val distance = pest.tile.distanceTo(portal.tile)
                log.debug { "SPINNER HEAL: Distance to portal=$distance" }

                if (distance <= 5) { // 2009scape uses 5 tiles range
                    // Only actually heal if portal is damaged and not locked
                    if (!portalLocked && currentHP < portalMaxHP) {
                        val healCounter = pest["healCounter"] as? Int ?: 0
                        pest["healCounter"] = healCounter + 1
                        log.debug { "SPINNER HEAL: healCounter=$healCounter -> ${healCounter + 1}" }
                        if (healCounter >= 5) { // heal every 6 ticks (~3.6s)
                            pest.face(portal.tile)
                            val healAmount = (portalMaxHP * 0.10).toInt() // Heal 10% of max HP (matches 2009scape)
                            val newHP = minOf(portalMaxHP, currentHP + healAmount)
                            gameData.portalHealth[portalIndex] = newHP
                            // Update both the attribute and the actual Constitution level (capped at max)
                            portal["hitpoints"] = newHP
                            portal.levels.set(Skill.Constitution, minOf(portalMaxHP, newHP))
                            pest.anim("spinner_heal")
                            pest.gfx("spinner_heal_graphics")
                            pest["healCounter"] = 0
                            log.debug { "SPINNER HEAL: Healed portal by $healAmount HP, new HP=$newHP/$portalMaxHP" }
                            for (p in gameData.players) {
                                updateGameInterface(p, gameData)
                            }
                        } else {
                            log.debug { "SPINNER HEAL: Not healing yet, waiting for healCounter >= 5" }
                        }
                    } else {
                        // Portal at full HP or locked - reset healCounter to prevent instant heal when damaged
                        pest["healCounter"] = 0
                        log.debug { "SPINNER HEAL: Portal not damaged or locked, waiting near portal (locked=$portalLocked, HP=$currentHP/$portalMaxHP)" }
                    }
                } else {
                    log.debug { "SPINNER HEAL: Not in range, walking to portal" }
                    pest.walkTo(portal.tile)
                    // Reset healCounter when not in range to prevent instant heal when arriving
                    pest["healCounter"] = 0
                }
            }

            // Make pests attack the Void Knight (matrix4-style: 33% chance to target knight, otherwise target players)
            val knight = gameData.knightNPC
            if (knight != null && knight.index != -1) {
                gameData.pests.removeAll { it.index == -1 || it.dead }
                log.debug { "PEST TARGETING: Processing ${gameData.pests.size} active pests, knight=$knight, knight.index=${knight.index}, knight.dead=${knight.dead}" }
                for (pest in gameData.pests) {
                    if (pest.index == -1 || pest.dead) continue
                    val alreadyTargetingKnight =
                        pest.mode is CombatMovement && (pest.mode as CombatMovement).target == knight
                    if (alreadyTargetingKnight) {
                        log.debug { "PEST TARGETING: Pest ${pest.id} already targeting knight, skipping" }
                        continue
                    }
                    val pestId = pest.id
                    // Skip spinners - they prioritize healing their portal
                    if (pestId.contains("spinner")) {
                        log.debug { "PEST TARGETING: Pest ${pest.id} is spinner, skipping (prioritizes portal healing)" }
                        continue
                    }
                    
                    // Matrix4-style targeting: 33% chance to target knight, otherwise target nearby players
                    val targetKnight = random.nextInt(3) == 0
                    log.debug { "PEST TARGETING: Pest ${pest.id} roll: targetKnight=$targetKnight (random=${random.nextInt(3)}), pest.mode=${pest.mode}, pest.underAttack=${pest.underAttack}" }
                    
                    if (targetKnight) {
                        log.debug { "PEST TARGETING: Directing pest ${pest.id} to attack void_knight" }
                        combat(pest, knight)
                    } else {
                        // Find and attack nearby players
                        val nearbyPlayer = gameData.players.firstOrNull { player ->
                            !player.dead && player.tile.distanceTo(pest.tile) <= 10
                        }
                        if (nearbyPlayer != null) {
                            log.debug { "PEST TARGETING: Directing pest ${pest.id} to attack player ${nearbyPlayer.name} (distance=${pest.tile.distanceTo(nearbyPlayer.tile)})" }
                            combat(pest, nearbyPlayer)
                        } else {
                            log.debug { "PEST TARGETING: Pest ${pest.id} found no nearby players within distance 10" }
                        }
                    }
                }
            } else {
                log.debug { "PEST TARGETING: No valid knight to target (knight=$knight, knight.index=${knight?.index})" }
            }

            // Spawn pests when timer reaches 0
            if (shieldTimer <= 0 && gameData.shieldsDropped < 4) {
                dropPortalShield(gameData)
                // Use subsequent interval after first shield drops
                shieldDropTimers[player] = subsequentShieldDropIntervalSeconds
            }

            // Update knight health from actual NPC constitution levels
            if (knight != null && knight.index != -1) {
                gameData.knightHealth = knight.levels.get(Skill.Constitution)
            }

            // Update interface for all players in this game
            for (p in gameData.players) {
                updateGameInterface(p, gameData)
            }

            // Check win/lose conditions
            if (timer <= 0) {
                // Time's up - lose
                endGame(gameData, false)
                gamesToRemove.addAll(gameData.players)
            } else if (gameData.knightHealth <= 0) {
                // Knight died - lose
                endGame(gameData, false)
                gamesToRemove.addAll(gameData.players)
            } else if (gameData.portalsDestroyed >= 4) {
                // All portals destroyed - win
                endGame(gameData, true)
                gamesToRemove.addAll(gameData.players)
            }
        }

        // Clean up completed games
        for (player in gamesToRemove) {
            activeGames.remove(player)
            gameTimers.remove(player)
            pestSpawnTimers.remove(player)
            shieldDropTimers.remove(player)
        }
    }

    /**
     * Drops a portal shield, transforming it from shielded to unshielded.
     * Based on 2009scape's removePortalShield logic.
     *
     * @param gameData The game state data
     */
    private fun dropPortalShield(gameData: PestGameData) {
        val portalIndex = gameData.shieldsDropped
        if (portalIndex >= 4) return

        // Get the unshielded portal ID
        val unshieldedId = gameData.difficultyConfig.portalUnshieldedIds[portalIndex]

        // Get the portal NPC from stored references
        val portal = gameData.portalNPCs[portalIndex]
        if (portal != null) {
            log.info { "Dropping shield for portal at index $portalIndex, replacing with $unshieldedId" }
            // Remove the shielded portal and spawn unshielded version
            NPCs.remove(portal)
            val portalTile = portal.tile
            val newPortal = NPCs.add(unshieldedId, portalTile, Direction.SOUTH)
            newPortal["hitpoints"] = gameData.portalHealth[portalIndex]
            newPortal["portal_index"] = portalIndex
            gameData.portalNPCs[portalIndex] = newPortal
        }

        // Update shield state
        gameData.portalShielded[portalIndex] = false
        gameData.shieldsDropped++

        // Send message to all players
        val portalNames = listOf("purple, western", "blue, eastern", "yellow, south-eastern", "red, south-western")
        val message = "The ${portalNames[portalIndex]} portal shield has dropped!"
        for (player in gameData.players) {
            player.message(message, ChatType.Game)
        }
    }

    /**
     * Checks if a tile is valid for spawning a pest.
     * Ensures the tile is within instance bounds, walkable, and not occupied.
     *
     * @param tile The tile to check
     * @param instanceTile The instance base tile
     * @param portalTile The portal tile (to avoid spawning underneath)
     * @param gameData The game state data
     * @return true if the tile is valid for spawning
     */
    private fun isValidSpawnTile(tile: Tile, instanceTile: Tile, portalTile: Tile?, gameData: PestGameData): Boolean {
        // Check if tile is within instance bounds (64x64 region)
        val inInstance = tile.x >= instanceTile.x && tile.x < instanceTile.x + 64 &&
                        tile.y >= instanceTile.y && tile.y < instanceTile.y + 64 &&
                        tile.level == instanceTile.level
        if (!inInstance) {
            return false
        }

        // Check if tile is walkable (collision check)
        if (Collisions.check(tile.x, tile.y, tile.level, CollisionFlag.FLOOR)) {
            return false
        }

        // Check if tile is too close to portal (avoid spawning underneath)
        if (portalTile != null && tile.distanceTo(portalTile) <= 1) {
            return false
        }

        // Check if tile is occupied by another pest
        for (pest in gameData.pests) {
            if (pest.tile.distanceTo(tile) <= 1) {
                return false
            }
        }

        return true
    }

    /**
     * Spawns pests near active portals and knight.
     * Follows Matrix4 logic with pest count limits per location.
     *
     * @param gameData The game state data
     */
    private fun spawnPests(gameData: PestGameData) {
        val instanceTile = gameData.instanceTile ?: return

        // Try to spawn pests at each portal location (0-3) and knight location (4)
        for (index in 0 until 5) {
            // Determine max pests for this location
            val maxPests = when {
                index == 4 -> maxPestsNearKnight // Knight location
                gameData.portalHealth[index] <= 0 -> continue // Portal destroyed, skip
                else -> maxPestsPerPortal // Unlocked portal (will change to 5 when shield system is added)
            }

            // Check if we've reached the limit for this location
            if (gameData.pestCounts[index] >= maxPests) continue

            // Get base tile for this location
            val baseOffset = if (index == 4) knightOffset else portalOffsets[index]
            val baseTile = Tile(instanceTile.x + baseOffset.x, instanceTile.y + baseOffset.y, instanceTile.level)

            // Get portal tile for this location (to avoid spawning underneath)
            val portalTile = if (index < 4) gameData.portalNPCs[index]?.tile else null

            // Get pest ID from appropriate list
            val pestId = if (index == 4) {
                // Knight location - spawn shifters
                gameData.pestDataConfig.shifters.random()
            } else {
                // Portal location - spawn regular pests
                gameData.pestDataConfig.pests.random()
            }

            // Try to find a free tile within 5 tiles of the base
            var pestTile = baseTile
            var spawned = false
            for (tryCount in 0 until 20) {
                pestTile = Tile(
                    baseTile.x + (-5..5).random(), baseTile.y + (-5..5).random(), baseTile.level
                )
                // Check if tile is valid (within bounds, walkable, not occupied)
                if (isValidSpawnTile(pestTile, instanceTile, portalTile, gameData)) {
                    spawned = true
                    break
                }
            }

            if (spawned) {
                val pest = NPCs.add(pestId, pestTile, Direction.SOUTH)
                if (pest.index != -1) {
                    gameData.pestCounts[index]++
                    pest["pest_control_spawn_index"] = index
                    // Spinners need to know which portal they're assigned to for healing
                    if (pest.id.contains("spinner")) {
                        pest["portal_index"] = index
                    }
                    gameData.pests.add(pest)
                    log.info { "Spawned pest $pestId at $pestTile for location $index (count: ${gameData.pestCounts[index]})" }
                }
            }
        }
    }

    /**
     * Updates the game interface for a player.
     *
     * @param player The player to update
     * @param gameData The game state data
     */
    private fun updateGameInterface(player: Player, gameData: PestGameData) {
        val minutesLeft = gameData.timeRemaining / 60
        player.interfaces.sendText("pest_control_playing", "time", "$minutesLeft min")
        // Use actual Constitution level from the knight NPC for accurate display
        val knightHP = gameData.knightNPC?.levels?.get(Skill.Constitution) ?: gameData.knightHealth
        player.interfaces.sendText("pest_control_playing", "knight_health", "$knightHP")

        // Update player damage/activity display
        val playerDamage = gameData.playerDamage[player] ?: 0
        player.interfaces.sendText("pest_control_playing", "activity", "$playerDamage")

        // Update portal health displays (components 13-16)
        for (i in 0 until 4) {
            val portalHP = gameData.portalHealth[i]
            val color = if (portalHP > 0) "<col=00FF00>" else "<col=FF0000>"
            player.interfaces.sendText(
                "pest_control_playing",
                "portal_${listOf("purple", "blue", "yellow", "red")[i]}_health",
                "$color$portalHP"
            )
        }

        // Update portal shield states using varp 719
        // Each shield is represented by 2 bits in the varp
        var shieldVarp = 0
        for (i in 0 until 4) {
            if (!gameData.portalShielded[i]) {
                // Shield dropped - set the bit
                shieldVarp = shieldVarp or (1 shl (i * 2))
                // Hide shield indicator on interface (shield is gone)
                val shieldName = listOf("purple", "blue", "yellow", "red")[i]
                player.interfaces.sendVisibility("pest_control_playing", "${shieldName}_shield", false)
            } else {
                // Shield still active - show shield indicator
                val shieldName = listOf("purple", "blue", "yellow", "red")[i]
                player.interfaces.sendVisibility("pest_control_playing", "${shieldName}_shield", true)
            }
        }
        player.variables.set("pest_control_shields", shieldVarp)

        // Update portal destruction states using varp
        // Based on 2009scape: sends config with value << 28 to show destroyed portals
        var destroyedVarp = 0
        for (i in 0 until 4) {
            if (gameData.portalNPCs[i] == null) {
                // Portal destroyed - set the bit
                destroyedVarp = destroyedVarp or (1 shl i)
            }
        }
        player.variables.set("pest_control_portals_destroyed", destroyedVarp shl 28)
    }

    /**
     * Ends a Pest Control game with a win or lose result.
     *
     * @param gameData The game state data
     * @param won Whether the game was won
     */
    private fun endGame(gameData: PestGameData, won: Boolean) {
        val exitTile = Tile(
            gameData.difficultyConfig.exitTileX,
            gameData.difficultyConfig.exitTileY,
            gameData.difficultyConfig.exitTileLevel
        )
        val points = gameData.difficultyConfig.rewardPoints

        for (player in gameData.players) {
            player.interfaces.close("pest_control_playing")
            player.clearInstance()
            player.remove<String>("pest_control_difficulty")
            player.remove<Boolean>("pest_control_game_active")

            if (won) {
                val currentPoints = player["pest_control_points", 0]
                player["pest_control_points"] = currentPoints + points
                player.message(
                    "Congratulations! You successfully defended the Void Knight and earned $points commendation points!",
                    ChatType.Game
                )
            } else {
                player.message("You failed to protect the Void Knight. No points awarded.", ChatType.Game)
            }

            // Teleport back to exit tile
            player.tele(exitTile)
        }
    }

    /**
     * Handles player logout while in Pest Control.
     *
     * Removes player from lobby and cleans up instance if in game.
     *
     * @param player The player logging out
     * @return True if logout should proceed, false otherwise
     */
    private fun handleLogout(player: Player): Boolean {
        val difficultyName = player.get("pest_control_difficulty", "")
        if (difficultyName.isEmpty()) {
            return true
        }
        val diffConfig = difficultyConfig(difficultyName)

        if (player.get("pest_control_in_lobby", false)) {
            // Remove from lobby and teleport back to entrance
            val lobby = lobbies[difficultyName]
            if (lobby != null) {
                synchronized(lobby) {
                    lobby.remove(player)
                }
            }
            player.interfaces.close("pest_control_waiting")
            if (diffConfig != null) {
                val exitTile = Tile(diffConfig.exitTileX, diffConfig.exitTileY, diffConfig.exitTileLevel)
                player.tele(exitTile)
            }
            player.clear("pest_control_difficulty")
            player.clear("pest_control_in_lobby")
            return true
        }

        if (player.get("pest_control_game_active", false)) {
            // Clean up instance and game state
            val gameData = activeGames[player]
            if (gameData != null) {
                gameData.players.remove(player)
                if (gameData.players.isEmpty()) {
                    // Last player left, end game as loss
                    endGame(gameData, false)
                }
            }
            activeGames.remove(player)
            gameTimers.remove(player)
            pestSpawnTimers.remove(player)
            shieldDropTimers.remove(player)
            player.clearInstance()
            player.interfaces.close("pest_control_waiting")
            player.interfaces.close("pest_control_playing")
            player.clear("pest_control_difficulty")
            player.clear("pest_control_game_active")
            if (diffConfig != null) {
                val exitTile = Tile(diffConfig.exitTileX, diffConfig.exitTileY, diffConfig.exitTileLevel)
                player.tele(exitTile)
            } else {
                player.tele(Tile(2657, 2639, 0))
            }
            return true
        }

        return false
    }

}
