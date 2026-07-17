package com.crazylei12.pokemonchampionsassistant

import android.content.Context
import android.view.WindowManager

internal class RecognitionFeatureHost(
    context: Context,
    windowManager: WindowManager,
    publish: (String) -> Unit,
    onBattleOverlayVisible: (Boolean) -> Unit,
    onCorrectionOverlayVisible: (Boolean) -> Unit,
    onOwnTeamSaved: (ImportSaveResult) -> Unit,
) {
    val ocrEngine = OwnTeamOcrEngine(context)
    val importRepository = OwnTeamImportRepository(context)
    val teamPreviewEngine = TeamPreviewRecognitionEngine(context).also { it.prepare() }
    val teamPreviewRepository = TeamPreviewResultRepository(context)
    val damageRuntime = DamageEngineRuntime(context)
    val battleOverlayController = BattleOverlayController(
        context = context,
        windowManager = windowManager,
        runtime = damageRuntime,
        sessionRepository = BattleSessionRepository(context),
        presetRepository = OpponentPresetRepository(context),
        publish = publish,
        onOverlayVisible = onBattleOverlayVisible,
    )
    val ownTeamCorrectionController = OwnTeamCorrectionOverlayController(
        context = context,
        windowManager = windowManager,
        importRepository = importRepository,
        presetRepository = OpponentPresetRepository(context),
        publish = publish,
        onOverlayVisible = onCorrectionOverlayVisible,
        onSaved = onOwnTeamSaved,
    )

    fun close() {
        ownTeamCorrectionController.close()
        battleOverlayController.closeAll()
        ocrEngine.close()
        teamPreviewEngine.close()
        damageRuntime.destroy()
    }
}
