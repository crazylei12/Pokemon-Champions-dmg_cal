package com.crazylei12.pokemonchampionsassistant

internal fun actualConfiguredMoves(configuredMoves: List<MoveValue>): List<MoveValue> =
    configuredMoves.distinctBy { normalizeMoveId(it.entity.showdownId) }

internal fun prioritizeLegalMoves(
    preferredMoves: List<MoveValue>,
    legalMoves: List<MoveValue>,
): List<MoveValue> {
    val legalIds = legalMoves.map { normalizeMoveId(it.entity.showdownId) }.toSet()
    return (preferredMoves.filter { normalizeMoveId(it.entity.showdownId) in legalIds } + legalMoves)
        .distinctBy { normalizeMoveId(it.entity.showdownId) }
}

internal fun configuredMoveOptions(
    configuredMoves: List<MoveValue>,
    legalMoves: List<MoveValue>,
): List<MoveValue> = actualConfiguredMoves(configuredMoves + legalMoves)

internal fun chooseCompatibleMoveId(
    moves: List<MoveValue>,
    selectedMoveId: String?,
    preferDamagingDefault: Boolean,
): String? = moves.firstOrNull {
    normalizeMoveId(it.entity.showdownId) == normalizeMoveId(selectedMoveId.orEmpty())
}?.entity?.showdownId
    ?: (if (preferDamagingDefault) moves.firstOrNull { (it.basePower ?: 0) > 0 } else null)?.entity?.showdownId
    ?: moves.firstOrNull()?.entity?.showdownId

private fun normalizeMoveId(value: String) = value.lowercase().replace(Regex("[^a-z0-9]+"), "")
