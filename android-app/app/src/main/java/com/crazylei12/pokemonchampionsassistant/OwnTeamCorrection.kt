package com.crazylei12.pokemonchampionsassistant

enum class OwnTeamImportNextStep {
    CAPTURE_MOVE_ITEM,
    CAPTURE_STATS,
    MANUAL_CORRECTION,
    NAME_TEAM,
}

data class OwnTeamCorrectionSlot(
    val slotIndex: Int,
    val species: EntityValue?,
    val speciesConfirmed: Boolean,
    val ability: EntityValue?,
    val item: EntityValue?,
    val itemResolved: Boolean,
    val moves: List<MoveValue>,
    val recognizedMoveSlotIndexes: Set<Int> = moves.indices.toSet(),
    val actualStats: StatFields,
) {
    fun unresolvedFields(): List<String> = buildList {
        if (species == null) add("宝可梦")
        else if (!speciesConfirmed) add("宝可梦（两页结果冲突，请确认）")
        if (ability == null) add("特性")
        if (!itemResolved) add("道具（或确认无道具）")
        val uniqueMoveCount = moves.distinctBy { it.entity.showdownId.lowercase() }.size
        if (uniqueMoveCount < 4) {
            val recordedSlots = recognizedMoveSlotIndexes.filter { it in 0..3 }.toSet()
            val missingSlots = (0..3).filterNot(recordedSlots::contains)
            val labels = if (missingSlots.size == 4 - uniqueMoveCount) {
                missingSlots
            } else {
                (uniqueMoveCount until 4).toList()
            }
            labels.forEach { add("招式 ${it + 1}") }
        }
        if (uniqueMoveCount != moves.size) add("招式重复")
        val missingStats = ACTUAL_STAT_LABELS.filter { (id, _) ->
            (actualStats.asMap()[id]?.toIntOrNull() ?: 0) <= 0
        }.map { it.second }
        if (missingStats.isNotEmpty()) add("能力值：${missingStats.joinToString("、")}")
    }

    fun reminders(): List<String> = emptyList()

    fun isComplete(): Boolean = unresolvedFields().isEmpty()

    fun toPokemonConfig(): PokemonConfig {
        require(isComplete()) { "槽位 ${slotIndex + 1} 仍有未补全字段" }
        return PokemonConfig(
            species = requireNotNull(species),
            level = 50,
            actualStats = actualStats,
            statPoints = StatFields(),
            ability = ability,
            item = item,
            moves = moves.take(4),
        )
    }
}

data class OwnTeamCorrectionDraft(
    val moveRecognized: Int,
    val moveTotal: Int,
    val statsRecognized: Int,
    val statsTotal: Int,
    val moveItemCapturedAt: String,
    val statsCapturedAt: String,
    val slots: List<OwnTeamCorrectionSlot>,
)

internal data class OwnTeamDraftPages(
    val moveItemPage: RecognizedOwnTeamPage?,
    val statsPage: RecognizedOwnTeamPage?,
    val restarted: Boolean,
)

internal fun updateOwnTeamDraft(
    previousMoveItemPage: RecognizedOwnTeamPage?,
    previousStatsPage: RecognizedOwnTeamPage?,
    page: RecognizedOwnTeamPage,
): OwnTeamDraftPages = when (page.type) {
    OwnTeamPageType.MOVE_ITEM -> OwnTeamDraftPages(
        moveItemPage = page,
        statsPage = null,
        restarted = previousMoveItemPage != null || previousStatsPage != null,
    )
    OwnTeamPageType.STATS -> OwnTeamDraftPages(
        moveItemPage = previousMoveItemPage,
        statsPage = page,
        restarted = false,
    )
}

internal fun nextOwnTeamImportStep(
    move: RecognizedOwnTeamPage?,
    stats: RecognizedOwnTeamPage?,
): OwnTeamImportNextStep {
    if (move == null) return OwnTeamImportNextStep.CAPTURE_MOVE_ITEM
    if (stats == null) return OwnTeamImportNextStep.CAPTURE_STATS
    val draft = buildOwnTeamCorrectionDraft(move, stats)
    return if (draft.slots.any { !it.isComplete() }) {
        OwnTeamImportNextStep.MANUAL_CORRECTION
    } else {
        OwnTeamImportNextStep.NAME_TEAM
    }
}

internal fun buildOwnTeamCorrectionDraft(
    move: RecognizedOwnTeamPage,
    stats: RecognizedOwnTeamPage,
): OwnTeamCorrectionDraft = OwnTeamCorrectionDraft(
    moveRecognized = move.recognized,
    moveTotal = move.total,
    statsRecognized = stats.recognized,
    statsTotal = stats.total,
    moveItemCapturedAt = move.capturedAt,
    statsCapturedAt = stats.capturedAt,
    slots = (0 until 6).map { slotIndex ->
        val moveSlot = move.slots.firstOrNull { it.slotIndex == slotIndex }
        val statsSlot = stats.slots.firstOrNull { it.slotIndex == slotIndex }
        val moveSpecies = moveSlot?.species
        val statsSpecies = statsSlot?.species
        val speciesConflict = moveSpecies != null && statsSpecies != null &&
            moveSpecies.canonicalId != statsSpecies.canonicalId
        val species = when {
            moveSpecies == null -> statsSpecies
            statsSpecies == null -> moveSpecies
            speciesConflict && statsSpecies.confidence > moveSpecies.confidence -> statsSpecies
            else -> moveSpecies
        }
        OwnTeamCorrectionSlot(
            slotIndex = slotIndex,
            species = species?.toEntityValue(),
            speciesConfirmed = species != null && !speciesConflict,
            ability = moveSlot?.ability?.toEntityValue(),
            item = moveSlot?.item?.toEntityValue(),
            itemResolved = moveSlot?.item != null,
            moves = moveSlot?.moves.orEmpty().map { MoveValue(it.toEntityValue()) }.take(4),
            recognizedMoveSlotIndexes = moveSlot?.moveSlotIndexes.orEmpty().toSet(),
            actualStats = StatFields.fromMap(
                ACTUAL_STAT_LABELS.associate { (id, _) ->
                    id to statsSlot?.actualStats?.get(id)?.toString().orEmpty()
                },
            ),
        )
    },
)

private fun RecognitionEntity.toEntityValue() = EntityValue(
    canonicalId = canonicalId,
    showdownId = showdownId,
    displayName = displayName,
    entityType = entityType,
)

private val ACTUAL_STAT_LABELS = listOf(
    "hp" to "生命",
    "atk" to "攻击",
    "def" to "防御",
    "spa" to "特攻",
    "spd" to "特防",
    "spe" to "速度",
)
