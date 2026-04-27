package content.minigame.pest_control

import com.github.michaelbull.logging.InlineLogger
import content.entity.combat.attackers
import content.entity.combat.dead
import content.entity.combat.hit.hit
import content.entity.combat.target
import content.entity.combat.underAttack
import content.entity.effect.toxin.poison
import content.minigame.pest_control.PestControl.Companion.FORTIFICATION_PROGRESSION
import content.minigame.pest_control.PestControl.Companion.INVALID_OBJECT_IDS
import content.minigame.pest_control.PestControl.Companion.RAVAGER_TARGET_DISTANCE
import content.quest.clearInstance
import content.quest.instanceOffset
import content.quest.setInstanceLogout
import content.quest.smallInstance
import org.rsmod.game.pathfinder.LineValidator
import org.rsmod.game.pathfinder.flag.CollisionFlag
import world.gregs.config.Config
import world.gregs.voidps.engine.GameLoop
import world.gregs.voidps.engine.Script
import world.gregs.voidps.engine.client.instruction.handle.interactNpc
import world.gregs.voidps.engine.client.instruction.handle.interactPlayer
import world.gregs.voidps.engine.client.message
import world.gregs.voidps.engine.data.definition.ObjectDefinitions
import world.gregs.voidps.engine.entity.World
import world.gregs.voidps.engine.entity.character.mode.EmptyMode
import world.gregs.voidps.engine.entity.character.mode.combat.CombatMovement
import world.gregs.voidps.engine.entity.character.mode.move.Movement
import world.gregs.voidps.engine.entity.character.move.tele
import world.gregs.voidps.engine.entity.character.npc.NPC
import world.gregs.voidps.engine.entity.character.npc.NPCs
import world.gregs.voidps.engine.entity.character.player.Player
import world.gregs.voidps.engine.entity.character.player.chat.ChatType
import world.gregs.voidps.engine.entity.character.player.combatLevel
import world.gregs.voidps.engine.entity.character.player.name
import world.gregs.voidps.engine.entity.character.player.skill.Skill
import world.gregs.voidps.engine.entity.item.Item
import world.gregs.voidps.engine.entity.obj.GameObject
import world.gregs.voidps.engine.entity.obj.GameObjects
import world.gregs.voidps.engine.inv.add
import world.gregs.voidps.engine.inv.inventory
import world.gregs.voidps.engine.map.collision.Collisions
import world.gregs.voidps.engine.map.collision.check
import world.gregs.voidps.engine.timedLoad
import world.gregs.voidps.engine.timer.Timer
import world.gregs.voidps.engine.timer.toTicks
import world.gregs.voidps.type.Direction
import world.gregs.voidps.type.Region
import world.gregs.voidps.type.Tile
import world.gregs.voidps.type.random
import java.util.concurrent.TimeUnit

/**
 * Pest Control configuration data loaded from Groml config file.
 */
