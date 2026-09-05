package com.crazylei12.pokemonchampionsassistant

internal enum class BattlePanelPage {
    DAMAGE,
    CONDITIONS,
    SPEED_LINE,
    OPPONENT_EDITOR,
}

internal class BattlePanelNavigation {
    var currentPage: BattlePanelPage = BattlePanelPage.DAMAGE
        private set

    var isVisible: Boolean = false
        private set

    fun show(page: BattlePanelPage) {
        currentPage = page
        isVisible = true
    }

    fun collapse() {
        isVisible = false
    }

    fun visibleSubpageForRefresh(): BattlePanelPage? =
        currentPage.takeIf { isVisible && it != BattlePanelPage.DAMAGE }

    fun openDetails() {
        currentPage = BattlePanelPage.DAMAGE
        isVisible = true
    }

    fun resetForTeamRecognition() {
        currentPage = BattlePanelPage.DAMAGE
        isVisible = false
    }
}
