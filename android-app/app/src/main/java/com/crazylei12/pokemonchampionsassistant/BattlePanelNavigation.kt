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

    fun reopen(): BattlePanelPage {
        isVisible = true
        return currentPage
    }

    fun resetForTeamRecognition() {
        currentPage = BattlePanelPage.DAMAGE
        isVisible = false
    }
}
