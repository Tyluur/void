package content.minigame.pest_control

import com.github.michaelbull.logging.InlineLogger
import world.gregs.config.Config
import world.gregs.voidps.engine.client.ui.InterfaceApi
import world.gregs.voidps.engine.client.ui.open
import world.gregs.voidps.engine.inv.inventory
import world.gregs.voidps.engine.Script
import world.gregs.voidps.engine.client.message
import world.gregs.voidps.engine.data.definition.ItemDefinitions
import world.gregs.voidps.engine.entity.character.player.Player
import world.gregs.voidps.engine.entity.character.player.chat.ChatType
import world.gregs.voidps.engine.entity.character.player.skill.Skill
import world.gregs.voidps.engine.entity.item.Item
import world.gregs.voidps.engine.event.AuditLog
import world.gregs.voidps.engine.inv.transact.TransactionError
import world.gregs.voidps.engine.inv.transact.operation.AddItem.add
import kotlin.math.ceil
import kotlin.math.min

data class CommendationExchangeConfig(
    val voidEquipment: VoidEquipmentConfig,
    val charms: CharmsConfig,
    val resourcePacks: ResourcePacksConfig
)

data class VoidEquipmentConfig(
    val items: List<String>,
    val costs: List<Int>
)

data class CharmsConfig(
    val items: List<String>
)

data class ResourcePacksConfig(
    val herblorePack: ResourcePackConfig,
    val mineralPack: ResourcePackConfig,
    val seedPack: ResourcePackConfig
)

data class ResourcePackConfig(
    val cost: Int,
    val skill: String,
    val level: Int
)

class CommendationExchange : Script, InterfaceApi {

    companion object {
        private val log = InlineLogger()
        private lateinit var config: CommendationExchangeConfig
        
        // Obvious default values that don't need configuration
        private const val RATE_ONE = 1
        private const val RATE_TEN = 10
        private const val RATE_HUNDRED = 100
        private const val CHARM_COST = 2
        
        fun loadConfig() {
            var voidEquipment: VoidEquipmentConfig? = null
            var charms: CharmsConfig? = null
            var herblorePack: ResourcePackConfig? = null
            var mineralPack: ResourcePackConfig? = null
            var seedPack: ResourcePackConfig? = null

            Config.fileReader("./data/minigame/pest_control/commendation_exchange.config.toml") {
                while (nextSection()) {
                    val section = section()
                    when (section) {
                        "void_equipment" -> {
                            var items = emptyList<String>()
                            var costs = emptyList<Int>()
                            while (nextPair()) {
                                when (key()) {
                                    "items" -> items = list().map { it as String }
                                    "costs" -> costs = list().map { it as Int }
                                }
                            }
                            voidEquipment = VoidEquipmentConfig(items, costs)
                        }
                        "charms" -> {
                            var items = emptyList<String>()
                            while (nextPair()) {
                                when (key()) {
                                    "items" -> items = list().map { it as String }
                                }
                            }
                            charms = CharmsConfig(items)
                        }
                        "herblore_pack" -> {
                            var cost = 0
                            var skill = ""
                            var level = 0
                            while (nextPair()) {
                                when (key()) {
                                    "cost" -> cost = int()
                                    "skill" -> skill = string()
                                    "level" -> level = int()
                                }
                            }
                            herblorePack = ResourcePackConfig(cost, skill, level)
                        }
                        "mineral_pack" -> {
                            var cost = 0
                            var skill = ""
                            var level = 0
                            while (nextPair()) {
                                when (key()) {
                                    "cost" -> cost = int()
                                    "skill" -> skill = string()
                                    "level" -> level = int()
                                }
                            }
                            mineralPack = ResourcePackConfig(cost, skill, level)
                        }
                        "seed_pack" -> {
                            var cost = 0
                            var skill = ""
                            var level = 0
                            while (nextPair()) {
                                when (key()) {
                                    "cost" -> cost = int()
                                    "skill" -> skill = string()
                                    "level" -> level = int()
                                }
                            }
                            seedPack = ResourcePackConfig(cost, skill, level)
                        }
                    }
                }
            }

            // Validate that all required values are loaded from TOML
            require(voidEquipment != null) { "Missing required config: void_equipment" }
            require(charms != null) { "Missing required config: charms" }
            require(herblorePack != null) { "Missing required config: herblore_pack" }
            require(mineralPack != null) { "Missing required config: mineral_pack" }
            require(seedPack != null) { "Missing required config: seed_pack" }

            val resourcePacks = ResourcePacksConfig(herblorePack, mineralPack, seedPack)
            config = CommendationExchangeConfig(
                voidEquipment, charms, resourcePacks
            )
        }
    }

