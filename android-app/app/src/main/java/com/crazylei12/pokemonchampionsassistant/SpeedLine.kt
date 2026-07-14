package com.crazylei12.pokemonchampionsassistant

enum class SpeedSide { OWN, OPPONENT }

private val PROTECTIVE_PRIORITY_MOVES = setOf(
    "banefulbunker",
    "burningbulwark",
    "craftyshield",
    "detect",
    "endure",
    "kingsshield",
    "matblock",
    "maxguard",
    "obstruct",
    "protect",
    "quickguard",
    "silktrap",
    "spikyshield",
    "wideguard",
)

fun isProtectivePriorityMove(showdownId: String): Boolean = showdownId
    .lowercase()
    .replace(Regex("[^a-z0-9]+"), "") in PROTECTIVE_PRIORITY_MOVES

data class SpeedLineMove(
    val name: String,
    val priority: Int,
)

data class SpeedLinePokemonInput(
    val side: SpeedSide,
    val slot: Int,
    val name: String,
    val baseSpeed: IntRange,
    val modifiers: SpeedPokemonModifiers = SpeedPokemonModifiers(),
    val tailwind: Boolean = false,
    val knownChoiceScarf: Boolean = false,
    val priorityMoves: List<SpeedLineMove> = emptyList(),
    val exactBaseSpeed: Boolean,
)

data class SpeedLineAction(
    val side: SpeedSide,
    val slot: Int,
    val pokemonName: String,
    val moveName: String?,
    val priority: Int,
    val speed: IntRange,
    val exactBaseSpeed: Boolean,
) {
    val isPoint: Boolean get() = speed.first == speed.last
    val label: String get() = buildString {
        append(if (side == SpeedSide.OWN) "我·" else "对·")
        append(pokemonName)
        moveName?.let {
            append("·")
            append(it)
        }
    }
}

fun possibleOpponentSpeedRange(baseSpeed: Int): IntRange {
    val minimum = ((baseSpeed + 20) * 9) / 10
    val maximum = ((baseSpeed + 52) * 11) / 10
    return minimum..maximum
}

fun effectiveSpeed(
    baseSpeed: Int,
    modifiers: SpeedPokemonModifiers,
    tailwind: Boolean,
    knownChoiceScarf: Boolean = false,
): Int {
    val stage = modifiers.stage.coerceIn(-6, 6)
    val stageNumerator = if (stage >= 0) 2L + stage else 2L
    val stageDenominator = if (stage >= 0) 2L else 2L - stage
    var speed = (baseSpeed.coerceAtLeast(1).toLong() * stageNumerator) / stageDenominator
    var modifierNumerator = 1L
    var modifierDenominator = 1L
    if (modifiers.doubled) modifierNumerator *= 2
    if (modifiers.choiceScarf ?: knownChoiceScarf) {
        modifierNumerator *= 3
        modifierDenominator *= 2
    }
    if (tailwind) modifierNumerator *= 2
    speed = (speed * modifierNumerator) / modifierDenominator
    if (modifiers.paralyzed) speed /= 2
    return speed.toInt().coerceIn(1, 10_000)
}

fun buildSpeedLineActions(inputs: List<SpeedLinePokemonInput>, trickRoom: Boolean): List<SpeedLineAction> =
    inputs.flatMap { pokemon ->
        val minimum = effectiveSpeed(
            pokemon.baseSpeed.first,
            pokemon.modifiers,
            pokemon.tailwind,
            pokemon.knownChoiceScarf,
        )
        val maximum = effectiveSpeed(
            pokemon.baseSpeed.last,
            pokemon.modifiers,
            pokemon.tailwind,
            pokemon.knownChoiceScarf,
        )
        val range = minOf(minimum, maximum)..maxOf(minimum, maximum)
        listOf(
            SpeedLineAction(
                side = pokemon.side,
                slot = pokemon.slot,
                pokemonName = pokemon.name,
                moveName = null,
                priority = 0,
                speed = range,
                exactBaseSpeed = pokemon.exactBaseSpeed,
            ),
        ) + pokemon.priorityMoves
            .filter { it.priority > 0 }
            .distinctBy { it.priority to it.name }
            .map { move ->
                SpeedLineAction(
                    side = pokemon.side,
                    slot = pokemon.slot,
                    pokemonName = pokemon.name,
                    moveName = move.name,
                    priority = move.priority,
                    speed = range,
                    exactBaseSpeed = pokemon.exactBaseSpeed,
                )
            }
    }.sortedWith(
        compareByDescending<SpeedLineAction> { it.priority }
            .thenComparator { left, right ->
                val leftMiddle = left.speed.first + left.speed.last
                val rightMiddle = right.speed.first + right.speed.last
                if (trickRoom) leftMiddle.compareTo(rightMiddle) else rightMiddle.compareTo(leftMiddle)
            }
            .thenBy { it.side.ordinal }
            .thenBy { it.slot }
            .thenBy { it.moveName.orEmpty() },
    )

fun speedAxisFraction(speed: Int, minimum: Int, maximum: Int, trickRoom: Boolean): Float {
    if (minimum >= maximum) return 0.5f
    val ascending = (speed - minimum).toFloat() / (maximum - minimum).toFloat()
    return (if (trickRoom) ascending else 1f - ascending).coerceIn(0f, 1f)
}