data class PestControlConfig(
    val difficulties: Map<String, DifficultyConfig>,
    val pestData: Map<String, PestDataConfig>,
    val locations: LocationConfig,
    val timers: TimerConfig,
    val limits: LimitConfig,
    val barricadeOffsets: List<TileOffset> = emptyList(),
    val gateOffsets: List<TileOffset> = emptyList()
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
        private var nextGameId = 0

        // Barricade & gate cache id ranges (mirrors 2009scape PestControlSession).
        // 14224-14232 are barricade variants, 14233-14248 are gate variants.
        private val FORTIFICATION_IDS = 14224..14248
        private const val GATE_BREAK_ID = 14233
        private val INVALID_OBJECT_IDS = setOf(14230, 14231, 14232, 14245, 14246, 14247, 14248)
        private val FORTIFICATION_PROGRESSION = mapOf(
            14224 to 14227,
            14225 to 14228,
            14226 to 14229,
            14227 to 14230,
            14228 to 14231,
            14229 to 14232,
            14233 to 14237,
            14234 to 14238,
            14235 to 14239,
            14236 to 14240,
            14237 to 14241,
            14238 to 14242,
            14239 to 14243,
            14240 to 14244,
            14241 to 14245,
            14242 to 14246,
            14243 to 14247,
            14244 to 14248,
        )

        // 2009scape's PCRavagerNPC switches the scenery shape to 22 (debris/flat) when the
        // fortification reaches its destroyed state - the cache models for the destroyed ids
        // (14230-14232 barricades, 14245-14248 gates) only render properly at this shape.
        private const val DESTROYED_OBJECT_SHAPE = 22

        // Reverse of FORTIFICATION_PROGRESSION: maps damaged → previous state for repair
        // (2010 wiki: "Repairing a barricade or gate acts as 50 points of damage on a monster")
        private val REPAIR_PROGRESSION: Map<Int, Int> by lazy {
            FORTIFICATION_PROGRESSION.entries.associate { (k, v) -> v to k }
        }

        // Ravager combat tuning (matches 2009scape PCRavagerNPC)
        private const val RAVAGER_ATTACK_RANGE = 1
        private const val RAVAGER_ATTACK_COOLDOWN_TICKS = 5
        private const val RAVAGER_TARGET_DISTANCE = 15 // RENDERING_DISTANCE / 3
        private const val RAVAGER_LAST_ATTACK_KEY = "ravager_last_attack"
        private const val RAVAGER_TARGET_KEY = "ravager_target"
        private const val RAVAGER_FORTIFICATION_TARGET_KEY = "ravager_fortification_target"

        // Shifter combat tuning (matches 2009scape PCShifterNPC)
        private const val SHIFTER_TELEPORT_DISTANCE = 5
        private const val SHIFTER_TELEPORT_RADIUS = 2
        private const val SHIFTER_SQUIRE_ATTACK_CHANCE = 2 // 2/50 per tick

        // Splatter combat tuning (matches 2009scape PCSplatterNPC)
        private const val SPLATTER_EXPLOSION_RADIUS = 1
        private const val SPLATTER_EXPLOSION_ANIM = 3888
        private const val SPLATTER_BASE_GFX = 649
    }

    // Debug flag - set to true to skip 30-second lobby timer and start game immediately
    private val DEBUG_MODE = true

    // Configuration loaded from Groml file
    private lateinit var config: PestControlConfig

    // Line of sight validator for ranged attacks
    private val lineValidator = LineValidator(flags = Collisions.map)

    /**
     * Gets difficulty config by name.
     */
    private fun difficultyConfig(name: String): DifficultyConfig? = config.difficulties[name.lowercase()]

    /**
     * Gets pest data config by name.
     */
    private fun pestDataConfig(name: String): PestDataConfig? = config.pestData[name.lowercase()]

    private val activeGames = mutableMapOf<Int, PestGameData>()
    private val gameTimers = mutableMapOf<Int, Int>()
    private val pestSpawnTimers = mutableMapOf<Int, Int>()
    private val shieldDropTimers = mutableMapOf<Int, Int>()
    private val playerToGameId = mutableMapOf<Player, Int>()
    private val lobbies = mutableMapOf<String, MutableList<Player>>()
    private val lobbyTimers = mutableMapOf<String, Int>()

    init {
        // Handle void knight damage - allow damage from pests
        npcCombatDamage("void_knight") { damage ->
            // damage=-1 in RS means miss/splash — treat as 0
            val actualDamage = maxOf(0, damage.damage)
            log.info { "VOID KNIGHT DAMAGE HANDLER: source=${damage.source}, type=${damage.type}, damage=${damage.damage} (actual=$actualDamage)" }
            log.info { "VOID KNIGHT DAMAGE HANDLER: Before damage - constitution=${this.levels.get(Skill.Constitution)}" }
            if (actualDamage > 0) {
                this.levels.set(Skill.Constitution, this.levels.get(Skill.Constitution) - actualDamage)
            }
            log.info { "VOID KNIGHT DAMAGE HANDLER: After damage - constitution=${this.levels.get(Skill.Constitution)}" }
        }

        // Re-open Pest Control overlay if it's closed while game is active
        interfaceClosed("pest_control_playing") {
            // Check if player is still in an active game
            if (this["pest_control_game_active"] as? Boolean == true) {
                val gameId = playerToGameId[this]
                if (gameId != null) {
                    val gameData = activeGames[gameId]
                    if (gameData != null) {
                        this.interfaces.open("pest_control_playing")
                        updateGameInterface(this, gameData)
                    }
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
                game.portalNPCs.contains(target) || game.pests.contains(target) || game.players.any { player ->
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

                // Restore 500 HP to Void Knight when portal destroyed
                gameData.knightHealth = minOf(gameData.knightHealth + 500, gameData.difficultyConfig.knightHealth)

                // Explode all spinners associated with this portal
                val spinnersToExplode =
                    gameData.pests.filter { it.id.contains("spinner") && (it["portal_index"] as? Int) == portalIndex }
                for (spinner in spinnersToExplode) {
                    // Damage nearby players (2010 wiki: 50 LP instant + poison starting at 18 LP)
                    for (player in gameData.players) {
                        if (spinner.tile.distanceTo(player.tile) <= 2) {
                            // 50 LP instant damage
                            player.hit(spinner, damage = 50, offensiveType = "damage", weapon = Item.EMPTY)
                            // Poison starting at 18 LP
                            spinner.poison(player, 18)
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
    private val barricadeOffsets: List<TileOffset>

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
        val gameId: Int,
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
        val portalNPCs: MutableList<NPC?> = mutableListOf(
            null, null, null, null
        ),
        var instanceTile: Tile? = null,
        var knightNPC: NPC? = null,
        val pests: MutableList<NPC> = mutableListOf(),
        val barricades: MutableMap<Tile, GameObject> = mutableMapOf() // Tracks live barricade & gate game objects by tile
    )

    init {
        // Load configuration from Groml file
        val difficulties = mutableMapOf<String, DifficultyConfig>()
        var pestData = mutableMapOf<String, PestDataConfig>()
        var locations: LocationConfig? = null
        var timers: TimerConfig? = null
        var limits: LimitConfig? = null
        var barricadeOffsets = emptyList<TileOffset>()
        var gateOffsets = emptyList<TileOffset>()

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

                        "barricade_offsets" -> {
                            var barricadeOffsetsLocal = emptyList<TileOffset>()
                            var gateOffsetsLocal = emptyList<TileOffset>()
                            while (nextPair()) {
                                when (key()) {
                                    "gates" -> {
                                        val offsetList = list()
                                        gateOffsetsLocal = offsetList.mapNotNull { item ->
                                            if (item is Map<*, *>) {
                                                val x = (item["x"] as? Number)?.toInt() ?: 0
                                                val y = (item["y"] as? Number)?.toInt() ?: 0
                                                val level = 0
                                                TileOffset(x, y, level)
                                            } else {
                                                null
                                            }
                                        }
                                    }

                                    "barricades" -> {
                                        val offsetList = list()
                                        barricadeOffsetsLocal = offsetList.mapNotNull { item ->
                                            if (item is Map<*, *>) {
                                                val x = (item["x"] as? Number)?.toInt() ?: 0
                                                val y = (item["y"] as? Number)?.toInt() ?: 0
                                                val level = 0
                                                TileOffset(x, y, level)
                                            } else {
                                                null
                                            }
                                        }
                                    }
                                }
                            }
                            barricadeOffsets = barricadeOffsetsLocal
                            gateOffsets = gateOffsetsLocal
                        }
                    }
                }

            }
            difficulties.size
        }

        config =
            PestControlConfig(difficulties, pestData, locations!!, timers!!, limits!!, barricadeOffsets, gateOffsets)

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
        this.barricadeOffsets = config.barricadeOffsets

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

        // Barricade/gate repair (2010 wiki: repair = 50 damage credit toward zeal)
        objectOperate("Repair", "*") {
            val obj = it.target
            val objId = obj.intId
            if (objId !in FORTIFICATION_IDS) return@objectOperate
            val repairedId = REPAIR_PROGRESSION[objId] ?: return@objectOperate

            val gameId = playerToGameId[this] ?: run {
                message("You can only repair during a Pest Control game.", ChatType.Game)
                return@objectOperate
            }
            val gameData = activeGames[gameId] ?: return@objectOperate

            repairFortification(this, obj, repairedId, gameData)
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
            val pestId = this.id
            // Splatters explode upon death (matrix4-style)
            if (pestId.contains("splatter")) {
                val spawnIndex = this["pest_control_spawn_index", -1]
                for (gameData in activeGames.values) {
                    if (gameData.pests.contains(this)) {
                        performSplatterDeathExplosion(this, gameData)
                        gameData.pestCounts[spawnIndex] = maxOf(0, gameData.pestCounts[spawnIndex] - 1)
                        gameData.pests.remove(this)
                        log.info { "Splatter ${this.id} exploded upon death at location $spawnIndex" }
                        break
                    }
                }
                return@npcDeath
            }

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
                val gameId = playerToGameId[this]
                if (gameId != null) {
                    val gameData = activeGames[gameId]
                    if (gameData != null) {
                        // Safe activity: respawn on the island with full stats (2010 wiki)
                        // Teleport to entrance within the instance
                        val playerOffset = this.instanceOffset()
                        tele(entrance.add(playerOffset))
                        // Restore HP and prayer (respawn with full stats)
                        levels.restore(Skill.Constitution, levels.getMax(Skill.Constitution))
                        levels.restore(Skill.Prayer, levels.getMax(Skill.Prayer))
                        message("Oh dear, you have died! But you quickly " +
                                "recover and find yourself back at the lander.", ChatType.Game)
                        // Re-enable NPC collision for brawler blocking
                        this.blockMove = CollisionFlag.BLOCK_NPCS
                        // Re-open game overlay
                        this.interfaces.open("pest_control_playing")
                        updateGameInterface(this, gameData)
                        // Player stays in the game — do NOT remove from gameData or playerToGameId
                    }
                }
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

        // 500-point warnings (2010 wiki)
        val currentPoints = this["pest_control_points", 0]
        val rewardPoints = diffConfig.rewardPoints
        if (currentPoints >= 500) {
            message("You already have 500 commendation points. You should spend them before playing again.", ChatType.Game)
        } else if (currentPoints + rewardPoints > 500) {
            val wasted = (currentPoints + rewardPoints) - 500
            message("Warning: You have $currentPoints points. Winning would only award ${rewardPoints - wasted} of $rewardPoints points.", ChatType.Game)
        }

        // Add to centralized lobby
        val lobby = lobbies[difficultyName] ?: return
        synchronized(lobby) {
            if (lobby.isEmpty()) {
                lobbyTimers[difficultyName] = if (DEBUG_MODE) 1 else lobbyTimerSeconds
            }
            lobby.add(this)

            // Auto-start when lander fills with 25 players (2010 wiki)
            if (lobby.size >= 25) {
                startGame(difficultyName)
                return
            }
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
                "pest_control_waiting",
                "commendations",
                "Commendations: ${player["pest_control_points", 0]}"
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
                    val requiredPlayers = if (DEBUG_MODE) 1 else minPlayersToStart
                    if (lobby.size >= requiredPlayers) {
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
            // Take up to 25 players from the lobby (max per game)
            // If there are more players, they'll wait for the next game
            val playersToTake = minOf(lobby.size, 25)
            players = lobby.take(playersToTake)
            repeat(playersToTake) {
                lobby.removeAt(0)
            }
            // Reset lobby timer if there are still players waiting
            if (lobby.isNotEmpty()) {
                lobbyTimers[difficultyName] = if (DEBUG_MODE) 1 else lobbyTimerSeconds
            }
        }

        // Generate unique game ID
        val gameId = nextGameId++

        // Create game data for this instance
        val gameData = PestGameData(
            gameId = gameId,
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

            // Enable NPC collision for brawler blocking (only brawlers have blocks_players = true)
            player.blockMove = CollisionFlag.BLOCK_NPCS

            // Track player to game ID mapping
            playerToGameId[player] = gameId
            player.message("Pest Control game starting!", ChatType.Game)
        }

        // Store game data and timers using game ID
        activeGames[gameId] = gameData
        gameTimers[gameId] = gameData.timeRemaining
        pestSpawnTimers[gameId] = pestSpawnIntervalSeconds
        shieldDropTimers[gameId] = firstShieldDropSeconds

        // Track barricades & gates that ravagers can break (mirrors 2009scape's initBarricadesList)
        if (instanceTile != null) {
            registerFortifications(gameData, instanceTile)
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
                player.message(
                    "DEBUG MODE: All shields dropped and ${gameData.pests.size} pests spawned!",
                    ChatType.Game
                )
            }
        }
    }

    /**
     * Locates each barricade & gate placed in the instanced region and tracks the live [GameObject]
     * so ravagers can later [GameObjects.replace] / [GameObjects.remove] them on damage.
     *
     * Mirrors 2009scape's `PestControlSession.initBarricadesList`: the offset table is treated as
     * a single list - the cache id at each tile decides whether it's a barricade or a gate (and
     * therefore which damage progression applies).
     */
    private fun registerFortifications(gameData: PestGameData, regionBase: Tile) {
        val offsets = config.barricadeOffsets.asSequence() + config.gateOffsets.asSequence()
        var barricades = 0
        var gates = 0
        for (offset in offsets) {
            val tile = regionBase.add(offset.x, offset.y, offset.level)
            val obj = findFortification(tile) ?: continue
            gameData.barricades[tile] = obj
            if (obj.intId < GATE_BREAK_ID) barricades++ else gates++
        }
        log.debug { "Registered $barricades barricades and $gates gates for game ${gameData.gameId}" }
    }

    /**
     * Finds the first barricade or gate [GameObject] at [tile] (any layer).
     * Returns `null` if nothing in the cache fortification id range exists at this tile.
     */
    private fun findFortification(tile: Tile): GameObject? =
        fortificationsAtTile(tile).firstOrNull()

    /**
     * Returns all fortification objects currently placed on [tile] across all object layers.
     */
    private fun fortificationsAtTile(tile: Tile): List<GameObject> =
        GameObjects.at(tile).filter { it.intId in FORTIFICATION_IDS }

    /**
     * Drives a single ravager's barricade/gate-breaking behaviour.
     *
     * Picks the closest tracked fortification within [RAVAGER_TARGET_DISTANCE], paths to it, and on
     * arrival animates an attack while transforming the live [GameObject] to its next damage state
     * (or removing it once destroyed). Returns `true` when the ravager handled its turn so the
     * caller can skip the default pest targeting flow.
     *
     * Mirrors `PCRavagerNPC.tick` from 2009scape but uses the void engine's [GameObjects] APIs.
     */
    private fun tickRavager(
        pest: NPC,
        gameData: PestGameData
    ): Boolean {
        val target = selectRavagerFortificationTarget(pest, gameData)
        if (target != null) {
            // Mirrors 2009scape's getPulseManager().clear(): drop any active combat so the ravager
            // commits to its barricade target instead of continuing to attack a player/knight.
            if (pest.mode is CombatMovement) {
                pest.mode = EmptyMode
            }

            val targetTile = target.tile
            if (pest.tile.distanceTo(targetTile) > RAVAGER_ATTACK_RANGE) {
                walkRavagerToTarget(pest, targetTile)
                return true
            }

            val lastAttackTick = pest[RAVAGER_LAST_ATTACK_KEY] as? Int ?: 0
            if (GameLoop.tick - lastAttackTick < RAVAGER_ATTACK_COOLDOWN_TICKS) {
                return true
            }

            attackFortification(pest, target, gameData)
            return true
        }

        // No fortification in range. 2009scape's PCRavagerNPC does NOT actively seek players or
        // the knight - ravagers only ever target scenery, and wander toward the squire (knight)
        // with a 20% chance per tick when idle so they eventually reach a barricade group.
        // Returning true here suppresses the default player-targeting flow in the caller.
        pest.clear(RAVAGER_FORTIFICATION_TARGET_KEY)
        if (pest.mode !is CombatMovement && random.nextInt(5) == 0) {
            val knight = gameData.knightNPC
            if (knight != null && pest.tile.distanceTo(knight.tile) > 1) {
                walkRavagerToTarget(pest, knight.tile)
            }
        }
        return true
    }

    /**
     * Returns the closest tracked barricade or gate to [origin] within [RAVAGER_TARGET_DISTANCE],
     * or `null` if no fortification is in range.
     */
    private fun closestFortification(origin: Tile, gameData: PestGameData): GameObject? =
        gameData.barricades.values
            .filter { origin.distanceTo(it.tile) <= RAVAGER_TARGET_DISTANCE }
            .minByOrNull { origin.distanceTo(it.tile) }

    /**
     * Keep ravagers focused on one fortification until it is destroyed to avoid bouncing between
     * nearby barricades/gates and leaving both in partial states.
     */
    private fun selectRavagerFortificationTarget(
        pest: NPC,
        gameData: PestGameData
    ): GameObject? {
        val lockedTile = pest[RAVAGER_FORTIFICATION_TARGET_KEY] as? Tile
        if (lockedTile != null) {
            val locked = gameData.barricades[lockedTile]
            if (locked != null && pest.tile.distanceTo(locked.tile) <= RAVAGER_TARGET_DISTANCE) {
                return locked
            }
        }
        val next = closestFortification(pest.tile, gameData)
        if (next != null) {
            pest[RAVAGER_FORTIFICATION_TARGET_KEY] = next.tile
        } else {
            pest.clear(RAVAGER_FORTIFICATION_TARGET_KEY)
        }
        return next
    }

    /**
     * Issues a walk command toward [tile] only when the ravager isn't already pathing there,
     * preventing the path from being reset every tick (which would lock the NPC in place).
     */
    private fun walkRavagerToTarget(
        pest: NPC,
        tile: Tile
    ) {
        val currentTarget = pest[RAVAGER_TARGET_KEY] as? Tile
        val isMoving = pest.mode is Movement && pest.mode !is world.gregs.voidps.engine.entity.character.mode.Wander
        if (currentTarget != tile || !isMoving) {
            pest.walkTo(tile)
            pest[RAVAGER_TARGET_KEY] = tile
        }
    }

    /**
     * Performs a single ravager strike against [target], updating both the live game object and the
     * tracked fortification map. Damage progression uses [FORTIFICATION_PROGRESSION]; the result is
     * removed when its id falls in [INVALID_OBJECT_IDS].
     *
     * **Important**: we deliberately avoid [GameObjects.replace] here. That API tracks the
     * map-original and automatically re-adds it when a replacement is removed. When the damage
     * progression changes the object layer (e.g. shape 0 WALL → shape 22 GROUND_DECORATION for
     * debris), the re-added original lands in the old layer while the new object sits in the new
     * layer, causing both to be visible simultaneously. Instead we remove every fortification
     * variant at the tile (sweeping for engine-restored originals) then [GameObjects.add] the new
     * state as a fresh object.
     */
    private fun attackFortification(
        pest: NPC,
        target: GameObject,
        gameData: PestGameData
    ) {
        val fortifications = fortificationsAtTile(target.tile)
        if (fortifications.isEmpty()) {
            gameData.barricades.remove(target.tile)
            pest.clear(RAVAGER_FORTIFICATION_TARGET_KEY)
            return
        }
        val liveTarget = fortifications.firstOrNull {
            it.intId == target.intId && it.shape == target.shape && it.rotation == target.rotation
        } ?: fortifications.firstOrNull { it.intId == target.intId } ?: fortifications.first()

        pest.face(liveTarget.tile)
        pest.anim("ravager_attack")
        pest[RAVAGER_LAST_ATTACK_KEY] = GameLoop.tick

        val currentId = liveTarget.intId
        val nextId = FORTIFICATION_PROGRESSION[currentId]
        if (nextId == null) {
            log.warn { "FORT REPLACE: no progression entry for fortification id $currentId at ${liveTarget.tile}" }
            gameData.barricades.remove(liveTarget.tile)
            pest.clear(RAVAGER_FORTIFICATION_TARGET_KEY)
            return
        }
        val destroyed = nextId in INVALID_OBJECT_IDS

        val replacementName = ObjectDefinitions.getValue(nextId).stringId
        if (replacementName.isEmpty()) {
            log.warn { "FORT REPLACE: cache id $nextId has no toml stringId; tracking only" }
            if (destroyed) gameData.barricades.remove(liveTarget.tile)
            return
        }
        val resolvedId = ObjectDefinitions.get(replacementName).id
        if (resolvedId == -1) {
            log.warn { "FORT REPLACE: stringId '$replacementName' resolves to -1 (would vanish); aborting" }
            if (destroyed) gameData.barricades.remove(liveTarget.tile)
            return
        }

        val replacementShape = if (destroyed) DESTROYED_OBJECT_SHAPE else liveTarget.shape
        val replacementRotation = if (destroyed) (liveTarget.rotation and 2) else liveTarget.rotation
        log.debug {
            "FORT REPLACE: tile=${liveTarget.tile} ${liveTarget.intId}(shape=${liveTarget.shape},rot=${liveTarget.rotation}) " +
                    "-> $nextId='$replacementName'(resolved=$resolvedId, shape=$replacementShape, rot=$replacementRotation) destroyed=$destroyed"
        }

        // Remove ALL fortification objects at this tile. Removing a replacement causes the engine
        // to re-add the map-original, so we sweep a second time to catch those restored objects.
        for (fort in fortifications) {
            GameObjects.remove(fort)
        }
        for (reAdded in fortificationsAtTile(liveTarget.tile)) {
            GameObjects.remove(reAdded)
        }

        // Place the new damage state as a fresh temporary object (no replacement tracking).
        val replacement = GameObjects.add(replacementName, liveTarget.tile, replacementShape, replacementRotation)
        log.debug {
            "FORT REPLACE result: replacement intId=${replacement.intId} shape=${replacement.shape} " +
                    "rotation=${replacement.rotation} tile=${replacement.tile}"
        }
        if (destroyed) {
            gameData.barricades.remove(liveTarget.tile)
            pest.clear(RAVAGER_FORTIFICATION_TARGET_KEY)
        } else {
            gameData.barricades[liveTarget.tile] = replacement
            pest[RAVAGER_FORTIFICATION_TARGET_KEY] = replacement.tile
        }
    }

    /**
     * Repairs a damaged barricade or gate, replacing it with its previous (less damaged) state.
     * Credits the player with 50 damage toward the 500 zeal requirement.
     * (2010 wiki: "Repairing a barricade or gate acts as 50 points of damage on a monster")
     *
     * @param player The player performing the repair
     * @param target The damaged fortification object
     * @param repairedId The cache id to replace it with (previous damage state)
     * @param gameData The active game state
     */
    private fun repairFortification(
        player: Player,
        target: GameObject,
        repairedId: Int,
        gameData: PestGameData
    ) {
        val replacementName = ObjectDefinitions.getValue(repairedId).stringId
        if (replacementName.isEmpty()) {
            log.warn { "REPAIR: cache id $repairedId has no toml stringId; aborting" }
            return
        }

        // Remove all fortification objects at this tile (same sweep pattern as attackFortification)
        for (fort in fortificationsAtTile(target.tile)) {
            GameObjects.remove(fort)
        }
        for (reAdded in fortificationsAtTile(target.tile)) {
            GameObjects.remove(reAdded)
        }

        // Place repaired object (use original shape/rotation since we're restoring)
        val replacement = GameObjects.add(replacementName, target.tile, target.shape, target.rotation)
        gameData.barricades[target.tile] = replacement

        // Credit 50 damage toward zeal requirement
        gameData.playerDamage[player] = (gameData.playerDamage[player] ?: 0) + 50
        val type = if (target.intId < GATE_BREAK_ID) "barricade" else "gate"
        player.message("You repair the $type.", ChatType.Game)
        log.debug { "REPAIR: ${player.name} repaired $type at ${target.tile} (${target.intId} -> $repairedId), +50 zeal" }
    }

    /**
     * Drives a single shifter's teleport behavior.
     *
     * Shifters use melee combat but can attack diagonally like ranged. When not in combat,
     * they have a 2/50 chance per tick to target the Void Knight. When attacking and the
     * target is more than 5 tiles away, they teleport within 2 tiles of the target.
     *
     * Mirrors `PCShifterNPC.tick` from 2009scape.
     */
    private fun tickShifter(
        pest: NPC,
        gameData: PestGameData
    ): Boolean {
        val knight = gameData.knightNPC
        val inCombat = pest.mode is CombatMovement

        // Random teleportation: 1/15 chance per tick (matching Matrix4 Utils.random(15) == 0)
        if (random.nextInt(15) == 0) {
            val target = if (inCombat) {
                (pest.mode as CombatMovement).target
            } else {
                knight
            }
            if (target != null) {
                log.debug { "SHIFTER RANDOM TELEPORT: ${pest.id} random teleport to ${if (target is Player) target.name else "void_knight"}" }
                val destination = findShifterTeleportDestination(target.tile, gameData)
                if (destination != null) {
                    performShifterTeleport(pest, destination)
                }
                return true
            }
        }

        // Retaliation: if under attack by a player, target them and teleport if > 5 tiles away
        if (pest.underAttack) {
            val attackers = pest.attackers.filterIsInstance<Player>()
            if (attackers.isNotEmpty()) {
                val attacker = attackers.first()
                val distance = pest.tile.distanceTo(attacker.tile)
                if (distance > SHIFTER_TELEPORT_DISTANCE) {
                    log.debug { "SHIFTER RETALIATION: ${pest.id} under attack by ${attacker.name}, distance=$distance > $SHIFTER_TELEPORT_DISTANCE, teleporting to retaliate" }
                    val destination = findShifterTeleportDestination(attacker.tile, gameData)
                    if (destination != null) {
                        performShifterTeleport(pest, destination)
                    }
                    pest.interactPlayer(attacker, "Attack")
                    return true
                } else {
                    // Close enough to attack without teleport
                    pest.interactPlayer(attacker, "Attack")
                    return true
                }
            }
        }

        // When not in combat, 2/50 chance to target the knight
        if (!inCombat && random.nextInt(50) < SHIFTER_SQUIRE_ATTACK_CHANCE) {
            if (knight != null && knight.index != -1) {
                // Check distance before starting combat - teleport if too far
                val distance = pest.tile.distanceTo(knight.tile)
                if (distance > SHIFTER_TELEPORT_DISTANCE) {
                    log.debug { "SHIFTER TELEPORT: ${pest.id} distance=$distance > $SHIFTER_TELEPORT_DISTANCE, teleporting before combat" }
                    val destination = findShifterTeleportDestination(knight.tile, gameData)
                    if (destination != null) {
                        performShifterTeleport(pest, destination)
                    }
                    // After teleport, start combat using void engine's interaction system
                    pest.interactNpc(knight, "Attack")
                    return true
                }
                pest.interactNpc(knight, "Attack")
                return true
            }
        }

        // When attacking and target is > 5 tiles away, teleport near target
        if (inCombat) {
            val target = (pest.mode as CombatMovement).target
            if (target != null) {
                val distance = pest.tile.distanceTo(target.tile)
                if (distance > SHIFTER_TELEPORT_DISTANCE) {
                    log.debug { "SHIFTER TELEPORT: ${pest.id} distance=$distance > $SHIFTER_TELEPORT_DISTANCE, teleporting to ${if (target is Player) target.name else "void_knight"}" }
                    val destination = findShifterTeleportDestination(target.tile, gameData)
                    if (destination != null) {
                        performShifterTeleport(pest, destination)
                    }
                    return true
                }
            }
        }
        return false
    }

    /**
     * Finds a valid teleport destination for a shifter near the target tile.
     * Shifters can teleport through walls (blocked for walking) but must end up on valid floor.
     * Shifters cannot teleport through brawlers (2011 wiki: "Shifters are unable to teleport through them").
     *
     * Mirrors `PCShifterNPC.getDestination` from 2009scape, which uses `RegionManager.isTeleportPermitted`.
     * Since void engine doesn't have teleport-permitted check, we use FLOOR flag to ensure valid floor.
     */
    private fun findShifterTeleportDestination(targetTile: Tile, gameData: PestGameData): Tile? {
        val locations = mutableListOf<Tile>()
        val radius = SHIFTER_TELEPORT_RADIUS
        for (x in -radius..radius) {
            for (y in -radius..radius) {
                if (x != 0 || y != 0) {
                    val tile = Tile(targetTile.x + x, targetTile.y + y, targetTile.level)
                    // Check if tile has valid floor (not void/invalid)
                    // This allows teleporting through walls but prevents teleporting to invalid tiles
                    if (!Collisions.check(tile.x, tile.y, tile.level, CollisionFlag.FLOOR)) {
                        // Check if tile is too close to a brawler (shifters cannot teleport through brawlers)
                        val nearBrawler = gameData.pests.any { pest ->
                            pest.index != -1 && !pest.dead && pest.id.contains("brawler") && tile.distanceTo(pest.tile) <= 2
                        }
                        if (!nearBrawler) {
                            locations.add(tile)
                        }
                    }
                }
            }
        }
        locations.shuffle()
        val destination = locations.firstOrNull()
        if (destination != null) {
            log.debug { "SHIFTER TELEPORT: Selected destination $destination around target $targetTile" }
        } else {
            log.debug { "SHIFTER TELEPORT: No valid floor tiles found around target $targetTile (brawlers may be blocking)" }
        }
        return destination
    }

    /**
     * Performs the shifter teleport animation and movement.
     * Sends GFX 654 at source, teleports, plays animation 3904, sends GFX 654 at destination.
     * Also teleports nearby ravagers and torchers a short distance (matching wiki description).
     *
     * Mirrors `PCShifterNPC.teleport` from 2009scape.
     */
    private fun performShifterTeleport(
        pest: NPC,
        destination: Tile
    ) {
        log.debug { "SHIFTER TELEPORT: ${pest.id} teleporting from ${pest.tile} to $destination" }
        
        // Teleport nearby ravagers and torchers a short distance (wiki: "can only teleport others a very short distance")
        teleportNearbyMonsters(pest, destination)
        
        // Send GFX at source location
        pest.gfx("shifter_teleport_graphics", delay = 0)

        // Teleport immediately
        pest.tele(destination, clearMode = false)

        log.debug { "SHIFTER TELEPORT: ${pest.id} teleport complete, now at ${pest.tile}" }

        // Play arrival animation and GFX at destination
        pest.anim("shifter_teleport")
        pest.gfx("shifter_teleport_graphics", delay = 1)
    }

    /**
     * Teleports nearby ravagers and torchers a short distance when a shifter teleports.
     * Wiki states shifters can teleport other monsters a very short distance.
     *
     * @param shifter The shifter that is teleporting
     * @param shifterDestination The destination tile of the shifter
     */
    private fun teleportNearbyMonsters(shifter: NPC, shifterDestination: Tile) {
        val nearbyMonsters = mutableListOf<NPC>()
        
        // Find nearby ravagers and torchers within 3 tiles
        for (pest in NPCs.iterator()) {
            if (pest.index == -1) continue
            if (pest == shifter) continue
            if (pest.tile.level != shifter.tile.level) continue
            
            val distance = shifter.tile.distanceTo(pest.tile)
            if (distance <= 3) {
                val pestId = pest.id.lowercase()
                if (pestId.contains("ravager") || pestId.contains("torcher")) {
                    nearbyMonsters.add(pest)
                }
            }
        }
        
        // Teleport nearby monsters to a random tile within 1-2 tiles of shifter's destination
        for (monster in nearbyMonsters) {
            val offsetX = (-1..1).random()
            val offsetY = (-1..1).random()
            val monsterDestination = Tile(shifterDestination.x + offsetX, shifterDestination.y + offsetY, shifterDestination.level)
            
            // Check if destination is valid (floor check)
            if (!Collisions.check(monsterDestination.x, monsterDestination.y, monsterDestination.level, CollisionFlag.FLOOR)) {
                log.debug { "SHIFTER TELEPORT: Teleporting nearby ${monster.id} from ${monster.tile} to $monsterDestination" }
                monster.tele(monsterDestination, clearMode = false)
                monster.gfx("shifter_teleport_graphics", delay = 0)
                monster.anim("shifter_teleport")
                monster.gfx("shifter_teleport_graphics", delay = 1)
            }
        }
    }

    /**
     * Checks if a pest is blocked from shooting (2011 wiki).
     * Defilers/torchers cannot shoot when in front of gates or brawlers.
     * Gates: "if they are in the spaces right in front of one of the three gates, they cannot shoot over it"
     * Brawlers: "Defilers and Torchers cannot shoot over brawlers"
     *
     * @param pest The pest to check
     * @param gameData The game state data
     * @return true if the pest is blocked from shooting, false otherwise
     */
    private fun isBlockedFromShooting(pest: NPC, gameData: PestGameData): Boolean {
        // Check if in front of gate
        for (fort in gameData.barricades.values) {
            if (fort.intId < GATE_BREAK_ID) continue
            
            val distance = pest.tile.distanceTo(fort.tile)
            if (distance <= 2) {
                log.debug { "PEST SHOOTING BLOCKED: Pest ${pest.id} is in front of gate at ${fort.tile} (distance=$distance)" }
                return true
            }
        }
        
        // Check if near brawler (defilers/torchers cannot shoot over brawlers)
        for (brawler in gameData.pests) {
            if (brawler.index == -1 || brawler.dead) continue
            if (!brawler.id.contains("brawler")) continue
            
            val distance = pest.tile.distanceTo(brawler.tile)
            if (distance <= 2) {
                log.debug { "PEST SHOOTING BLOCKED: Pest ${pest.id} is near brawler ${brawler.id} at ${brawler.tile} (distance=$distance)" }
                return true
            }
        }
        
        return false
    }

    /**
     * Drives a single splatter's self-destruct behavior.
     *
     * Splatters check for nearby barricades/gates when not in combat. If they find one,
     * they self-destruct, damaging the fortification and nearby entities.
     *
     * Mirrors `PCSplatterNPC.tick` from 2009scape.
     */
    private fun tickSplatter(
        pest: NPC,
        gameData: PestGameData
    ): Boolean {
        val inCombat = pest.mode is CombatMovement
        if (inCombat) {
            return false
        }

        // Check if next to any barricade/gate
        for (fort in gameData.barricades.values) {
            if (pest.tile.distanceTo(fort.tile) <= 1) {
                // Self-destruct immediately
                performSplatterExplosion(pest, gameData)
                return true
            }
        }
        return false
    }

    /**
     * Performs the splatter explosion, damaging nearby fortifications and entities.
     * Uses the same fortification progression as ravagers.
     *
     * Mirrors `PCSplatterNPC.explode` from 2009scape.
     */
    private fun performSplatterExplosion(
        pest: NPC,
        gameData: PestGameData
    ) {
        // Play explosion animation and GFX
        pest.anim("splatter_explode_start")

        // Map splatter ID to the correct graphics string ID
        // Splatter IDs: 3727-3731 map to graphics variants
        val splatterId = pest.def.id
        val gfxStringId = when (splatterId) {
            3727 -> "splatter_explosion_graphics"
            3728 -> "splatter_explosion_graphics_33"
            3729 -> "splatter_explosion_graphics_44"
            3730 -> "splatter_explosion_graphics_54"
            3731 -> "splatter_explosion_graphics_65"
            else -> "splatter_explosion_graphics" // fallback
        }
        pest.gfx(gfxStringId, delay = 1)

        // Damage nearby fortifications
        for (fort in gameData.barricades.values.toList()) {
            if (pest.tile.distanceTo(fort.tile) <= SPLATTER_EXPLOSION_RADIUS) {
                val currentId = fort.intId
                val nextId = FORTIFICATION_PROGRESSION[currentId]
                if (nextId != null) {
                    val destroyed = nextId in INVALID_OBJECT_IDS
                    val replacementName = ObjectDefinitions.getValue(nextId).stringId
                    if (replacementName.isNotEmpty()) {
                        val resolvedId = ObjectDefinitions.get(replacementName).id
                        if (resolvedId != -1) {
                            val replacementShape = if (destroyed) DESTROYED_OBJECT_SHAPE else fort.shape
                            val replacementRotation = if (destroyed) (fort.rotation and 2) else fort.rotation

                            // Remove all fortifications at tile, sweep for engine-restored originals
                            val fortifications = fortificationsAtTile(fort.tile)
                            for (f in fortifications) {
                                GameObjects.remove(f)
                            }
                            for (reAdded in fortificationsAtTile(fort.tile)) {
                                GameObjects.remove(reAdded)
                            }

                            // Place the new damage state
                            val replacement =
                                GameObjects.add(replacementName, fort.tile, replacementShape, replacementRotation)
                            if (destroyed) {
                                gameData.barricades.remove(fort.tile)
                            } else {
                                gameData.barricades[fort.tile] = replacement
                            }
                        }
                    }
                }
            }
        }

        // Damage nearby players (based on splatter combat level / 3)
        val splatterLevel = pest.def.combat
        val maxDamage = splatterLevel / 3
        val minDamage = maxDamage / 2
        for (player in gameData.players) {
            if (!player.dead && pest.tile.distanceTo(player.tile) <= SPLATTER_EXPLOSION_RADIUS) {
                val damage = random.nextInt(minDamage, maxDamage + 1)
                player.hit(player, damage = damage, offensiveType = "damage", weapon = Item.EMPTY)
                gameData.playerDamage[player] = (gameData.playerDamage[player] ?: 0) + damage
            }
        }

        // Remove the splatter NPC
        NPCs.remove(pest)
        gameData.pests.remove(pest)
        val spawnIndex = pest["pest_control_spawn_index", -1]
        if (spawnIndex != -1) {
            gameData.pestCounts[spawnIndex] = maxOf(0, gameData.pestCounts[spawnIndex] - 1)
        }
    }

    /**
     * Performs the splatter death explosion when killed by players.
     * Plays animation 3888, then 3889, then graphics, then damages nearby entities.
     *
     * Mirrors `Splatter.sendExplosion` from matrix4.
     */
    private fun performSplatterDeathExplosion(
        pest: NPC,
        gameData: PestGameData
    ) {
        // Capture tile and graphics ID before pest is removed
        val explosionTile = pest.tile
        val splatterId = pest.def.id
        val gfxStringId = when (splatterId) {
            3727 -> "splatter_explosion_graphics"
            3728 -> "splatter_explosion_graphics_33"
            3729 -> "splatter_explosion_graphics_44"
            3730 -> "splatter_explosion_graphics_54"
            3731 -> "splatter_explosion_graphics_65"
            else -> "splatter_explosion_graphics"
        }

        // Play initial explosion animation
        pest.anim("splatter_explode_start")

        // Delay for first animation, then play second animation and graphics
        World.queue("splatter_death_explosion_${pest.index}", 1) {
            // Note: pest may be removed here, but we only need the tile for effects
            // Send graphics at the explosion tile (can't send to removed NPC)
            // For now, skip the second animation since pest is gone
            // The graphics effect at the tile location is sufficient
        }

        // Delay for graphics, then damage nearby entities
        World.queue("splatter_death_damage_${pest.index}", 1) {
            // Damage nearby players (matrix4: random damage up to 400)
            for (player in gameData.players) {
                if (!player.dead && explosionTile.distanceTo(player.tile) <= 2) {
                    val damage = random.nextInt(400)
                    player.hit(player, damage = damage, offensiveType = "damage", weapon = Item.EMPTY)
                    gameData.playerDamage[player] = (gameData.playerDamage[player] ?: 0) + damage
                }
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
        val gamesToRemove = mutableListOf<Int>()

        for ((gameId, gameData) in activeGames) {
            val timer = gameTimers[gameId] ?: continue
            val pestTimer = pestSpawnTimers[gameId] ?: continue
            val shieldTimer = shieldDropTimers[gameId] ?: continue

            // Decrement timer
            gameTimers[gameId] = timer - 1
            gameData.timeRemaining = timer - 1

            // Decrement pest spawn timer
            pestSpawnTimers[gameId] = pestTimer - 1

            // Decrement shield drop timer
            shieldDropTimers[gameId] = shieldTimer - 1

            // Spawn pests when timer reaches 0
            if (pestTimer <= 0) {
                spawnPests(gameData)
                pestSpawnTimers[gameId] = pestSpawnIntervalSeconds
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
                // Iterate over a copy to avoid ConcurrentModificationException
                for (pest in gameData.pests.toList()) {
                    if (pest.index == -1 || pest.dead) continue

                    val pestId = pest.id

                    // Ravager logic runs BEFORE combat-state checks: 2009scape PCRavagerNPC always
                    // prioritizes barricades over combat and clears its pulse when a target is found.
                    if (pestId.contains("ravager") && tickRavager(pest, gameData)) {
                        continue
                    }

                    // Shifter logic: teleports when target is > 5 tiles away
                    // Shifters are excluded from the targeting loop below - they use the combat system's
                    // target selection (matching matrix4 approach). tickShifter handles pre-combat
                    // targeting to the knight and teleportation during combat.
                    if (pestId.contains("shifter")) {
                        tickShifter(pest, gameData)
                        continue
                    }

                    // Splatter logic: self-destructs when next to barricades/gates
                    if (pestId.contains("splatter") && tickSplatter(pest, gameData)) {
                        continue
                    }

                    val alreadyInCombat = pest.mode is CombatMovement
                    if (alreadyInCombat) {
                        val target = (pest.mode as CombatMovement).target
                        val targetName = if (target is Player) target.name else "void_knight"
                        log.debug { "PEST TARGETING: Pest ${pest.id} already targeting $targetName, skipping" }
                        continue
                    }

                    // Also skip if pest has a target assigned but not yet in combat (e.g., walking to target)
                    // This prevents rapid reassignment when combat is rejected due to distance
                    if (pest.target != null) {
                        val targetName = if (pest.target is Player) (pest.target as Player).name else "void_knight"
                        log.debug { "PEST TARGETING: Pest ${pest.id} has assigned target $targetName, skipping reassignment" }
                        continue
                    }
                    // Skip spinners - they prioritize healing their portal
                    if (pestId.contains("spinner")) {
                        log.debug { "PEST TARGETING: Pest ${pest.id} is spinner, skipping (prioritizes portal healing)" }
                        continue
                    }

                    // Matrix4-style targeting: 33% chance to target knight, otherwise target nearby players
                    // If under attack, prioritize the attacking player (shifters especially should respond to attacks)
                    val isShifter = pestId.contains("shifter")
                    val targetKnight = if (pest.underAttack && !isShifter) {
                        // Non-shifters: if under attack, 50% chance to target knight (less likely)
                        random.nextInt(2) == 0
                    } else if (pest.underAttack && isShifter) {
                        // Shifters: if under attack, always target the attacking player
                        false
                    } else {
                        // Not under attack: normal targeting
                        if (isShifter) random.nextInt(3) != 0 else random.nextInt(3) == 0
                    }
                    log.debug {
                        "PEST TARGETING: Pest ${pest.id} roll: targetKnight=$targetKnight (random=${
                            random.nextInt(
                                3
                            )
                        }), pest.mode=${pest.mode}, pest.underAttack=${pest.underAttack}"
                    }

                    val initialTarget = if (targetKnight) knight else {
                        // Find and attack nearby players
                        gameData.players.firstOrNull { player ->
                            !player.dead && player.tile.distanceTo(pest.tile) <= 10
                        }
                    }

                    // Brawlers and splatters never attack the Void Knight
                    // (2010 wiki: "Brawlers will never attack the Void knight", "Splatters will never attack the Void knight")
                    val isBrawler = pestId.contains("brawler")
                    val isSplatter = pestId.contains("splatter")
                    val target = if ((isBrawler || isSplatter) && initialTarget is NPC && initialTarget == knight) {
                        val pestType = if (isBrawler) "Brawler" else "Splatter"
                        log.debug { "PEST TARGETING: $pestType ${pest.id} skipping knight, targeting player instead" }
                        gameData.players.firstOrNull { player ->
                            !player.dead && player.tile.distanceTo(pest.tile) <= 10
                        }
                    } else {
                        initialTarget
                    }

                    if (target != null) {
                        val targetName = if (target is Player) target.name else "void_knight"
                        log.debug {
                            "PEST TARGETING: Directing pest ${pest.id} to attack $targetName (distance=${
                                pest.tile.distanceTo(
                                    target.tile
                                )
                            })"
                        }

                        // Defilers and torchers can shoot over walls (2011 wiki)
                        // They cannot shoot when in front of gates or brawlers (2011 wiki: "in the spaces right in front of one of the three gates", "cannot shoot over brawlers")
                        val isRangedPest = pestId.contains("defiler") || pestId.contains("torcher")
                        val blockedFromShooting = if (isRangedPest) {
                            isBlockedFromShooting(pest, gameData)
                        } else {
                            false
                        }

                        if (!blockedFromShooting) {
                            // Use void engine's interaction system instead of direct combat() call
                            if (target is Player) {
                                pest.interactPlayer(target, "Attack")
                            } else {
                                pest.interactNpc(target as NPC, "Attack")
                            }
                        } else {
                            log.debug { "PEST TARGETING: Pest ${pest.id} (ranged) blocked from shooting, skipping attack on $targetName" }
                        }
                    } else {
                        log.debug { "PEST TARGETING: Pest ${pest.id} found no valid target" }
                    }
                }
            } else {
                log.debug { "PEST TARGETING: No valid knight to target (knight=$knight, knight.index=${knight?.index})" }
            }

            // Spawn pests when timer reaches 0
            if (shieldTimer <= 0 && gameData.shieldsDropped < 4) {
                dropPortalShield(gameData)
                // Use subsequent interval after first shield drops
                shieldDropTimers[gameId] = subsequentShieldDropIntervalSeconds
            }

            // Update knight health from actual NPC constitution levels
            if (knight != null && knight.index != -1) {
                gameData.knightHealth = knight.levels.get(Skill.Constitution)
            }

            // Update interface for all players in this game
            for (p in gameData.players) {
                updateGameInterface(p, gameData)
            }

            // Check win/lose conditions (knight death checked first)
            if (gameData.knightHealth <= 0) {
                // Knight died - lose
                endGame(gameData, false)
                gamesToRemove.add(gameId)
            } else if (gameData.portalsDestroyed >= 4) {
                // All portals destroyed - win
                endGame(gameData, true)
                gamesToRemove.add(gameId)
            } else if (timer <= 0) {
                // Time's up with knight alive - WIN (2010 wiki: "Keep the Void Knight alive for 20 minutes")
                endGame(gameData, true)
                gamesToRemove.add(gameId)
            }
        }

        // Clean up completed games
        for (gameId in gamesToRemove) {
            activeGames.remove(gameId)
            gameTimers.remove(gameId)
            pestSpawnTimers.remove(gameId)
            shieldDropTimers.remove(gameId)
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
                    // Shifters need attack_range = 2 to allow diagonal attacks (matching 2009scape)
                    if (pest.id.contains("shifter")) {
                        pest["attack_range"] = 2
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

        // Update player damage/activity display with color coding (like matrix4)
        val playerDamage = gameData.playerDamage[player] ?: 0
        val damageText = if (playerDamage > 750) {
            "<col=75AE49>$playerDamage"
        } else {
            "$playerDamage"
        }
        player.interfaces.sendText("pest_control_playing", "activity", damageText)

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
            player.remove<Boolean>("pest_control_game_active")
            player.interfaces.close("pest_control_playing")
            player.clearInstance()
            player.remove<String>("pest_control_difficulty")

            // Reset NPC collision blocking
            player.blockMove = 0

            // Remove player-to-game mapping
            playerToGameId.remove(player)

            // Restore all stats at end of game (2010 wiki: HP, Prayer, spec, energy, all stats)
            for (skill in Skill.all) {
                val offset = player.levels.getOffset(skill)
                if (offset < 0) {
                    player.levels.restore(skill, -offset)
                } else if (offset > 0) {
                    player.levels.drain(skill, offset)
                }
            }
            player.levels.restore(Skill.Constitution, player.levels.getMax(Skill.Constitution))
            player.levels.restore(Skill.Prayer, player.levels.getMax(Skill.Prayer))
            player["energy"] = 10000 // Run energy fully restored (10000 = 100%)
            player["special_attack_energy"] = 1000 // Special attack bar fully restored (MAX_SPECIAL_ATTACK = 1000)

            if (won) {
                val currentPoints = player["pest_control_points", 0]
                val playerDamage = gameData.playerDamage[player] ?: 0

                // Coin reward: combat level * 10 (2010 wiki)
                val coinReward = player.combatLevel * 10
                player.inventory.add("coins", coinReward)

                // Check damage requirement (500 damage minimum)
                if (playerDamage < 500) {
                    player.message("The knights have noticed your lack of zeal in the battle and have not rewarded you with any points.", ChatType.Game)
                } else {
                    // Check points cap (500 maximum)
                    if (currentPoints + points > 500) {
                        val pointsAwarded = 500 - currentPoints
                        if (pointsAwarded > 0) {
                            player["pest_control_points"] = 500
                            player.message(
                                "Congratulations! You successfully defended the Void Knight and earned $pointsAwarded commendation points (capped at 500).",
                                ChatType.Game
                            )
                        } else {
                            player.message("You have reached the maximum 500 commendation points. Exchange them before playing again.", ChatType.Game)
                        }
                    } else {
                        player["pest_control_points"] = currentPoints + points
                        player.message(
                            "Congratulations! You successfully defended the Void Knight and earned $points commendation points!",
                            ChatType.Game
                        )
                    }
                }
            } else {
                player.message("You failed to protect the Void Knight. No points awarded.", ChatType.Game)
            }

            // Teleport back to exit tile
            player.tele(exitTile)
        }

        // Clean up game instance data
        val gameId = gameData.gameId
        activeGames.remove(gameId)
        gameTimers.remove(gameId)
        pestSpawnTimers.remove(gameId)
        shieldDropTimers.remove(gameId)

        // Clean up NPCs
        gameData.knightNPC?.let { NPCs.remove(it) }
        for (portal in gameData.portalNPCs) {
            portal?.let { NPCs.remove(it) }
        }
        for (pest in gameData.pests) {
            NPCs.remove(pest)
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
        // Ensure pest_control_points is persisted (attributes are automatically saved in void)
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

        if (player["pest_control_game_active", false]) {
            // Clean up instance and game state
            val gameId = playerToGameId[player]
            if (gameId != null) {
                val gameData = activeGames[gameId]
                if (gameData != null) {
                    gameData.players.remove(player)
                    if (gameData.players.isEmpty()) {
                        // Last player left, end game as loss
                        endGame(gameData, false)
                    }
                }
                // Remove player-to-game mapping
                playerToGameId.remove(player)
            }
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
