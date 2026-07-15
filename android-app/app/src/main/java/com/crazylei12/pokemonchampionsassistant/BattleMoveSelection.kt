package com.crazylei12.pokemonchampionsassistant

internal fun compatibleConfiguredMoves(
    configuredMoves: List<MoveValue>,
    legalMoves: List<MoveValue>,
): List<MoveValue> {
    if (configuredMoves.isEmpty() || legalMoves.isEmpty()) return configuredMoves
    val legalIds = legalMoves.map { normalizeMoveId(it.entity.showdownId) }.toSet()
    return configuredMoves.filter { normalizeMoveId(it.entity.showdownId) in legalIds }
}

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
