package com.crazylei12.pokemonchampionsassistant

internal data class CaptureBufferSpec(
    val width: Int,
    val height: Int,
    val densityDpi: Int,
    val rotation: Int = 0,
) {
    // VirtualDisplay and ImageReader use the unrotated display mode, while width/height
    // describe the oriented content size reported by MediaProjection.
    val virtualDisplayWidth: Int
        get() = if (rotation % 2 == 0) width else height

    val virtualDisplayHeight: Int
        get() = if (rotation % 2 == 0) height else width

    val bitmapRotationDegrees: Float
        get() = if (rotation == 0) 0f else -90f * rotation
}

internal fun changedCaptureBufferSpec(
    current: CaptureBufferSpec?,
    width: Int,
    height: Int,
    densityDpi: Int,
    rotation: Int = current?.rotation ?: 0,
): CaptureBufferSpec? {
    if (width <= 0 || height <= 0 || densityDpi <= 0 || rotation !in 0..3) return null
    return CaptureBufferSpec(width, height, densityDpi, rotation).takeUnless { it == current }
}
