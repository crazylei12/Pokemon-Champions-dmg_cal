package com.crazylei12.pokemonchampionsassistant

internal data class CaptureBufferSpec(
    val width: Int,
    val height: Int,
    val densityDpi: Int,
)

internal fun changedCaptureBufferSpec(
    current: CaptureBufferSpec?,
    width: Int,
    height: Int,
    densityDpi: Int,
): CaptureBufferSpec? {
    if (width <= 0 || height <= 0 || densityDpi <= 0) return null
    return CaptureBufferSpec(width, height, densityDpi).takeUnless { it == current }
}