    init {
        loadConfig()

        // Register interface button handlers
        registerButtonHandlers()

        // Register NPC interaction handlers
        npcOperate("Talk-to", "void_knight_2_pest_control") { (target) ->
            openExchangeShop(this)
        }
        npcOperate("Exchange", "void_knight_2_pest_control") { (target) ->
            openExchangeShop(this)
        }
    }

    private fun openExchangeShop(player: Player) {
        player.interfaces.open("pest_control_rewards")
        refreshPoints(player)
        showExperienceTab(player)
    }

    private fun refreshPoints(player: Player) {
        player.variables.send("pest_control_points")
    }

    private fun showExperienceTab(player: Player) {
        // Experience tab: hide tab buttons (69) and equipment/consumables (70), show experience (63)
        player.interfaces.sendVisibility("pest_control_rewards", "tab_buttons_container", false)
        player.interfaces.sendVisibility("pest_control_rewards", "equipment_consumables_tab_container", false)
    }

    private fun showEquipmentTab(player: Player) {
        // Equipment tab: hide equipment/consumables (70), show tab buttons + void equipment (69)
        player.interfaces.sendVisibility("pest_control_rewards", "equipment_consumables_tab_container", false)
        player.interfaces.sendVisibility("pest_control_rewards", "tab_buttons_container", true)
    }

    private fun showConsumablesTab(player: Player) {
        // Consumables tab: show equipment/consumables (70), hide tab buttons (69)
        player.interfaces.sendVisibility("pest_control_rewards", "equipment_consumables_tab_container", true)
        player.interfaces.sendVisibility("pest_control_rewards", "tab_buttons_container", false)
    }

