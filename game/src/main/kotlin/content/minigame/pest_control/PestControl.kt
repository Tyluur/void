package content.minigame.pest_control

import content.quest.clearInstance
import content.quest.instanceOffset
import content.quest.setInstanceLogout
import content.quest.smallInstance
import world.gregs.voidps.engine.Script
import world.gregs.voidps.engine.client.message
import world.gregs.voidps.engine.entity.World
import world.gregs.voidps.engine.entity.character.move.tele
import world.gregs.voidps.engine.entity.character.npc.NPCs
import world.gregs.voidps.engine.entity.character.player.Player
import world.gregs.voidps.engine.entity.character.player.chat.ChatType
import world.gregs.voidps.engine.entity.character.player.combatLevel
import world.gregs.voidps.engine.queue.queue
import world.gregs.voidps.engine.timer.Timer
import world.gregs.voidps.engine.timer.toTicks
import world.gregs.voidps.type.Delta
import world.gregs.voidps.type.Direction
import world.gregs.voidps.type.Region
import world.gregs.voidps.type.Tile
import java.util.Collections
import java.util.LinkedList
import java.util.concurrent.TimeUnit

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

    // Debug flag - set to true to skip 30-second lobby timer and start game immediately
    private val DEBUG_MODE = true

    /**
     * Pest Control difficulty levels with combat requirements and tiles.
     * 
     * @property id Unique identifier for the difficulty
     * @property combatRequirement Minimum combat level to enter
     * @property entryTile Tile where players enter the lander
     * @property exitTile Tile where players exit the game
     */
    enum class PestControlDifficulty(
        val id: Int,
        val combatRequirement: Int,
        val entryTile: Tile,
        val exitTile: Tile
    ) {
        NOVICE(0, 40, Tile(2661, 2639, 0), Tile(2657, 2639, 0)),
        INTERMEDIATE(1, 70, Tile(2641, 2644, 0), Tile(2644, 2644, 0)),
        VETERAN(2, 100, Tile(2635, 2653, 0), Tile(2638, 2653, 0));

        companion object {
            fun forId(id: Int): PestControlDifficulty? = values().find { it.id == id }
            fun forName(name: String): PestControlDifficulty? = values().find { it.name.equals(name, ignoreCase = true) }
        }
    }

    /**
     * Pest data configurations for each difficulty level.
     * 
     * @property pests List of pest NPC IDs to spawn
     * @property shifters List of shifter NPC IDs
     * @property reward Commendation points reward per game
     */
    enum class PestData(
        val pests: List<String>,
        val shifters: List<String>,
        val reward: Int
    ) {
        NOVICE(
            pests = listOf(
                "shifter_level_38", "shifter_level_38_2", "shifter_level_57", "shifter_level_57_2",
                "ravager_level_36", "ravager_level_53",
                "brawler_level_51",
                "splatter_level_22", "splatter_level_33", "splatter_level_44",
                "spinner_level_36", "spinner_level_55",
                "torcher_level_33", "torcher_level_33_2", "torcher_level_49",
                "defiler_level_33", "defiler_level_50"
            ),
            shifters = listOf("shifter_level_38", "shifter_level_38_2", "shifter_level_57", "shifter_level_57_2"),
            reward = 3
        ),
        INTERMEDIATE(
            pests = listOf(
                "shifter_level_57", "shifter_level_57_2", "shifter_level_76", "shifter_level_76_2",
                "ravager_level_53", "ravager_level_71",
                "brawler_level_51", "brawler_level_76",
                "splatter_level_33", "splatter_level_44", "splatter_level_54",
                "spinner_level_55", "spinner_level_74",
                "torcher_level_49", "torcher_level_49_2", "torcher_level_66",
                "defiler_level_50", "defiler_level_67", "defiler_level_80"
            ),
            shifters = listOf("shifter_level_57", "shifter_level_57_2", "shifter_level_76", "shifter_level_76_2"),
            reward = 4
        ),
        VETERAN(
            pests = listOf(
                "shifter_level_76", "shifter_level_76_2", "shifter_level_90", "shifter_level_90_2",
                "ravager_level_71", "ravager_level_89",
                "brawler_level_76", "brawler_level_101",
                "splatter_level_44", "splatter_level_54", "splatter_level_65",
                "spinner_level_74", "spinner_level_88",
                "torcher_level_66", "torcher_level_66_2", "torcher_level_79",
                "defiler_level_67", "defiler_level_80", "defiler_level_97"
            ),
            shifters = listOf("shifter_level_76", "shifter_level_76_2", "shifter_level_90", "shifter_level_90_2"),
            reward = 5
        );

        companion object {
            fun forName(name: String): PestData? = values().find { it.name.equals(name, ignoreCase = true) }
        }
    }

    // Centralized lobby state management
    private val lobbies = mutableMapOf<PestControlDifficulty, MutableList<Player>>()
    private val lobbyTimers = mutableMapOf<PestControlDifficulty, Int>()

    // Game state management
    private val activeGames = mutableMapOf<Player, PestGameData>()
    private val gameTimers = mutableMapOf<Player, Int>()
    private val pestSpawnTimers = mutableMapOf<Player, Int>()

    // Instance configuration
    private val pestRegion = Region(10536)
    private val entrance = Tile(2659, 2614, 0)

    // Portal locations from Matrix4 PORTAL_LOCATIONS:
    // X coords: {4, 56, 45, 21, 32}
    // Y coords: {31, 28, 10, 9, 32}
    // Index 4 (32, 32) is the Void Knight
    private val portalOffsets = listOf(
        Tile(4, 31, 0),  // Purple portal
        Tile(56, 28, 0), // Blue portal
        Tile(45, 10, 0), // Yellow portal
        Tile(21, 9, 0)   // Red portal
    )

    // Knight location (last element of PORTAL_LOCATIONS arrays)
    private val knightOffset = Tile(32, 32, 0)

    /**
     * Game state data for a Pest Control game instance.
     * 
     * @property difficulty The difficulty level
     * @property players List of players in the game
     * @property knightHealth Current health of the Void Knight
     * @property portalHealth Health of each portal [purple, blue, yellow, red]
     * @property portalsDestroyed Number of portals destroyed
     * @property timeRemaining Remaining game time in seconds
     * @property playerDamage Damage dealt by each player
     * @property pestCounts Number of pests at each location [purple, blue, yellow, red, knight]
     * @property instanceTile The world tile where the instance region starts
     */
    data class PestGameData(
        val difficulty: PestControlDifficulty,
        val players: MutableList<Player>,
        var knightHealth: Int = 250,
        val portalHealth: MutableList<Int> = mutableListOf(2000, 2000, 2000, 2000),
        var portalsDestroyed: Int = 0,
        var timeRemaining: Int = 1200, // 20 minutes
        val portalBaseHealth: Int = 2000,
        val pestData: PestData,
        val playerDamage: MutableMap<Player, Int> = mutableMapOf(),
        val pestCounts: MutableList<Int> = mutableListOf(0, 0, 0, 0, 0), // 4 portals + knight
        var instanceTile: Tile? = null
    )

    init {
        // Initialize lobbies for each difficulty
        for (difficulty in PestControlDifficulty.values()) {
            lobbies[difficulty] = Collections.synchronizedList(LinkedList())
            lobbyTimers[difficulty] = 30
        }

        // Gangplank entry (default to novice)
        objectOperate("Cross", "pest_control_gangplank_enter") {
            this.enterLander(PestControlDifficulty.NOVICE)
        }
        // Alternative gangplank entry for novice lander
        objectOperate("Cross", "pest_control_gangplank_novice") {
            this.enterLander(PestControlDifficulty.NOVICE)
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
            this.enterLander(PestControlDifficulty.NOVICE)
        }
        npcOperate("Talk-to", "squire_intermediate_pest_control") {
            this.enterLander(PestControlDifficulty.INTERMEDIATE)
        }
        npcOperate("Talk-to", "squire_veteran_pest_control") {
            this.enterLander(PestControlDifficulty.VETERAN)
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
        playerLogout(::handleLogout)
        playerDeath {
            val difficultyName = get("pest_control_difficulty", "")
            val difficulty = PestControlDifficulty.forName(difficultyName)

            if (get("pest_control_in_lobby", false)) {
                difficulty?.let { diff ->
                    val lobby = lobbies[diff]
                    if (lobby != null) {
                        synchronized(lobby) {
                            lobby.remove(this)
                        }
                    }
                }
                this.interfaces.close("pest_control_waiting")
                remove<String>("pest_control_difficulty")
                remove<Boolean>("pest_control_in_lobby")
            }

            if (get("pest_control_game_active", false)) {
                val gameData = activeGames[this]
                if (gameData != null) {
                    gameData.players.remove(this)
                    if (gameData.players.isEmpty()) {
                        endGame(gameData, false)
                    }
                }
                activeGames.remove(this)
                gameTimers.remove(this)
                pestSpawnTimers.remove(this)
                clearInstance()
                this.interfaces.close("pest_control_waiting")
                this.interfaces.close("pest_control_playing")
                remove<String>("pest_control_difficulty")
                remove<Boolean>("pest_control_game_active")
            }
        }
    }

    /**
     * Handles player entering the Pest Control lander.
     * 
     * Checks combat level requirement and follower restriction before adding player to lobby.
     * 
     * @param player The player attempting to enter
     * @param difficulty The difficulty level being entered
     */
    private fun Player.enterLander(difficulty: PestControlDifficulty) {
        if (combatLevel < difficulty.combatRequirement) {
            message("You need a combat level of ${difficulty.combatRequirement} or more to enter this boat.", ChatType.Console)
            return
        }

        // Check for follower/familiar
        val followerIndex = get("follower_index", -1)
        if (followerIndex != -1 && NPCs.indexed(followerIndex) != null) {
            message("You can't take a follower into the lander, there isn't enough room!", ChatType.Console)
            return
        }

        // Add to centralized lobby
        val lobby = lobbies[difficulty] ?: return
        synchronized(lobby) {
            if (lobby.isEmpty()) {
                lobbyTimers[difficulty] = if (DEBUG_MODE) 1 else 30
            }
            lobby.add(this)
        }

        // Teleport to lander
        tele(difficulty.entryTile)
        message("You board the lander.", ChatType.Console)

        // Open lobby interface
        this.interfaces.open("pest_control_waiting")
        updateLanderInterface(this, difficulty)

        // Set player state
        this["pest_control_difficulty"] = difficulty.name
        this["pest_control_in_lobby"] = true
    }

    /**
     * Updates the lander waiting interface for a player.
     * 
     * @param player The player to update
     * @param difficulty The difficulty of the lander
     */
    private fun updateLanderInterface(player: Player, difficulty: PestControlDifficulty) {
        val lobby = lobbies[difficulty] ?: return
        val timer = lobbyTimers[difficulty] ?: 30
        synchronized(lobby) {
            val minutesLeft = timer / 60
            player.interfaces.sendText("pest_control_waiting", "title", difficulty.name.lowercase().replaceFirstChar { it.uppercase() })
            player.interfaces.sendText("pest_control_waiting", "departure", "Next Departure: $minutesLeft minutes ${if (minutesLeft % 2 != 0) "30 seconds" else ""}")
            player.interfaces.sendText("pest_control_waiting", "players_ready", "Player's Ready: ${lobby.size}")
            player.interfaces.sendText("pest_control_waiting", "commendations", "Commendations: ${player["pest_control_points", 0]}")
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
        val difficultyName = player["pest_control_difficulty", ""]
        val difficulty = PestControlDifficulty.forName(difficultyName) ?: return

        if (player["pest_control_in_lobby", false]) {
            val lobby = lobbies[difficulty]
            if (lobby != null) {
                synchronized(lobby) {
                    lobby.remove(player)
                }
            }
            player.remove<String>("pest_control_difficulty")
            player.remove<Boolean>("pest_control_in_lobby")
        }

        player.interfaces.close("pest_control_waiting")
        player.tele(difficulty.exitTile)
        player.message("You leave the lander.", ChatType.Console)
    }

    /**
     * Updates lobby timers and starts games when conditions are met.
     * 
     * Called every second by the timer system.
     * Starts game when timer reaches 0 and there are 3+ players.
     */
    private fun updateLobbyTimers() {
        for (difficulty in PestControlDifficulty.values()) {
            val lobby = lobbies[difficulty] ?: continue
            val timer = lobbyTimers[difficulty] ?: continue

            synchronized(lobby) {
                if (lobby.isEmpty()) {
                    lobbyTimers[difficulty] = if (DEBUG_MODE) 1 else 30
                    return@synchronized
                }

                lobbyTimers[difficulty] = timer - 1

                if (timer == 0) {
                    if (lobby.size >= 1) {
                        startGame(difficulty)
                    } else {
                        lobbyTimers[difficulty] = 30
                    }
                }

                // Update interface for all players in lobby
                for (player in lobby) {
                    updateLanderInterface(player, difficulty)
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
     * @param difficulty The difficulty level to start
     */
    private fun startGame(difficulty: PestControlDifficulty) {
        val lobby = lobbies[difficulty] ?: return
        val players: List<Player>

        synchronized(lobby) {
            players = lobby.toList()
            lobby.clear()
            lobbyTimers[difficulty] = 30
        }

        val data = PestData.forName(difficulty.name) ?: PestData.NOVICE

        // Create game data for this instance
        val gameData = PestGameData(
            difficulty = difficulty,
            players = players.toMutableList(),
            portalBaseHealth = if (difficulty == PestControlDifficulty.NOVICE) 2000 else 2500,
            pestData = data,
            playerDamage = mutableMapOf()
        )
        
        // Initialize player damage tracking
        for (player in players) {
            gameData.playerDamage[player] = 0
        }

        // Store instance tile for NPC spawning
        var instanceTile: Tile? = null

        for (player in players) {
            // Create instance following TzhaarFightCave pattern
            val instance = player.smallInstance(pestRegion, 3)
            if (instanceTile == null) {
                instanceTile = instance.tile
                gameData.instanceTile = instanceTile
                println("[PestControl] Instance tile: $instanceTile")
            }
            val playerOffset = player.instanceOffset()
            player.tele(entrance.add(playerOffset))
            player.setInstanceLogout(difficulty.exitTile)

            // Close waiting interface and open game interface
            player.interfaces.close("pest_control_waiting")
            player.interfaces.open("pest_control_playing")
            updateGameInterface(player, gameData)

            // Set player state
            player["pest_control_difficulty"] = difficulty.name
            player["pest_control_game_active"] = true
            player.remove<String>("pest_control_in_lobby")

            // Track game state per player
            activeGames[player] = gameData
            gameTimers[player] = gameData.timeRemaining
            pestSpawnTimers[player] = 10 // Spawn pests every 10 seconds

            player.message("Pest Control game starting!", ChatType.Console)
        }

        // Spawn NPCs in the instance after instances are created
        if (instanceTile != null) {
            spawnGameNPCs(gameData, instanceTile)
        }
    }

    /**
     * Spawns portals and Void Knight for the game.
     * 
     * @param gameData The game state data
     * @param instanceTile The world tile where the instance region starts
     */
    private fun spawnGameNPCs(gameData: PestGameData, instanceTile: Tile) {
        println("[PestControl] Spawning NPCs with instance tile: $instanceTile")
        
        // Spawn Void Knight at instance base + relative offset
        // Direct coordinate addition to avoid Delta wrapping issues with negative values
        val knightTile = Tile(instanceTile.x + knightOffset.x, instanceTile.y + knightOffset.y, instanceTile.level)
        println("[PestControl] Spawning Void Knight at: $knightTile")
        val knight = NPCs.add("void_knight", knightTile, Direction.SOUTH)
        knight["hitpoints"] = gameData.knightHealth
        println("[PestControl] Void Knight spawned: ${knight.index != -1}")

        // Spawn portals (purple, blue, yellow, red)
        for (i in portalOffsets.indices) {
            val portalTile = Tile(instanceTile.x + portalOffsets[i].x, instanceTile.y + portalOffsets[i].y, instanceTile.level)
            val portalId = listOf("portal_purple", "portal_blue", "portal_yellow", "portal_red")[i]
            println("[PestControl] Spawning $portalId at: $portalTile")
            val portal = NPCs.add(portalId, portalTile, Direction.SOUTH)
            portal["hitpoints"] = gameData.portalBaseHealth
            portal["damage_cap"] = 400
            portal["cant_follow_under_combat"] = true
            portal["force_multi_area"] = true
            portal["portal_index"] = i
            gameData.portalHealth[i] = gameData.portalBaseHealth
            println("[PestControl] Portal spawned: ${portal.index != -1}")
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

            // Decrement timer
            gameTimers[player] = timer - 1
            gameData.timeRemaining = timer - 1

            // Decrement pest spawn timer
            pestSpawnTimers[player] = pestTimer - 1

            // Spawn pests when timer reaches 0
            if (pestTimer <= 0) {
                spawnPests(gameData)
                pestSpawnTimers[player] = 10 // Reset to 10 seconds
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
        player.interfaces.sendText("pest_control_playing", "knight_health", "${gameData.knightHealth}")
        
        // Update player damage/activity display
        val playerDamage = gameData.playerDamage[player] ?: 0
        player.interfaces.sendText("pest_control_playing", "activity", "$playerDamage")
        
        // Update portal health displays (components 13-16)
        for (i in 0 until 4) {
            player.interfaces.sendText("pest_control_playing", "portal_${listOf("purple", "blue", "yellow", "red")[i]}_health", "${gameData.portalHealth[i]}")
        }
    }

    /**
     * Ends a Pest Control game with a win or lose result.
     * 
     * @param gameData The game state data
     * @param won Whether the game was won
     */
    private fun endGame(gameData: PestGameData, won: Boolean) {
        for (player in gameData.players) {
            player.interfaces.close("pest_control_playing")
            player.clearInstance()
            player.remove<String>("pest_control_difficulty")
            player.remove<Boolean>("pest_control_game_active")

            if (won) {
                val points = gameData.difficulty.let { 
                    when (it) {
                        PestControlDifficulty.NOVICE -> 5
                        PestControlDifficulty.INTERMEDIATE -> 6
                        PestControlDifficulty.VETERAN -> 8
                    }
                }
                val currentPoints = player["pest_control_points", 0]
                player["pest_control_points"] = currentPoints + points
                player.message("Congratulations! You successfully defended the Void Knight and earned $points commendation points!", ChatType.Console)
            } else {
                player.message("You failed to protect the Void Knight. No points awarded.", ChatType.Console)
            }

            // Teleport back to exit tile
            player.tele(gameData.difficulty.exitTile)
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
        val difficultyName = player["pest_control_difficulty", ""] ?: return true
        val difficulty = PestControlDifficulty.forName(difficultyName)

        if (player["pest_control_in_lobby", false]) {
            // Remove from lobby and teleport back to entrance
            difficulty?.let { diff ->
                val lobby = lobbies[diff]
                if (lobby != null) {
                    synchronized(lobby) {
                        lobby.remove(player)
                    }
                }
                player.interfaces.close("pest_control_waiting")
                player.tele(diff.exitTile)
            }
            player.remove<String>("pest_control_difficulty")
            player.remove<Boolean>("pest_control_in_lobby")
            return true
        }

        if (player["pest_control_game_active", false]) {
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
            player.clearInstance()
            player.interfaces.close("pest_control_waiting")
            player.interfaces.close("pest_control_playing")
            player.remove<String>("pest_control_difficulty")
            player.remove<Boolean>("pest_control_game_active")
            player.tele(difficulty?.exitTile ?: Tile(2657, 2639, 0))
            return true
        }

        return true
    }

}
