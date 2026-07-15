package com.crazylei12.pokemonchampionsassistant

import kotlin.math.abs
import kotlin.math.roundToInt

internal const val TEAM_PREVIEW_VIEWPORT_MAPPING_MODE = "largest_centered_aspect"

data class TeamPreviewViewport(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
)

internal data class TeamPreviewBaseRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

internal data class TeamPreviewPixelRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width get() = right - left
    val height get() = bottom - top
}

private data class TeamPreviewViewportGeometry(
    val left: Double,
    val top: Double,
    val width: Double,
    val height: Double,
)

internal fun parseTeamPreviewAspectRatio(value: String): Double {
    val parts = value.split(':', limit = 2)
    require(parts.size == 2) { "无效的队伍预览画布比例：$value" }
    val width = parts[0].toDoubleOrNull()
    val height = parts[1].toDoubleOrNull()
    require(width != null && height != null && width > 0.0 && height > 0.0) {
        "无效的队伍预览画布比例：$value"
    }
    return width / height
}

internal fun centeredTeamPreviewViewport(
    width: Int,
    height: Int,
    targetAspectRatio: Double,
): TeamPreviewViewport {
    val geometry = centeredTeamPreviewViewportGeometry(width, height, targetAspectRatio)
    return TeamPreviewViewport(
        left = geometry.left.roundToInt(),
        top = geometry.top.roundToInt(),
        width = geometry.width.roundToInt(),
        height = geometry.height.roundToInt(),
    )
}

private fun centeredTeamPreviewViewportGeometry(
    width: Int,
    height: Int,
    targetAspectRatio: Double,
): TeamPreviewViewportGeometry {
    require(width > 0 && height > 0 && targetAspectRatio.isFinite() && targetAspectRatio > 0.0) {
        "无效的队伍预览画布：${width}x$height，比例=$targetAspectRatio"
    }
    val imageAspectRatio = width.toDouble() / height
    if (abs(imageAspectRatio - targetAspectRatio) < 0.001) {
        return TeamPreviewViewportGeometry(0.0, 0.0, width.toDouble(), height.toDouble())
    }
    if (imageAspectRatio > targetAspectRatio) {
        val viewportWidth = height * targetAspectRatio
        return TeamPreviewViewportGeometry(
            left = ((width - viewportWidth) / 2.0).coerceAtLeast(0.0),
            top = 0.0,
            width = viewportWidth,
            height = height.toDouble(),
        )
    }
    val viewportHeight = width / targetAspectRatio
    return TeamPreviewViewportGeometry(
        left = 0.0,
        top = ((height - viewportHeight) / 2.0).coerceAtLeast(0.0),
        width = width.toDouble(),
        height = viewportHeight,
    )
}

internal fun mapTeamPreviewRectToImage(
    baseImageWidth: Int,
    baseImageHeight: Int,
    baseRect: TeamPreviewBaseRect,
    imageWidth: Int,
    imageHeight: Int,
    targetAspectRatio: Double,
): TeamPreviewPixelRect {
    val baseViewport = centeredTeamPreviewViewportGeometry(baseImageWidth, baseImageHeight, targetAspectRatio)
    val targetViewport = centeredTeamPreviewViewportGeometry(imageWidth, imageHeight, targetAspectRatio)
    require(
        baseRect.left >= baseViewport.left &&
            baseRect.top >= baseViewport.top &&
            baseRect.right <= baseViewport.left + baseViewport.width &&
            baseRect.bottom <= baseViewport.top + baseViewport.height,
    ) { "队伍预览 SafeZone 超出基准游戏画布：$baseRect / $baseViewport" }

    val scaleX = targetViewport.width / baseViewport.width
    val scaleY = targetViewport.height / baseViewport.height
    val targetBounds = centeredTeamPreviewViewport(imageWidth, imageHeight, targetAspectRatio)
    val x1 = (
        targetViewport.left + (baseRect.left - baseViewport.left) * scaleX
    ).roundToInt().coerceIn(targetBounds.left, targetBounds.left + targetBounds.width - 1)
    val y1 = (
        targetViewport.top + (baseRect.top - baseViewport.top) * scaleY
    ).roundToInt().coerceIn(targetBounds.top, targetBounds.top + targetBounds.height - 1)
    val x2 = (
        targetViewport.left + (baseRect.right - baseViewport.left) * scaleX
    ).roundToInt().coerceIn(x1 + 1, targetBounds.left + targetBounds.width)
    val y2 = (
        targetViewport.top + (baseRect.bottom - baseViewport.top) * scaleY
    ).roundToInt().coerceIn(y1 + 1, targetBounds.top + targetBounds.height)
    return TeamPreviewPixelRect(x1, y1, x2, y2)
}