    private fun registerButtonHandlers() {
        val cfg = config

        // Tab switching - matching Lotica's exact logic
        interfaceOption("Experience", "pest_control_rewards:experience_tab") {
            showExperienceTab(this)
        }
        interfaceOption("Experience", "pest_control_rewards:experience_tab_3") {
            showExperienceTab(this)
        }
        interfaceOption("Equipment", "pest_control_rewards:equipment_tab_2") {
            showEquipmentTab(this)
        }
        interfaceOption("Equipment", "pest_control_rewards:equipment_tab_3") {
            showEquipmentTab(this)
        }
        interfaceOption("Consumables", "pest_control_rewards:consumables_tab") {
            showConsumablesTab(this)
        }
        interfaceOption("Consumables", "pest_control_rewards:consumables_tab_2") {
            showConsumablesTab(this)
        }

        // XP rewards for Attack
        interfaceOption("Exchange-1", "pest_control_rewards:attack_xp_1") {
            log.debug { "Attack XP 1x triggered" }
            addXPForSkill(this, Skill.Attack, RATE_ONE)
        }
        interfaceOption("Exchange-10", "pest_control_rewards:attack_xp_10") {
            log.debug { "Attack XP 10x triggered" }
            addXPForSkill(this, Skill.Attack, RATE_TEN)
        }
        interfaceOption("Exchange-100", "pest_control_rewards:attack_xp_100") {
            log.debug { "Attack XP 100x triggered" }
            addXPForSkill(this, Skill.Attack, RATE_HUNDRED)
        }

        // XP rewards for Strength
        interfaceOption("Exchange-1", "pest_control_rewards:strength_xp_1") {
            addXPForSkill(this, Skill.Strength, RATE_ONE)
        }
        interfaceOption("Exchange-10", "pest_control_rewards:strength_xp_10") {
            addXPForSkill(this, Skill.Strength, RATE_TEN)
        }
        interfaceOption("Exchange-100", "pest_control_rewards:strength_xp_100") {
            addXPForSkill(this, Skill.Strength, RATE_HUNDRED)
        }

        // XP rewards for Defence
        interfaceOption("Exchange-1", "pest_control_rewards:defence_xp_1") {
            addXPForSkill(this, Skill.Defence, RATE_ONE)
        }
        interfaceOption("Exchange-10", "pest_control_rewards:defence_xp_10") {
            addXPForSkill(this, Skill.Defence, RATE_TEN)
        }
        interfaceOption("Exchange-100", "pest_control_rewards:defence_xp_100") {
            addXPForSkill(this, Skill.Defence, RATE_HUNDRED)
        }

        // XP rewards for Constitution
        interfaceOption("Exchange-1", "pest_control_rewards:constitution_xp_1") {
            addXPForSkill(this, Skill.Constitution, RATE_ONE)
        }
        interfaceOption("Exchange-10", "pest_control_rewards:constitution_xp_10") {
            addXPForSkill(this, Skill.Constitution, RATE_TEN)
        }
        interfaceOption("Exchange-100", "pest_control_rewards:constitution_xp_100") {
            addXPForSkill(this, Skill.Constitution, RATE_HUNDRED)
        }

        // XP rewards for Ranged
        interfaceOption("Exchange-1", "pest_control_rewards:ranged_xp_1") {
            addXPForSkill(this, Skill.Ranged, RATE_ONE)
        }
        interfaceOption("Exchange-10", "pest_control_rewards:ranged_xp_10") {
            addXPForSkill(this, Skill.Ranged, RATE_TEN)
        }
        interfaceOption("Exchange-100", "pest_control_rewards:ranged_xp_100") {
            addXPForSkill(this, Skill.Ranged, RATE_HUNDRED)
        }

        // XP rewards for Magic
        interfaceOption("Exchange-1", "pest_control_rewards:magic_xp_1") {
            addXPForSkill(this, Skill.Magic, RATE_ONE)
        }
        interfaceOption("Exchange-10", "pest_control_rewards:magic_xp_10") {
            addXPForSkill(this, Skill.Magic, RATE_TEN)
        }
        interfaceOption("Exchange-100", "pest_control_rewards:magic_xp_100") {
            addXPForSkill(this, Skill.Magic, RATE_HUNDRED)
        }

        // XP rewards for Prayer
        interfaceOption("Exchange-1", "pest_control_rewards:prayer_xp_1") {
            addXPForSkill(this, Skill.Prayer, RATE_ONE)
        }
        interfaceOption("Exchange-10", "pest_control_rewards:prayer_xp_10") {
            addXPForSkill(this, Skill.Prayer, RATE_TEN)
        }
        interfaceOption("Exchange-100", "pest_control_rewards:prayer_xp_100") {
            addXPForSkill(this, Skill.Prayer, RATE_HUNDRED)
        }

        // Void equipment buttons
        interfaceOption("Exchange", "pest_control_rewards:void_melee_helm") {
            addVoidItem(this, 0)
        }
        interfaceOption("Exchange", "pest_control_rewards:void_ranger_helm") {
            addVoidItem(this, 1)
        }
        interfaceOption("Exchange", "pest_control_rewards:void_mage_helm") {
            addVoidItem(this, 2)
        }
        interfaceOption("Exchange", "pest_control_rewards:void_top") {
            addVoidItem(this, 3)
        }
        interfaceOption("Exchange", "pest_control_rewards:void_robe") {
            addVoidItem(this, 4)
        }
        interfaceOption("Exchange", "pest_control_rewards:void_gloves") {
            addVoidItem(this, 5)
        }
        interfaceOption("Exchange", "pest_control_rewards:void_mace") {
            addVoidItem(this, 6)
        }
        interfaceOption("Exchange", "pest_control_rewards:void_deflector") {
            addVoidItem(this, 7)
        }
        interfaceOption("Exchange", "pest_control_rewards:void_seal") {
            addVoidItem(this, 8)
        }

        // Charm buttons
        interfaceOption("Exchange-1", "pest_control_rewards:gold_charm") {
            addCharm(this, 0, RATE_ONE)
        }
        interfaceOption("Exchange-10", "pest_control_rewards:gold_charm_10") {
            addCharm(this, 0, RATE_TEN)
        }
        interfaceOption("Exchange-All", "pest_control_rewards:gold_charm_all") {
            addCharm(this, 0, RATE_HUNDRED)
        }
        interfaceOption("Exchange-1", "pest_control_rewards:green_charm") {
            addCharm(this, 1, RATE_ONE)
        }
        interfaceOption("Exchange-10", "pest_control_rewards:green_charm_10") {
            addCharm(this, 1, RATE_TEN)
        }
        interfaceOption("Exchange-All", "pest_control_rewards:green_charm_all") {
            addCharm(this, 1, RATE_HUNDRED)
        }
        interfaceOption("Exchange-1", "pest_control_rewards:crimson_charm") {
            addCharm(this, 2, RATE_ONE)
        }
        interfaceOption("Exchange-10", "pest_control_rewards:crimson_charm_10") {
            addCharm(this, 2, RATE_TEN)
        }
        interfaceOption("Exchange-All", "pest_control_rewards:crimson_charm_all") {
            addCharm(this, 2, RATE_HUNDRED)
        }
        interfaceOption("Exchange-1", "pest_control_rewards:blue_charm") {
            addCharm(this, 3, RATE_ONE)
        }
        interfaceOption("Exchange-10", "pest_control_rewards:blue_charm_10") {
            addCharm(this, 3, RATE_TEN)
        }
        interfaceOption("Exchange-All", "pest_control_rewards:blue_charm_all") {
            addCharm(this, 3, RATE_HUNDRED)
        }

        // Resource packs
        interfaceOption("Exchange", "pest_control_rewards:herblore_pack") {
            addResourcePack(this, cfg.resourcePacks.herblorePack)
        }
        interfaceOption("Exchange", "pest_control_rewards:mineral_pack") {
            addResourcePack(this, cfg.resourcePacks.mineralPack)
        }
        interfaceOption("Exchange", "pest_control_rewards:seed_pack") {
            addResourcePack(this, cfg.resourcePacks.seedPack)
        }
    }


