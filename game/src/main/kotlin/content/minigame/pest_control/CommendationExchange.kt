package content.minigame.pest_control

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

class CommendationExchange : Script, InterfaceApi {

    companion object {
        private const val INTERFACE = 1011
        private const val RATE_ONE = 1
        private const val RATE_TEN = 10
        private const val RATE_HUNDRED = 100

        // XP related stuff
        private val SKILL_BASE_COMPONENTS = listOf(100, 116, 132, 148, 164, 180)
        private val SKILLS = listOf(
            Skill.Strength, Skill.Defence, Skill.Constitution, Skill.Ranged, Skill.Magic, Skill.Prayer
        )

        // Void related stuff
        private val VOID_BASE_COMPONENTS = listOf(15, 196, 208, 220, 232, 244, 256, 268, 280)
        private val VOID_ITEMS = listOf(
            "void_melee_helm_2", "void_ranger_helm_2", "void_mage_helm_2", "void_knight_top", "void_knight_robe", "void_knight_gloves", "void_knight_mace", "void_knight_deflector", "void_seal_8_8"
        )
        private val VOID_COSTS = listOf(200, 200, 200, 250, 250, 150, 250, 150, 10)

        // Charm related stuff
        private val CHARM_BASE_COMPONENTS = listOf(324, 339, 354, 369)
        private val CHARM_ITEMS = listOf("gold_charm", "green_charm", "crimson_charm", "blue_charm")
        private val CHARM_COST = 2

        fun openExchangeShop(player: Player) {
            player.interfaces.open("pest_control_rewards")
            refreshPoints(player)
            player.message("XP rewards are x10 the amount displayed.", ChatType.Game)
        }

        private fun refreshPoints(player: Player) {
            val points = player["pest_control_points", 0]
            player.interfaces.sendText("pest_control_rewards", "commendations", "Commendations: $points")
        }
    }

