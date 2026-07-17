package com.crazylei12.pokemonchampionsassistant

import android.content.Context
import android.view.WindowInsets
import android.view.WindowManager
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowLayoutInfo
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal data class OverlayBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val area: Long get() = width.toLong() * height.toLong()

    fun inset(amount: Int): OverlayBounds {
        val horizontal = amount.coerceIn(0, width / 2)
        val vertical = amount.coerceIn(0, height / 2)
        return OverlayBounds(left + horizontal, top + vertical, right - horizontal, bottom - vertical)
    }

    fun offset(dx: Int, dy: Int): OverlayBounds =
        OverlayBounds(left + dx, top + dy, right + dx, bottom + dy)

    fun intersectionArea(other: OverlayBounds): Long =
        (min(right, other.right) - max(left, other.left)).coerceAtLeast(0).toLong() *
            (min(bottom, other.bottom) - max(top, other.top)).coerceAtLeast(0).toLong()
}
internal data class OverlayInsets(
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
)

internal enum class OverlayFoldOrientation { VERTICAL, HORIZONTAL }

internal data class OverlayOcclusion(
    val bounds: OverlayBounds,
    val orientation: OverlayFoldOrientation,
)

internal fun calculateOverlaySafeRegions(
    windowBounds: OverlayBounds,
    insets: OverlayInsets,
    occlusions: List<OverlayOcclusion>,
    foldPadding: Int,
): List<OverlayBounds> {
    val insetBounds = OverlayBounds(
        left = windowBounds.left + insets.left,
        top = windowBounds.top + insets.top,
        right = windowBounds.right - insets.right,
        bottom = windowBounds.bottom - insets.bottom,
    ).takeIf { it.width > 0 && it.height > 0 } ?: windowBounds

    return occlusions.fold(listOf(insetBounds)) { regions, occlusion ->
        regions.flatMap { region ->
            val crossesRegion = when (occlusion.orientation) {
                OverlayFoldOrientation.VERTICAL ->
                    occlusion.bounds.left <= region.right && occlusion.bounds.right >= region.left &&
                        occlusion.bounds.top < region.bottom && occlusion.bounds.bottom > region.top
                OverlayFoldOrientation.HORIZONTAL ->
                    occlusion.bounds.top <= region.bottom && occlusion.bounds.bottom >= region.top &&
                        occlusion.bounds.left < region.right && occlusion.bounds.right > region.left
            }
            if (!crossesRegion) {
                listOf(region)
            } else {
                when (occlusion.orientation) {
                    OverlayFoldOrientation.VERTICAL -> {
                        val gapLeft = (occlusion.bounds.left - foldPadding).coerceIn(region.left, region.right)
                        val gapRight = (occlusion.bounds.right + foldPadding).coerceIn(region.left, region.right)
                        listOf(
                            OverlayBounds(region.left, region.top, gapLeft, region.bottom),
                            OverlayBounds(gapRight, region.top, region.right, region.bottom),
                        )
                    }

                    OverlayFoldOrientation.HORIZONTAL -> {
                        val gapTop = (occlusion.bounds.top - foldPadding).coerceIn(region.top, region.bottom)
                        val gapBottom = (occlusion.bounds.bottom + foldPadding).coerceIn(region.top, region.bottom)
                        listOf(
                            OverlayBounds(region.left, region.top, region.right, gapTop),
                            OverlayBounds(region.left, gapBottom, region.right, region.bottom),
                        )
                    }
                }.filter { it.width > 0 && it.height > 0 }
            }
        }
    }.ifEmpty { listOf(insetBounds) }
}