    private fun exchangeCommendation(player: Player, price: Int): Boolean {
        val currentPoints = player["pest_control_points", 0]
        val newPoints = currentPoints - price
        log.debug { "Exchange attempt: current=$currentPoints, price=$price, new=$newPoints" }
        if (newPoints < 0) {
            player.message("You don't have enough Commendations remaining to complete this exchange.", ChatType.Game)
            return false
        }
        player["pest_control_points"] = newPoints
        refreshPoints(player)
        return true
    }

    private fun addXPForSkill(player: Player, skill: Skill, rate: Int) {
        if (player.levels.getMax(skill) < 25) {
            player.message("You need a ${skill.name.lowercase()} level of at least 25 in order to gain experience.", ChatType.Game)
            return
        }
        for (i in 0 until rate) {
            if (!exchangeCommendation(player, 1)) {
                return
            }
        }
        val experience = calculateExperience(player, skill) * rate
        player.experience.add(skill, experience)
        player.message("You gain ${experience.toInt()} experience in ${skill.name}.", ChatType.Game)
    }

    private fun addVoidItem(player: Player, index: Int) {
        val cfg = config.voidEquipment
        // Check skill requirements: 42 Attack, Strength, Defence, Constitution, Range, Magic, 22 Prayer
        if (player.levels.get(Skill.Attack) < 42 ||
            player.levels.get(Skill.Strength) < 42 ||
            player.levels.get(Skill.Defence) < 42 ||
            player.levels.get(Skill.Constitution) < 42 ||
            player.levels.get(Skill.Ranged) < 42 ||
            player.levels.get(Skill.Magic) < 42 ||
            player.levels.get(Skill.Prayer) < 22) {
            player.message("You need an attack, strength, defence, constitution, range, and magic level of 42, and a prayer level of 22 in order to purchase void equipment.", ChatType.Game)
            return
        }
        if (player.inventory.spaces <= 0) {
            player.message("You don't have enough inventory space.", ChatType.Game)
            return
        }
        val cost = cfg.costs[index]
        if (!exchangeCommendation(player, cost)) {
            return
        }
        val voidItem = cfg.items[index]
        player.inventory.transaction {
            add(voidItem, 1)
        }
        when (player.inventory.transaction.error) {
            is TransactionError.Full -> player.message("You don't have enough inventory space.", ChatType.Game)
            TransactionError.None -> {
                val itemName = voidItem.lowercase()
                player.message("You exchange $cost commendation points for a $itemName.", ChatType.Game)
                AuditLog.event(player, "bought", Item(voidItem), "pest_control_exchange", cost)
            }
            else -> {}
        }
    }

