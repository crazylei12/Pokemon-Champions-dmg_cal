package com.crazylei12.pokemonchampionsassistant

enum class BattleAssistantMode(
    val wireName: String,
    val autoOpenDirectHud: Boolean,
) {
    STANDARD("standard", false),
    HUD("hud", true),
    ;

    companion object {
        fun fromWireName(value: String?): BattleAssistantMode = values()
            .firstOrNull { it.wireName == value }
            ?: STANDARD
    }
}
