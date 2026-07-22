package com.crazylei12.pokemonchampionsassistant

import android.content.Context
import org.json.JSONObject
import kotlin.math.roundToInt

internal data class BattleDirectHudPlacement(
    val xFraction: Float,
    val yFraction: Float,
    val widthFraction: Float,
    val heightFraction: Float,
)

internal fun battleDirectHudLayoutProfileKey(region: OverlayBounds): String =
    if (region.width >= region.height) "landscape" else "portrait"

internal fun battleDirectHudPlacementFromBounds(
    region: OverlayBounds,
    bounds: OverlayBounds,
): BattleDirectHudPlacement {
    val regionWidth = region.width.coerceAtLeast(1)
    val regionHeight = region.height.coerceAtLeast(1)
    return BattleDirectHudPlacement(
        xFraction = ((bounds.left - region.left).toFloat() / regionWidth).coerceIn(0f, 1f),
        yFraction = ((bounds.top - region.top).toFloat() / regionHeight).coerceIn(0f, 1f),
        widthFraction = (bounds.width.toFloat() / regionWidth).coerceIn(0f, 1f),
        heightFraction = (bounds.height.toFloat() / regionHeight).coerceIn(0f, 1f),
    )
}

internal fun resolveBattleDirectHudPlacement(
    region: OverlayBounds,
    placement: BattleDirectHudPlacement,
    minimumWidth: Int,
    minimumHeight: Int,
): OverlayBounds {
    val safeWidth = region.width.coerceAtLeast(1)
    val safeHeight = region.height.coerceAtLeast(1)
    val minWidth = minimumWidth.coerceIn(1, safeWidth)
    val minHeight = minimumHeight.coerceIn(1, safeHeight)
    val width = (safeWidth * placement.widthFraction.coerceIn(0f, 1f)).roundToInt()
        .coerceIn(minWidth, safeWidth)
    val height = (safeHeight * placement.heightFraction.coerceIn(0f, 1f)).roundToInt()
        .coerceIn(minHeight, safeHeight)
    val proposedLeft = region.left + (safeWidth * placement.xFraction.coerceIn(0f, 1f)).roundToInt()
    val proposedTop = region.top + (safeHeight * placement.yFraction.coerceIn(0f, 1f)).roundToInt()
    val left = proposedLeft.coerceIn(region.left, (region.right - width).coerceAtLeast(region.left))
    val top = proposedTop.coerceIn(region.top, (region.bottom - height).coerceAtLeast(region.top))
    return OverlayBounds(left, top, left + width, top + height)
}

internal fun encodeBattleDirectHudPlacements(
    placements: Map<BattleDirectHudElement, BattleDirectHudPlacement>,
): String = JSONObject().apply {
    put("version", 1)
    put("elements", JSONObject().apply {
        placements
            .filterKeys { it != BattleDirectHudElement.EDIT }
            .forEach { (element, placement) ->
                put(element.name, JSONObject().apply {
                    put("x", placement.xFraction.toDouble())
                    put("y", placement.yFraction.toDouble())
                    put("width", placement.widthFraction.toDouble())
                    put("height", placement.heightFraction.toDouble())
                })
            }
    })
}.toString()

internal fun decodeBattleDirectHudPlacements(raw: String?): Map<BattleDirectHudElement, BattleDirectHudPlacement> {
    if (raw.isNullOrBlank()) return emptyMap()
    return runCatching {
        val root = JSONObject(raw)
        if (root.optInt("version") != 1) return@runCatching emptyMap()
        val elements = root.optJSONObject("elements") ?: return@runCatching emptyMap()
        buildMap {
            BattleDirectHudElement.values()
                .filter { it != BattleDirectHudElement.EDIT }
                .forEach { element ->
                    val value = elements.optJSONObject(element.name) ?: return@forEach
                    val placement = BattleDirectHudPlacement(
                        xFraction = value.optDouble("x", Double.NaN).toFloat(),
                        yFraction = value.optDouble("y", Double.NaN).toFloat(),
                        widthFraction = value.optDouble("width", Double.NaN).toFloat(),
                        heightFraction = value.optDouble("height", Double.NaN).toFloat(),
                    )
                    if (
                        placement.xFraction.isFinite() &&
                        placement.yFraction.isFinite() &&
                        placement.widthFraction.isFinite() &&
                        placement.heightFraction.isFinite() &&
                        placement.widthFraction > 0f &&
                        placement.heightFraction > 0f
                    ) {
                        put(element, placement)
                    }
                }
        }
    }.getOrDefault(emptyMap())
}

internal class BattleDirectHudLayoutStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(profileKey: String): Map<BattleDirectHudElement, BattleDirectHudPlacement> =
        decodeBattleDirectHudPlacements(preferences.getString(preferenceKey(profileKey), null))

    fun save(
        profileKey: String,
        placements: Map<BattleDirectHudElement, BattleDirectHudPlacement>,
    ): Boolean = preferences.edit()
        .putString(preferenceKey(profileKey), encodeBattleDirectHudPlacements(placements))
        .commit()

    private fun preferenceKey(profileKey: String): String = "layout_v1_$profileKey"

    private companion object {
        const val PREFERENCES_NAME = "battle_direct_hud_layout"
    }
}