    init {
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

    private fun registerButtonHandlers() {
        // XP rewards for Attack (components 68, 86, 88)
        interfaceOption(id = "pest_control_rewards:attack_xp_1", option = "Exchange") {
            addXPForSkill(this, Skill.Attack, RATE_ONE)
        }
        interfaceOption(id = "pest_control_rewards:attack_xp_10", option = "Exchange") {
            addXPForSkill(this, Skill.Attack, RATE_TEN)
        }
        interfaceOption(id = "pest_control_rewards:attack_xp_100", option = "Exchange") {
            addXPForSkill(this, Skill.Attack, RATE_HUNDRED)
        }

        // Void equipment buttons
        for ((index, componentId) in VOID_BASE_COMPONENTS.withIndex()) {
            val component = getComponentName(componentId)
            if (component != null) {
                interfaceOption(id = "pest_control_rewards:$component", option = "Exchange") {
                    addVoidItem(this, index)
                }
            }
        }

        // Charm buttons
        for ((index, componentId) in CHARM_BASE_COMPONENTS.withIndex()) {
            val component = getComponentName(componentId)
            if (component != null) {
                interfaceOption(id = "pest_control_rewards:$component", option = "Exchange") {
                    addCharm(this, index, RATE_ONE)
                }
            }
        }

        // Resource packs
        interfaceOption(id = "pest_control_rewards:herblore_pack", option = "Exchange") {
            addHerblorePack(this)
        }
        interfaceOption(id = "pest_control_rewards:mineral_pack", option = "Exchange") {
            addMineralPack(this)
        }
        interfaceOption(id = "pest_control_rewards:seed_pack", option = "Exchange") {
            addSeedPack(this)
        }
    }

    private fun getComponentName(componentId: Int): String? {
        return when (componentId) {
            15 -> "void_top"
            196 -> "void_robe"
            208 -> "void_gloves"
            220 -> "void_mace"
            232 -> "void_ranger_helm"
            244 -> "void_mage_helm"
            256 -> "void_ranger_top"
            268 -> "void_mage_top"
            280 -> "void_seal"
            324 -> "gold_charm"
            339 -> "green_charm"
            354 -> "crimson_charm"
            369 -> "blue_charm"
            291 -> "herblore_pack"
            302 -> "mineral_pack"
            313 -> "seed_pack"
            else -> null
        }
    }

    fun openExchangeShop(player: Player) {
        player.interfaces.open("pest_control_rewards")
        refreshPoints(player)
        player.message("XP rewards are x10 the amount displayed.", ChatType.Game)
    }

    private fun refreshPoints(player: Player) {
        val points = player["pest_control_points", 0]
        player.interfaces.sendText("pest_control_rewards", "commendations", "Commendations: $points")
    }

    private fun exchangeCommendation(player: Player, price: Int): Boolean {
        val newPoints = player["pest_control_points", 0] - price
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
                break
            }
        }
        val experience = calculateExperience(player, skill) * rate
        player.experience.add(skill, experience)
        player.message("You gain ${experience.toInt()} experience in ${skill.name}.", ChatType.Game)
    }

    private fun addVoidItem(player: Player, index: Int) {
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
        val cost = VOID_COSTS[index]
        if (!exchangeCommendation(player, cost)) {
            return
        }
        val voidItem = VOID_ITEMS[index]
        player.inventory.transaction {
            add(voidItem)
        }
        when (player.inventory.transaction.error) {
            is TransactionError.Full -> player.message("You don't have enough inventory space.", ChatType.Game)
            TransactionError.None -> {
                val itemName = ItemDefinitions.get(voidItem).name.lowercase()
                player.message("You exchange $cost commendation points for a $itemName.", ChatType.Game)
                AuditLog.event(player, "bought", Item(voidItem), "pest_control_exchange", cost)
            }
            else -> {}
        }
    }

    private fun addCharm(player: Player, index: Int, rate: Int) {
        val actualRate = if (rate == 100) player.inventory.spaces else rate
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
        val charmItem = CHARM_ITEMS[index]
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

    private fun addHerblorePack(player: Player) {
        if (player.levels.get(Skill.Herblore) < 25) {
            player.message("You need a herblore level of 25 in order to purchase a herblore pack.", ChatType.Game)
            return
        }
        if (!exchangeCommendation(player, 30)) {
            return
        }
        // Add random herbs (simplified - in real implementation would use HerbCleaning.Herbs)
        val freeSlots = player.inventory.spaces
        player.inventory.transaction {
            add("clean_guam", min(5, freeSlots)) // Guam leaf
            add("clean_irit", min(4, freeSlots)) // Irit leaf
            add("clean_avantoe", min(3, freeSlots)) // Avantoe
            add("clean_kwuarm", min(2, freeSlots)) // Kwuarm
        }
        when (player.inventory.transaction.error) {
            is TransactionError.Full -> player.message("You don't have enough inventory space.", ChatType.Game)
            TransactionError.None -> {
                player.message("You exchange 30 commendation points for a herblore pack.", ChatType.Game)
            }
            else -> {}
        }
    }

    private fun addMineralPack(player: Player) {
        if (player.levels.get(Skill.Mining) < 25) {
            player.message("You need a mining level of 25 in order to purchase a mineral pack.", ChatType.Game)
            return
        }
        if (!exchangeCommendation(player, 15)) {
            return
        }
        val freeSlots = player.inventory.spaces
        player.inventory.transaction {
            add("copper_ore", min(20, freeSlots)) // Copper ore
            add("coal", min(30, freeSlots)) // Coal
        }
        when (player.inventory.transaction.error) {
            is TransactionError.Full -> player.message("You don't have enough inventory space.", ChatType.Game)
            TransactionError.None -> {
                player.message("You exchange 15 commendation points for a mineral pack.", ChatType.Game)
            }
            else -> {}
        }
    }

    private fun addSeedPack(player: Player) {
        if (player.levels.get(Skill.Farming) < 25) {
            player.message("You need a farming level of 25 in order to purchase a seed pack.", ChatType.Game)
            return
        }
        if (!exchangeCommendation(player, 15)) {
            return
        }
        val freeSlots = player.inventory.spaces
        // Add random seeds (simplified)
        player.inventory.transaction {
            add("potato_seed", min(5, freeSlots)) // Potato seed
            add("onion_seed", min(3, freeSlots)) // Onion seed
            add("cabbage_seed", min(2, freeSlots)) // Cabbage seed
        }
        when (player.inventory.transaction.error) {
            is TransactionError.Full -> player.message("You don't have enough inventory space.", ChatType.Game)
            TransactionError.None -> {
                player.message("You exchange 15 commendation points for a seed pack.", ChatType.Game)
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
