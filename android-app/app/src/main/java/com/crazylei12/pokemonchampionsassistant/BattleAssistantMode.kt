package com.crazylei12.pokemonchampionsassistant

enum class BattleAssistantMode(
    val wireName: String,
    val autoOpenDirectHud: Boolean,
    val usesFloatingBubble: Boolean,
) {
    STANDARD("standard", false, true),
    HUD("hud", true, false),
    ;

    companion object {
        fun fromWireName(value: String?): BattleAssistantMode = values()
            .firstOrNull { it.wireName == value }
            ?: STANDARD
    }
}