    private fun addCharm(player: Player, index: Int, rate: Int) {
        val cfg = config
        val freeSlots = player.inventory.spaces
        val requestedRate = if (rate == RATE_HUNDRED) freeSlots else rate
        val actualRate = min(requestedRate, freeSlots)
        if (actualRate <= 0) {
            player.message("You don't have enough inventory space.", ChatType.Game)
            return
        }
        var exchanged = 0
        for (i in 0 until actualRate) {
            if (!exchangeCommendation(player, CHARM_COST)) {
                break
            }
            exchanged++
        }
        if (exchanged == 0) {
            return
        }
        val charmItem = cfg.charms.items[index]
        player.inventory.transaction {
            add(charmItem, exchanged)
        }
        when (player.inventory.transaction.error) {
            is TransactionError.Full -> player.message("You don't have enough inventory space.", ChatType.Game)
            TransactionError.None -> {
                player.message("You exchange ${exchanged * CHARM_COST} Commendations for a charm.", ChatType.Game)
            }
            else -> {}
        }
    }

    private fun addResourcePack(player: Player, packConfig: ResourcePackConfig) {
        val skill = Skill.valueOf(packConfig.skill.replaceFirstChar { it.uppercase() })
        if (player.levels.get(skill) < packConfig.level) {
            player.message("You need a ${packConfig.skill} level of ${packConfig.level} in order to purchase a ${packConfig.skill} pack.", ChatType.Game)
            return
        }
        var remainingSlots = player.inventory.spaces
        if (remainingSlots <= 0) {
            player.message("You don't have enough inventory space.", ChatType.Game)
            return
        }
        val additions = mutableListOf<Pair<String, Int>>()
        fun queue(item: String, amount: Int) {
            val toAdd = min(amount, remainingSlots)
            if (toAdd > 0) {
                additions.add(item to toAdd)
                remainingSlots -= toAdd
            }
        }
        when (packConfig.skill) {
            "herblore" -> {
                queue("clean_guam", 5)
                queue("clean_irit", 4)
                queue("clean_avantoe", 3)
                queue("clean_kwuarm", 2)
            }
            "mining" -> {
                queue("copper_ore", 20)
                queue("coal", 30)
            }
            "farming" -> {
                queue("potato_seed", 5)
                queue("onion_seed", 3)
                queue("cabbage_seed", 2)
            }
        }
        if (additions.isEmpty()) {
            player.message("You don't have enough inventory space.", ChatType.Game)
            return
        }
        if (!exchangeCommendation(player, packConfig.cost)) {
            return
        }
        player.inventory.transaction {
            for ((item, amount) in additions) {
                add(item, amount)
            }
        }
        when (player.inventory.transaction.error) {
            is TransactionError.Full -> player.message("You don't have enough inventory space.", ChatType.Game)
            TransactionError.None -> {
                player.message("You exchange ${packConfig.cost} commendation points for a ${packConfig.skill} pack.", ChatType.Game)
            }
            else -> {}
        }
    }

    private fun calculateExperience(player: Player, skill: Skill): Double {
        val level = player.levels.get(skill)
        val constant = when (skill) {
            Skill.Magic, Skill.Ranged -> 32
            Skill.Prayer -> 18
            else -> 35
        }
        return ceil(((level + 25) * (level - 24)) / 606.0 * constant) + constant
    }
}
