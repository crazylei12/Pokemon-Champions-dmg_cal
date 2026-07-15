package com.crazylei12.pokemonchampionsassistant

import java.text.Collator
import java.text.Normalizer
import java.util.Locale

enum class MoveSortMode(val label: String) {
    PINYIN("名称拼音"),
    TYPE("招式属性"),
}

private val moveTypeOrder = listOf(
    "Normal", "Fire", "Water", "Electric", "Grass", "Ice", "Fighting", "Poison", "Ground",
    "Flying", "Psychic", "Bug", "Rock", "Ghost", "Dragon", "Dark", "Steel", "Fairy", "Stellar",
).mapIndexed { index, type -> type.lowercase(Locale.ROOT) to index }.toMap()

fun sortMoves(
    moves: List<MoveValue>,
    mode: MoveSortMode,
    typeOf: (MoveValue) -> String? = MoveValue::type,
): List<MoveValue> {
    val collator = Collator.getInstance(Locale.SIMPLIFIED_CHINESE).apply {
        strength = Collator.PRIMARY
    }
    val byPinyin = Comparator<MoveValue> { left, right ->
        collator.compare(left.entity.displayName, right.entity.displayName)
            .takeIf { it != 0 }
            ?: left.entity.showdownId.compareTo(right.entity.showdownId, ignoreCase = true)
    }
    val comparator = when (mode) {
        MoveSortMode.PINYIN -> byPinyin
        MoveSortMode.TYPE -> Comparator { left, right ->
            val leftType = typeOf(left)?.lowercase(Locale.ROOT)
            val rightType = typeOf(right)?.lowercase(Locale.ROOT)
            val typeComparison = (moveTypeOrder[leftType] ?: Int.MAX_VALUE)
                .compareTo(moveTypeOrder[rightType] ?: Int.MAX_VALUE)
            if (typeComparison != 0) typeComparison else byPinyin.compare(left, right)
        }
    }
    return moves.sortedWith(comparator)
}

internal fun normalizeSearchText(value: String): String = Normalizer
    .normalize(value, Normalizer.Form.NFKC)
    .lowercase(Locale.ROOT)
    .filter(Char::isLetterOrDigit)

internal fun EntityValue.matchesSearch(query: String): Boolean {
    val normalizedQuery = normalizeSearchText(query)
    if (normalizedQuery.isBlank()) return true
    return sequenceOf(displayName, showdownId, canonicalId)
        .map(::normalizeSearchText)
        .any { normalizedCandidate -> normalizedCandidate.contains(normalizedQuery) }
}

internal fun MoveValue.matchesSearch(query: String): Boolean = entity.matchesSearch(query)