internal fun chooseOverlaySafeRegion(
    regions: List<OverlayBounds>,
    reference: OverlayBounds? = null,
    preferEnd: Boolean = false,
): OverlayBounds {
    require(regions.isNotEmpty())
    return regions.maxWithOrNull(
        compareBy<OverlayBounds> { reference?.let(it::intersectionArea) ?: 0L }
            .thenBy { region ->
                reference?.let {
                    val centerX = (it.left.toLong() + it.right) / 2
                    val centerY = (it.top.toLong() + it.bottom) / 2
                    centerX >= region.left && centerX < region.right &&
                        centerY >= region.top && centerY < region.bottom
                } ?: false
            }
            .thenBy(OverlayBounds::area)
            .thenBy { if (preferEnd) it.right else -it.left },
    ) ?: regions.first()
}

internal fun clampOverlayPosition(
    regions: List<OverlayBounds>,
    proposedX: Int,
    proposedY: Int,
    width: Int,
    height: Int,
): OverlayPosition {
    val safeWidth = width.coerceAtLeast(1)
    val safeHeight = height.coerceAtLeast(1)
    val reference = OverlayBounds(
        proposedX,
        proposedY,
        proposedX + safeWidth,
        proposedY + safeHeight,
    )
    val region = chooseOverlaySafeRegion(regions, reference)
    val maxX = (region.right - safeWidth).coerceAtLeast(region.left)
    val maxY = (region.bottom - safeHeight).coerceAtLeast(region.top)
    return OverlayPosition(
        x = proposedX.coerceIn(region.left, maxX),
        y = proposedY.coerceIn(region.top, maxY),
    )
}

internal class OverlaySafeAreaProvider(private val windowContext: Context) {
    private val windowManager = windowContext.getSystemService(WindowManager::class.java)
    private val foldPadding = (8 * windowContext.resources.displayMetrics.density).roundToInt()

    @Volatile
    private var rawOcclusions: List<OverlayOcclusion> = emptyList()

    fun updateWindowLayoutInfo(layoutInfo: WindowLayoutInfo) {
        rawOcclusions = layoutInfo.displayFeatures
            .filterIsInstance<FoldingFeature>()
            .filter { it.isSeparating || it.occlusionType == FoldingFeature.OcclusionType.FULL }
            .map { feature ->
                OverlayOcclusion(
                    bounds = feature.bounds.let { OverlayBounds(it.left, it.top, it.right, it.bottom) },
                    orientation = if (feature.orientation == FoldingFeature.Orientation.VERTICAL) {
                        OverlayFoldOrientation.VERTICAL
                    } else {
                        OverlayFoldOrientation.HORIZONTAL
                    },
                )
            }
    }

    fun currentRegions(): List<OverlayBounds> {
        val metrics = windowManager.currentWindowMetrics
        val rect = metrics.bounds
        val windowBounds = OverlayBounds(rect.left, rect.top, rect.right, rect.bottom)
        val safeInsets = metrics.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
        )
        val occlusions = rawOcclusions.map { occlusion ->
            val raw = occlusion.bounds
            val windowRelative = raw.left >= 0 && raw.top >= 0 &&
                raw.right <= windowBounds.width && raw.bottom <= windowBounds.height
            val absolute = if (
                windowRelative && (windowBounds.left != 0 || windowBounds.top != 0)
            ) {
                raw.offset(windowBounds.left, windowBounds.top)
            } else {
                raw
            }
            occlusion.copy(bounds = absolute)
        }
        return calculateOverlaySafeRegions(
            windowBounds = windowBounds,
            insets = OverlayInsets(safeInsets.left, safeInsets.top, safeInsets.right, safeInsets.bottom),
            occlusions = occlusions,
            foldPadding = foldPadding,
        )
    }

    fun currentRegion(
        reference: OverlayBounds? = null,
        preferEnd: Boolean = false,
    ): OverlayBounds = chooseOverlaySafeRegion(currentRegions(), reference, preferEnd)

    fun clampPosition(
        proposedX: Int,
        proposedY: Int,
        width: Int,
        height: Int,
    ): OverlayPosition = clampOverlayPosition(currentRegions(), proposedX, proposedY, width, height)
}
