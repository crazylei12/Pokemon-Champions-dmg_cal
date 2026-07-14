package com.crazylei12.pokemonchampionsassistant

import android.content.Context
import android.graphics.Bitmap
import android.os.Debug
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvException
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfInt
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.time.Instant
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class TeamPreviewCandidate(
    val canonicalId: String,
    val showdownId: String,
    val displayName: String,
    val confidence: Double,
    val score: Double,
    val scoreMargin: Double,
    val source: String,
    val visualVariant: String,
    val isShiny: Boolean,
) {
    fun toJson() = JSONObject().apply {
        put("entityType", "SPECIES")
        put("canonicalId", canonicalId)
        put("showdownId", showdownId)
        put("displayName", displayName)
        put("confidence", confidence)
        put("score", score)
        put("scoreMargin", scoreMargin)
        put("source", source)
        put("visualVariant", visualVariant)
        put("isShiny", isShiny)
    }
}

data class TeamPreviewSlotResult(
    val side: String,
    val slotIndex: Int,
    val roiId: String,
    val candidates: List<TeamPreviewCandidate>,
) {
    val selected get() = candidates.firstOrNull()
    val requiresConfirmation get() = selected == null || selected!!.confidence < 0.90 || selected!!.scoreMargin < 0.035

    fun toJson() = JSONObject().apply {
        put("side", side)
        put("slotIndex", slotIndex)
        put("roiId", roiId)
        put("confirmed", false)
        put("requiresConfirmation", requiresConfirmation)
        selected?.let { put("selectedCandidate", it.toJson()) }
        put("candidates", JSONArray().apply { candidates.forEach { put(it.toJson()) } })
    }
}

data class TeamPreviewCaptureTiming(
    val requestedAtNanos: Long,
    val hideWaitMs: Double,
    val frameCopyMs: Double,
)

data class TeamPreviewSlotPerformance(
    val roiId: String,
    val cropMs: Double,
    val featureMs: Double,
    val strictColorMaskMs: Double,
    val relaxedColorMaskMs: Double,
    val grabCutMaskMs: Double,
    val maskSelectionMs: Double,
    val colorMaskQuality: Double,
    val adaptiveGrabCutFallback: Boolean,
    val rankMs: Double,
    val eligibleTemplates: Int,
    val refinedTemplates: Int,
) {
    fun toJson() = JSONObject().apply {
        put("roiId", roiId)
        put("cropMs", roundedMillis(cropMs))
        put("featureMs", roundedMillis(featureMs))
        put("strictColorMaskMs", roundedMillis(strictColorMaskMs))
        put("relaxedColorMaskMs", roundedMillis(relaxedColorMaskMs))
        put("grabCutMaskMs", roundedMillis(grabCutMaskMs))
        put("maskSelectionMs", roundedMillis(maskSelectionMs))
        put("colorMaskQuality", rounded(colorMaskQuality))
        put("adaptiveGrabCutFallback", adaptiveGrabCutFallback)
        put("rankMs", roundedMillis(rankMs))
        put("eligibleTemplates", eligibleTemplates)
        put("refinedTemplates", refinedTemplates)
    }
}

data class TeamPreviewPerformance(
    val captureHideWaitMs: Double,
    val frameCopyMs: Double,
    val executorQueueMs: Double,
    val openCvInitMs: Double,
    val templateLoadMs: Double,
    val templateLoadPerformed: Boolean,
    val roiConfigLoadMs: Double,
    val bitmapToBgrMs: Double,
    val featureTotalMs: Double,
    val rankTotalMs: Double,
    val engineWallMs: Double,
    val engineThreadCpuMs: Double,
    val slots: List<TeamPreviewSlotPerformance>,
) {
    fun toJson() = JSONObject().apply {
        put("captureHideWaitMs", roundedMillis(captureHideWaitMs))
        put("frameCopyMs", roundedMillis(frameCopyMs))
        put("executorQueueMs", roundedMillis(executorQueueMs))
        put("openCvInitMs", roundedMillis(openCvInitMs))
        put("templateLoadMs", roundedMillis(templateLoadMs))
        put("templateLoadPerformed", templateLoadPerformed)
        put("roiConfigLoadMs", roundedMillis(roiConfigLoadMs))
        put("bitmapToBgrMs", roundedMillis(bitmapToBgrMs))
        put("featureTotalMs", roundedMillis(featureTotalMs))
        put("rankTotalMs", roundedMillis(rankTotalMs))
        put("engineWallMs", roundedMillis(engineWallMs))
        put("engineThreadCpuMs", roundedMillis(engineThreadCpuMs))
        put("eligibleTemplateEvaluations", slots.sumOf { it.eligibleTemplates })
        put("refinedTemplateEvaluations", slots.sumOf { it.refinedTemplates })
        put("slots", JSONArray().apply { slots.forEach { put(it.toJson()) } })
    }
}

data class TeamPreviewRecognitionResult(
    val capturedAt: String,
    val width: Int,
    val height: Int,
    val elapsedMs: Long,
    val slots: List<TeamPreviewSlotResult>,
    val performance: TeamPreviewPerformance,
) {
    fun toJson() = JSONObject().apply {
        put("schemaVersion", 1)
        put("kind", "TeamPreviewRecognitionResult")
        put("sceneType", "TEAM_PREVIEW")
        put("capturedAt", capturedAt)
        put("imageSize", JSONObject().put("width", width).put("height", height))
        put("backend", "android_opencv_4.13.0")
        put("templateAsset", "team-preview-templates-v2.bin")
        put("elapsedMs", elapsedMs)
        put("performance", performance.toJson())
        put("confirmed", false)
        put("ownTeamCandidates", JSONArray().apply {
            slots.filter { it.side == "own" }.sortedBy { it.slotIndex }.forEach { put(it.toJson()) }
        })
        put("opponentTeamCandidates", JSONArray().apply {
            slots.filter { it.side == "opponent" }.sortedBy { it.slotIndex }.forEach { put(it.toJson()) }
        })
        put("warnings", JSONArray().apply {
            if (slots.any { it.requiresConfirmation }) put("Low-confidence or low-margin candidates require user confirmation before damage calculation.")
        })
    }

    fun summary(savedFileName: String): String {
        fun names(side: String) = slots.filter { it.side == side }.sortedBy { it.slotIndex }
            .joinToString("、") { slot -> (slot.selected?.displayName ?: "未识别") + if (slot.requiresConfirmation) "?" else "" }
        val pending = slots.count { it.requiresConfirmation }
        return "双方队伍 ROI 完成（${elapsedMs}ms，待确认 $pending/12）\n我方：${names("own")}\n对方：${names("opponent")}\n临时结果：$savedFileName（下次识别自动覆盖）"
    }
}

data class TeamPreviewSaveResult(
    val fileName: String,
    val json: JSONObject,
    val privateWriteMs: Double,
)

class TeamPreviewResultRepository(private val context: Context) {
    fun save(result: TeamPreviewRecognitionResult): TeamPreviewSaveResult {
        val name = "current-team-preview.json"
        val json = result.toJson()
        val body = json.toString(2)
        val privateStarted = System.nanoTime()
        context.filesDir.resolve("team-preview-results").deleteRecursively()
        context.filesDir.resolve("battle-session").resolve(name).writeUtf8Atomically(body)
        val privateWriteMs = nanosToMillis(System.nanoTime() - privateStarted)
        return TeamPreviewSaveResult(
            name,
            json,
            privateWriteMs,
        )
    }
}

class TeamPreviewRecognitionEngine(private val context: Context) : AutoCloseable {
    companion object {
        private const val ADAPTIVE_GRABCUT_MARGIN = 0.02
        private const val UNUSABLE_FOREGROUND_QUALITY = -9.5
    }

    private val executor = Executors.newSingleThreadExecutor()
    private var assets: TemplateAsset? = null
    private val regions by lazy { loadRegions() }

    fun prepare() {
        executor.execute {
            runCatching {
                val prepareStarted = System.nanoTime()
                check(OpenCVLoader.initLocal()) { "OpenCV 本地运行库初始化失败" }
                val grabCutWarmupStarted = System.nanoTime()
                prewarmGrabCutRuntime()
                val grabCutWarmupMs = nanosToMillis(System.nanoTime() - grabCutWarmupStarted)
                if (assets == null) assets = TemplateAsset.load(context)
                regions
                Log.i(
                    "TeamPreviewPerf",
                    "Background prepare complete: grabCutWarmupMs=${roundedMillis(grabCutWarmupMs)}, " +
                        "totalMs=${roundedMillis(nanosToMillis(System.nanoTime() - prepareStarted))}",
                )
            }.onFailure { Log.w("TeamPreviewPerf", "Background prepare failed", it) }
        }
    }

    fun recognize(
        bitmap: Bitmap,
        captureTiming: TeamPreviewCaptureTiming,
        callback: (Result<TeamPreviewRecognitionResult>) -> Unit,
    ) {
        val queuedAt = System.nanoTime()
        executor.execute {
            val queueMs = nanosToMillis(System.nanoTime() - queuedAt)
            callback(runCatching { recognizeBlocking(bitmap, captureTiming, queueMs) })
        }
    }

    private fun recognizeBlocking(
        bitmap: Bitmap,
        captureTiming: TeamPreviewCaptureTiming,
        executorQueueMs: Double,
    ): TeamPreviewRecognitionResult {
        val engineStarted = System.nanoTime()
        val cpuStarted = Debug.threadCpuTimeNanos()
        val openCvStarted = System.nanoTime()
        check(OpenCVLoader.initLocal()) { "OpenCV 本地运行库初始化失败" }
        val openCvInitMs = nanosToMillis(System.nanoTime() - openCvStarted)
        val templateLoadPerformed = assets == null
        val templateStarted = System.nanoTime()
        val templates = assets ?: TemplateAsset.load(context).also { assets = it }
        val templateLoadMs = nanosToMillis(System.nanoTime() - templateStarted)
        val roiStarted = System.nanoTime()
        val activeRegions = regions
        val roiConfigLoadMs = nanosToMillis(System.nanoTime() - roiStarted)
        val bitmapStarted = System.nanoTime()
        val rgba = Mat()
        val bgr = Mat()
        Utils.bitmapToMat(bitmap, rgba)
        Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
        rgba.release()
        val bitmapToBgrMs = nanosToMillis(System.nanoTime() - bitmapStarted)
        val slotPerformance = mutableListOf<TeamPreviewSlotPerformance>()
        val results = try {
            activeRegions.map { region ->
                val cropStarted = System.nanoTime()
                val rect = region.scaledRect(bitmap.width, bitmap.height)
                val crop = bgr.submat(rect).clone()
                val cropMs = nanosToMillis(System.nanoTime() - cropStarted)
                try {
                    val featureStarted = System.nanoTime()
                    var feature = VisionFeature.create(crop, templates.featureSize, templates.coarseSize)
                    var featureMs = nanosToMillis(System.nanoTime() - featureStarted)
                    var strictColorMaskMs = feature.strictColorMaskMs
                    var relaxedColorMaskMs = feature.relaxedColorMaskMs
                    var grabCutMaskMs = feature.grabCutMaskMs
                    var maskSelectionMs = feature.maskSelectionMs
                    val colorMaskQuality = feature.colorMaskQuality
                    var adaptiveGrabCutFallback = false
                    try {
                        val rankStarted = System.nanoTime()
                        var ranked = templates.rank(feature, region.sideKey, 3)
                        var rankMs = nanosToMillis(System.nanoTime() - rankStarted)
                        var eligibleTemplates = ranked.eligibleTemplateCount
                        var refinedTemplates = ranked.refinedTemplateCount
                        if (
                            feature.grabCutMaskMs == 0.0 &&
                            (ranked.candidates.firstOrNull()?.scoreMargin ?: 1.0) < ADAPTIVE_GRABCUT_MARGIN
                        ) {
                            val fallbackFeatureStarted = System.nanoTime()
                            val fallback = VisionFeature.create(
                                crop,
                                templates.featureSize,
                                templates.coarseSize,
                                forceGrabCut = true,
                            )
                            featureMs += nanosToMillis(System.nanoTime() - fallbackFeatureStarted)
                            feature.close()
                            feature = fallback
                            strictColorMaskMs += fallback.strictColorMaskMs
                            relaxedColorMaskMs += fallback.relaxedColorMaskMs
                            grabCutMaskMs += fallback.grabCutMaskMs
                            maskSelectionMs += fallback.maskSelectionMs
                            val fallbackRankStarted = System.nanoTime()
                            val fallbackRanked = templates.rank(fallback, region.sideKey, 3)
                            rankMs += nanosToMillis(System.nanoTime() - fallbackRankStarted)
                            eligibleTemplates += fallbackRanked.eligibleTemplateCount
                            refinedTemplates += fallbackRanked.refinedTemplateCount
                            ranked = fallbackRanked
                            adaptiveGrabCutFallback = true
                        }
                        slotPerformance += TeamPreviewSlotPerformance(
                            region.id,
                            cropMs,
                            featureMs,
                            strictColorMaskMs,
                            relaxedColorMaskMs,
                            grabCutMaskMs,
                            maskSelectionMs,
                            colorMaskQuality,
                            adaptiveGrabCutFallback,
                            rankMs,
                            eligibleTemplates,
                            refinedTemplates,
                        )
                        TeamPreviewSlotResult(
                            side = region.side,
                            slotIndex = region.slotIndex,
                            roiId = region.id,
                            candidates = ranked.candidates,
                        )
                    } finally {
                        feature.close()
                    }
                } finally {
                    crop.release()
                }
            }
        } finally {
            bgr.release()
        }
        check(slotPerformance.any { it.colorMaskQuality > UNUSABLE_FOREGROUND_QUALITY }) {
            "截图内容被系统屏蔽或画面不可见；请在通知面板点击‘解除屏蔽’后重试"
        }
        val engineWallMs = nanosToMillis(System.nanoTime() - engineStarted)
        val performance = TeamPreviewPerformance(
            captureHideWaitMs = captureTiming.hideWaitMs,
            frameCopyMs = captureTiming.frameCopyMs,
            executorQueueMs = executorQueueMs,
            openCvInitMs = openCvInitMs,
            templateLoadMs = templateLoadMs,
            templateLoadPerformed = templateLoadPerformed,
            roiConfigLoadMs = roiConfigLoadMs,
            bitmapToBgrMs = bitmapToBgrMs,
            featureTotalMs = slotPerformance.sumOf { it.featureMs },
            rankTotalMs = slotPerformance.sumOf { it.rankMs },
            engineWallMs = engineWallMs,
            engineThreadCpuMs = nanosToMillis(Debug.threadCpuTimeNanos() - cpuStarted),
            slots = slotPerformance,
        )
        Log.i("TeamPreviewPerf", performance.toJson().toString())
        return TeamPreviewRecognitionResult(
            capturedAt = Instant.now().toString(),
            width = bitmap.width,
            height = bitmap.height,
            elapsedMs = engineWallMs.toLong(),
            slots = results,
            performance = performance,
        )
    }

    private fun loadRegions(): List<TeamPreviewRegion> {
        val root = JSONObject(context.assets.open("recognition/team-preview.safe-zone-roi.zh-Hans.v1.json").bufferedReader().use { it.readText() })
        val width = root.getJSONObject("baseImageSize").getInt("width")
        val height = root.getJSONObject("baseImageSize").getInt("height")
        val array = root.getJSONArray("regions")
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val rect = item.getJSONObject("rect")
            TeamPreviewRegion(
                id = item.getString("id"),
                side = item.getString("side"),
                slotIndex = item.getInt("slotIndex"),
                baseWidth = width,
                baseHeight = height,
                left = rect.getInt("left"),
                top = rect.getInt("top"),
                right = rect.getInt("right"),
                bottom = rect.getInt("bottom"),
            )
        }.sortedWith(compareBy<TeamPreviewRegion> { if (it.side == "own") 0 else 1 }.thenBy { it.slotIndex })
    }

    override fun close() {
        executor.shutdownNow()
        assets?.close()
        assets = null
    }
}

private data class TeamPreviewRegion(
    val id: String,
    val side: String,
    val slotIndex: Int,
    val baseWidth: Int,
    val baseHeight: Int,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val sideKey get() = "$side.slot$slotIndex"

    fun scaledRect(width: Int, height: Int): Rect {
        val x1 = (left * width.toDouble() / baseWidth).roundToInt().coerceIn(0, width - 1)
        val y1 = (top * height.toDouble() / baseHeight).roundToInt().coerceIn(0, height - 1)
        val x2 = (right * width.toDouble() / baseWidth).roundToInt().coerceIn(x1 + 1, width)
        val y2 = (bottom * height.toDouble() / baseHeight).roundToInt().coerceIn(y1 + 1, height)
        return Rect(x1, y1, x2 - x1, y2 - y1)
    }
}

private data class TemplateWeights(val phash: Double, val edge: Double, val color: Double, val template: Double)

private class TemplateAsset(
    val featureSize: Int,
    val coarseSize: Int,
    private val defaultWeights: TemplateWeights,
    private val opponentWeights: TemplateWeights,
    private val labeledBonus: Double,
    private val templates: List<TemplateFeature>,
) : AutoCloseable {
    companion object {
        private const val MAGIC = "PTVFEAT2"
        private const val COARSE_SPECIES_TOP_K = 24

        fun load(context: Context): TemplateAsset {
            DataInputStream(BufferedInputStream(context.assets.open("recognition/team-preview-templates-v2.bin"), 1024 * 1024)).use { input ->
                val magic = ByteArray(8).also { input.readFully(it) }.toString(Charsets.US_ASCII)
                check(magic == MAGIC) { "未知队伍预览模板格式：$magic" }
                check(input.readInt() == 2) { "不支持的队伍预览模板版本" }
                val featureSize = input.readInt()
                val coarseSize = input.readInt()
                val histSize = input.readInt()
                val count = input.readInt()
                fun weights() = TemplateWeights(input.readFloat().toDouble(), input.readFloat().toDouble(), input.readFloat().toDouble(), input.readFloat().toDouble())
                val defaultWeights = weights()
                val opponentWeights = weights()
                val bonus = input.readFloat().toDouble()
                val graySize = featureSize * featureSize
                val edgeSize = (graySize + 7) / 8
                val templates = ArrayList<TemplateFeature>(count)
                repeat(count) {
                    fun text(): String = input.readUTF()
                    val canonicalId = text()
                    val showdownId = text()
                    val displayName = text()
                    text() // pokemonId is retained in the format for PC auditing.
                    val sideKey = text()
                    text() // sampleImage
                    val source = text()
                    val visualVariant = text()
                    text() // sourcePath
                    val shiny = input.readUnsignedByte() == 1
                    val bonusScale = input.readFloat().toDouble()
                    val grayBytes = ByteArray(graySize).also { input.readFully(it) }
                    val gray = Mat(featureSize, featureSize, CvType.CV_8UC1).apply { put(0, 0, grayBytes) }
                    val coarseGray = ByteArray(coarseSize * coarseSize).also { input.readFully(it) }
                    val edge = ByteArray(edgeSize).also { input.readFully(it) }
                    val histValues = FloatArray(histSize) { input.readFloat() }
                    val hist = Mat(histSize, 1, CvType.CV_32F).apply { put(0, 0, histValues) }
                    templates += TemplateFeature(
                        canonicalId, showdownId, displayName, sideKey, source, visualVariant, shiny,
                        bonusScale, gray, coarseGray, edge, hist, histValues, input.readLong(),
                    )
                }
                return TemplateAsset(featureSize, coarseSize, defaultWeights, opponentWeights, bonus, templates)
            }
        }
    }

    fun rank(query: VisionFeature, querySideKey: String, topK: Int): RankedCandidates {
        val querySide = querySideKey.substringBefore('.')
        val weights = if (querySide == "opponent") opponentWeights else defaultWeights
        val eligible = templates.filter {
            it.sideKey.isEmpty() || it.sideKey.substringBefore('.') == querySide
        }
        val coarseBest = HashMap<String, Double>()
        eligible.forEach { template ->
            val coarseTemplateScore = normalizeCorrelation(byteCorrelation(query.coarseGray, template.coarseGray))
            val colorScore = normalizeCorrelation(floatCorrelation(query.histValues, template.histValues))
            val phashScore = 1.0 - java.lang.Long.bitCount(query.phash xor template.phash) / 64.0
            val bonus = if (template.sideKey == querySideKey) labeledBonus * min(query.bonusScale, template.bonusScale) else 0.0
            val score = phashScore * weights.phash + colorScore * weights.color +
                coarseTemplateScore * weights.template + bonus
            val current = coarseBest[template.canonicalId]
            if (current == null || score > current) coarseBest[template.canonicalId] = score
        }
        val shortlist = coarseBest.entries.sortedByDescending { it.value }
            .take(COARSE_SPECIES_TOP_K).mapTo(HashSet()) { it.key }
        val refined = eligible.filter { it.canonicalId in shortlist }
        val best = HashMap<String, ScoredTemplate>()
        val result = Mat()
        try {
            refined.forEach { template ->
                Imgproc.matchTemplate(query.gray, template.gray, result, Imgproc.TM_CCOEFF_NORMED)
                val templateScore = normalizeCorrelation(result.get(0, 0)[0])
                val edgeScore = bitIoU(query.edgeBits, template.edgeBits)
                val colorScore = normalizeCorrelation(Imgproc.compareHist(query.hist, template.hist, Imgproc.HISTCMP_CORREL))
                val phashScore = 1.0 - java.lang.Long.bitCount(query.phash xor template.phash) / 64.0
                val base = phashScore * weights.phash + edgeScore * weights.edge + colorScore * weights.color + templateScore * weights.template
                val bonus = if (template.sideKey == querySideKey) labeledBonus * min(query.bonusScale, template.bonusScale) else 0.0
                val scored = ScoredTemplate(template, base + bonus)
                val current = best[template.canonicalId]
                if (current == null || scored.score > current.score) best[template.canonicalId] = scored
            }
        } finally {
            result.release()
        }
        val ranked = best.values.sortedByDescending { it.score }.take(topK)
        val candidates = ranked.mapIndexed { index, scored ->
            val next = ranked.getOrNull(index + 1)?.score ?: 0.0
            val margin = if (index == 0) max(0.0, scored.score - next) else 0.0
            TeamPreviewCandidate(
                canonicalId = scored.template.canonicalId,
                showdownId = scored.template.showdownId,
                displayName = scored.template.displayName,
                confidence = scored.score.coerceIn(0.0, 1.0),
                score = rounded(scored.score),
                scoreMargin = rounded(margin),
                source = scored.template.source,
                visualVariant = scored.template.visualVariant,
                isShiny = scored.template.isShiny,
            )
        }
        return RankedCandidates(candidates, eligible.size, refined.size)
    }

    override fun close() = templates.forEach { it.close() }
}

private class TemplateFeature(
    val canonicalId: String,
    val showdownId: String,
    val displayName: String,
    val sideKey: String,
    val source: String,
    val visualVariant: String,
    val isShiny: Boolean,
    val bonusScale: Double,
    val gray: Mat,
    val coarseGray: ByteArray,
    val edgeBits: ByteArray,
    val hist: Mat,
    val histValues: FloatArray,
    val phash: Long,
) : AutoCloseable {
    override fun close() {
        gray.release()
        hist.release()
    }
}

private data class ScoredTemplate(val template: TemplateFeature, val score: Double)
private data class RankedCandidates(
    val candidates: List<TeamPreviewCandidate>,
    val eligibleTemplateCount: Int,
    val refinedTemplateCount: Int,
)

private class VisionFeature(
    val gray: Mat,
    val coarseGray: ByteArray,
    val edgeBits: ByteArray,
    val hist: Mat,
    val histValues: FloatArray,
    val phash: Long,
    val bonusScale: Double,
    val strictColorMaskMs: Double,
    val relaxedColorMaskMs: Double,
    val grabCutMaskMs: Double,
    val maskSelectionMs: Double,
    val colorMaskQuality: Double,
) : AutoCloseable {
    companion object {
        fun create(
            source: Mat,
            featureSize: Int,
            coarseSize: Int,
            forceGrabCut: Boolean = false,
        ): VisionFeature {
            val foreground = foregroundMask(source, forceGrabCut)
            var mask = foreground.mask
            if (Core.countNonZero(mask) < max(8, (mask.total() * 0.005).roundToInt())) {
                mask.release()
                mask = Mat(source.rows(), source.cols(), CvType.CV_8UC1, Scalar(255.0))
            }
            val bonusScale = labeledBonusScale(mask, source)
            val bbox = paddedBbox(maskBbox(mask) ?: Rect(0, 0, source.cols(), source.rows()), source.cols(), source.rows(), 0.06)
            val croppedBgr = source.submat(bbox).clone()
            val croppedMask = mask.submat(bbox).clone()
            mask.release()
            val normalized = normalizeColor(croppedBgr)
            croppedBgr.release()
            val (canvasBgr, canvasMask) = resizeFit(normalized, croppedMask, featureSize)
            normalized.release()
            croppedMask.release()
            val gray = Mat()
            Imgproc.cvtColor(canvasBgr, gray, Imgproc.COLOR_BGR2GRAY)
            val coarse = Mat()
            Imgproc.resize(gray, coarse, Size(coarseSize.toDouble(), coarseSize.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            val coarseGray = ByteArray(coarseSize * coarseSize).also { coarse.get(0, 0, it) }
            coarse.release()
            Core.bitwise_and(gray, gray, gray, canvasMask)
            val edge = Mat()
            Imgproc.Canny(gray, edge, 60.0, 150.0)
            Core.bitwise_and(edge, edge, edge, canvasMask)
            val hsv = Mat()
            Imgproc.cvtColor(canvasBgr, hsv, Imgproc.COLOR_BGR2HSV)
            val hist = Mat()
            Imgproc.calcHist(listOf(hsv), MatOfInt(0, 1), canvasMask, hist, MatOfInt(24, 16), MatOfFloat(0f, 180f, 0f, 256f))
            val sum = Core.sumElems(hist).`val`[0]
            if (sum > 0.0) Core.multiply(hist, Scalar(1.0 / sum), hist)
            val flatHist = hist.reshape(1, hist.total().toInt()).clone()
            hist.release()
            val edgeBits = matBits(edge)
            val phash = perceptualHash(gray)
            edge.release()
            hsv.release()
            canvasBgr.release()
            canvasMask.release()
            val histValues = FloatArray(flatHist.total().toInt()).also { flatHist.get(0, 0, it) }
            return VisionFeature(
                gray,
                coarseGray,
                edgeBits,
                flatHist,
                histValues,
                phash,
                bonusScale,
                foreground.strictColorMs,
                foreground.relaxedColorMs,
                foreground.grabCutMs,
                foreground.selectionMs,
                foreground.colorQuality,
            )
        }
    }

    override fun close() {
        gray.release()
        hist.release()
    }
}

private data class BackgroundPrototype(val bgr: DoubleArray, val hsv: DoubleArray)

private data class ForegroundMaskResult(
    val mask: Mat,
    val strictColorMs: Double,
    val relaxedColorMs: Double,
    val grabCutMs: Double,
    val selectionMs: Double,
    val colorQuality: Double,
)

private fun prewarmGrabCutRuntime() {
    val sample = Mat(48, 48, CvType.CV_8UC3, Scalar(36.0, 64.0, 96.0))
    Imgproc.rectangle(sample, Point(12.0, 10.0), Point(36.0, 38.0), Scalar(180.0, 92.0, 42.0), -1)
    Imgproc.circle(sample, Point(24.0, 24.0), 8, Scalar(40.0, 190.0, 210.0), -1)
    try {
        grabcutForegroundMask(sample).release()
    } finally {
        sample.release()
    }
}

private fun foregroundMask(bgr: Mat, forceGrabCut: Boolean = false): ForegroundMaskResult {
    val strictStarted = System.nanoTime()
    val strict = colorDistanceForegroundMask(bgr, 52.0, 32.0)
    val strictMs = nanosToMillis(System.nanoTime() - strictStarted)
    val relaxedStarted = System.nanoTime()
    val relaxed = colorDistanceForegroundMask(bgr, 42.0, 26.0)
    val relaxedMs = nanosToMillis(System.nanoTime() - relaxedStarted)
    val color = chooseColorMaskVariant(strict, relaxed)
    if (color !== strict) strict.release()
    if (color !== relaxed) relaxed.release()
    val earlySelectionStarted = System.nanoTime()
    val colorQuality = foregroundMaskQuality(color)
    if (!forceGrabCut && colorQuality >= 3.5) {
        return ForegroundMaskResult(
            color,
            strictMs,
            relaxedMs,
            0.0,
            nanosToMillis(System.nanoTime() - earlySelectionStarted),
            colorQuality,
        )
    }
    val grabCutStarted = System.nanoTime()
    val grabcut = grabcutForegroundMask(bgr)
    val grabCutMs = nanosToMillis(System.nanoTime() - grabCutStarted)
    val selectionStarted = System.nanoTime()
    val chosen = chooseForegroundMask(color, grabcut, bgr)
    val selectionMs = nanosToMillis(System.nanoTime() - selectionStarted)
    if (chosen !== color) color.release()
    if (chosen !== grabcut) grabcut.release()
    return ForegroundMaskResult(chosen, strictMs, relaxedMs, grabCutMs, selectionMs, colorQuality)
}

private fun colorDistanceForegroundMask(bgr: Mat, distanceThreshold: Double, hsvThreshold: Double): Mat {
    val hsv = Mat()
    Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV)
    val prototypes = sampleBackgroundPrototypes(bgr, hsv)
    val bgrBytes = ByteArray((bgr.total() * 3).toInt()).also { bgr.get(0, 0, it) }
    val hsvBytes = ByteArray((hsv.total() * 3).toInt()).also { hsv.get(0, 0, it) }
    hsv.release()
    val output = ByteArray(bgr.rows() * bgr.cols())
    val bgrLimit = distanceThreshold * distanceThreshold
    val hsvLimit = hsvThreshold * hsvThreshold
    for (pixel in output.indices) {
        val offset = pixel * 3
        val b0 = bgrBytes[offset].toInt() and 0xff
        val b1 = bgrBytes[offset + 1].toInt() and 0xff
        val b2 = bgrBytes[offset + 2].toInt() and 0xff
        val h0 = hsvBytes[offset].toInt() and 0xff
        val h1 = hsvBytes[offset + 1].toInt() and 0xff
        val h2 = hsvBytes[offset + 2].toInt() and 0xff
        var minBgr = Double.MAX_VALUE
        var minHsv = Double.MAX_VALUE
        prototypes.forEach { prototype ->
            val db0 = b0 - prototype.bgr[0]
            val db1 = b1 - prototype.bgr[1]
            val db2 = b2 - prototype.bgr[2]
            minBgr = min(minBgr, db0 * db0 + db1 * db1 + db2 * db2)
            val rawHue = abs(h0 - prototype.hsv[0])
            val dh = min(rawHue, 180.0 - rawHue) * 1.6
            val ds = (h1 - prototype.hsv[1]) * 0.45
            val dv = (h2 - prototype.hsv[2]) * 0.45
            minHsv = min(minHsv, dh * dh + ds * ds + dv * dv)
        }
        output[pixel] = if (minBgr > bgrLimit && minHsv > hsvLimit) 0xff.toByte() else 0
    }
    var mask = Mat(bgr.rows(), bgr.cols(), CvType.CV_8UC1).apply { put(0, 0, output) }
    val kernel = Mat.ones(3, 3, CvType.CV_8UC1)
    Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, kernel)
    Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel)
    kernel.release()
    val cleaned = removeUiFrameArtifacts(mask, bgr)
    mask.release()
    mask = selectForegroundComponents(cleaned, bgr.cols(), bgr.rows())
    cleaned.release()
    return mask
}

private fun grabcutForegroundMask(bgr: Mat): Mat {
    val width = bgr.cols()
    val height = bgr.rows()
    if (width < 12 || height < 12) return Mat.zeros(height, width, CvType.CV_8UC1)
    val mask = Mat(height, width, CvType.CV_8UC1, Scalar(Imgproc.GC_PR_BGD.toDouble()))
    val border = max(2, (min(width, height) * 0.035).roundToInt())
    mask.submat(0, border, 0, width).setTo(Scalar(Imgproc.GC_BGD.toDouble()))
    mask.submat(height - border, height, 0, width).setTo(Scalar(Imgproc.GC_BGD.toDouble()))
    mask.submat(0, height, 0, border).setTo(Scalar(Imgproc.GC_BGD.toDouble()))
    mask.submat(0, height, width - border, width).setTo(Scalar(Imgproc.GC_BGD.toDouble()))
    val marginX = min(width / 3, max(border + 1, (width * 0.12).roundToInt()))
    val marginY = min(height / 3, max(border + 1, (height * 0.10).roundToInt()))
    mask.submat(marginY, height - marginY, marginX, width - marginX).setTo(Scalar(Imgproc.GC_PR_FGD.toDouble()))
    Imgproc.ellipse(mask, Point(width / 2.0, height / 2.0), Size(max(2, (width * 0.30).toInt()).toDouble(), max(2, (height * 0.36).toInt()).toDouble()), 0.0, 0.0, 360.0, Scalar(Imgproc.GC_PR_FGD.toDouble()), -1)
    val bgdModel = Mat.zeros(1, 65, CvType.CV_64F)
    val fgdModel = Mat.zeros(1, 65, CvType.CV_64F)
    try {
        Core.setRNGSeed(0)
        Imgproc.grabCut(bgr, mask, Rect(), bgdModel, fgdModel, 3, Imgproc.GC_INIT_WITH_MASK)
    } catch (_: CvException) {
        mask.release(); bgdModel.release(); fgdModel.release()
        return Mat.zeros(height, width, CvType.CV_8UC1)
    }
    bgdModel.release(); fgdModel.release()
    val definite = Mat()
    val probable = Mat()
    Core.compare(mask, Scalar(Imgproc.GC_FGD.toDouble()), definite, Core.CMP_EQ)
    Core.compare(mask, Scalar(Imgproc.GC_PR_FGD.toDouble()), probable, Core.CMP_EQ)
    Core.bitwise_or(definite, probable, definite)
    probable.release(); mask.release()
    val kernel = Mat.ones(3, 3, CvType.CV_8UC1)
    Imgproc.morphologyEx(definite, definite, Imgproc.MORPH_OPEN, kernel)
    Imgproc.morphologyEx(definite, definite, Imgproc.MORPH_CLOSE, kernel)
    kernel.release()
    val cleaned = removeUiFrameArtifacts(definite, bgr)
    definite.release()
    val selected = selectForegroundComponents(cleaned, width, height)
    cleaned.release()
    return selected
}

private fun chooseColorMaskVariant(strict: Mat, relaxed: Mat): Mat {
    val strictPixels = Core.countNonZero(strict)
    val relaxedPixels = Core.countNonZero(relaxed)
    if (strictPixels == 0) return relaxed
    if (relaxedPixels < strictPixels * 1.08) return strict
    val overlap = intersectionCount(strict, relaxed) / max(1.0, strictPixels.toDouble())
    if (overlap < 0.82) return strict
    val strictScore = foregroundMaskQuality(strict)
    val relaxedScore = foregroundMaskQuality(relaxed)
    if (relaxedScore < strictScore - 0.15) return strict
    val bbox = maskBbox(relaxed) ?: return strict
    if (bbox.width >= relaxed.cols() * 0.96 || bbox.height >= relaxed.rows() * 0.96) return strict
    return relaxed
}

private fun chooseForegroundMask(color: Mat, grabcut: Mat, bgr: Mat): Mat {
    val colorScore = foregroundMaskQuality(color)
    val grabcutScore = foregroundMaskQuality(grabcut)
    val colorPixels = Core.countNonZero(color)
    val grabcutPixels = Core.countNonZero(grabcut)
    if (colorPixels > 0 && grabcutPixels > 0) {
        val intersection = intersectionCount(color, grabcut)
        val overlap = intersection / max(1.0, grabcutPixels.toDouble())
        if (shouldKeepColorOverGrabcut(color, grabcut, colorScore, grabcutScore, overlap)) return color
        if (overlap >= 0.65 && colorPixels >= grabcutPixels * 1.25 && colorScore >= grabcutScore - 1.25) return color
        val reverseOverlap = intersection / max(1.0, colorPixels.toDouble())
        if (reverseOverlap >= 0.65 && grabcutPixels >= colorPixels * 1.25 && grabcutScore >= colorScore - 0.30) {
            val edgeDensity = addedRegionEdgeDensity(bgr, color, grabcut)
            if (edgeDensity >= 0.12 && grabcutScore >= colorScore + 0.02) return grabcut
            return color
        }
    }
    return if (grabcutScore > colorScore) grabcut else color
}

private fun shouldKeepColorOverGrabcut(color: Mat, grabcut: Mat, colorScore: Double, grabcutScore: Double, overlap: Double): Boolean {
    if (overlap < 0.80 || colorScore < grabcutScore - 0.10) return false
    val colorBox = maskBbox(color) ?: return false
    val grabcutBox = maskBbox(grabcut) ?: return false
    return grabcutBox.width / grabcut.cols().toDouble() <= colorBox.width / color.cols().toDouble() * 0.88 ||
        grabcutBox.height / grabcut.rows().toDouble() <= colorBox.height / color.rows().toDouble() * 0.88
}

private fun foregroundMaskQuality(mask: Mat): Double {
    val width = mask.cols()
    val height = mask.rows()
    val foreground = Core.countNonZero(mask)
    if (foreground < max(8, (width * height * 0.003).roundToInt())) return -10.0
    val bbox = maskBbox(mask) ?: return -10.0
    val ratio = foreground / (width * height).toDouble()
    val widthRatio = bbox.width / width.toDouble()
    val heightRatio = bbox.height / height.toDouble()
    val border = max(1, (min(width, height) * 0.03).roundToInt())
    val borderMask = Mat.zeros(height, width, CvType.CV_8UC1)
    borderMask.submat(0, border, 0, width).setTo(Scalar(1.0))
    borderMask.submat(height - border, height, 0, width).setTo(Scalar(1.0))
    borderMask.submat(0, height, 0, border).setTo(Scalar(1.0))
    borderMask.submat(0, height, width - border, width).setTo(Scalar(1.0))
    val borderPixels = Core.countNonZero(borderMask)
    val intersection = Mat()
    Core.bitwise_and(mask, borderMask, intersection)
    val borderForeground = Core.countNonZero(intersection) / max(1.0, borderPixels.toDouble())
    borderMask.release(); intersection.release()
    val touches = bbox.x <= 1 || bbox.y <= 1 || bbox.x + bbox.width >= width - 1 || bbox.y + bbox.height >= height - 1
    var score = if (ratio in 0.035..0.65) 2.0 else -1.5
    score += if (widthRatio <= 0.92) 1.0 else -1.0
    score += if (heightRatio <= 0.92) 1.0 else -1.0
    score -= borderForeground * 4.0
    if (touches) score -= 0.8
    score -= (abs(bbox.x + bbox.width * 0.5 - width * 0.5) / max(1.0, width.toDouble()) +
        abs(bbox.y + bbox.height * 0.5 - height * 0.5) / max(1.0, height.toDouble())) * 0.75
    return score
}

private fun sampleBackgroundPrototypes(bgr: Mat, hsv: Mat): List<BackgroundPrototype> {
    val width = bgr.cols()
    val height = bgr.rows()
    val boxes = listOf(
        Rect(0, 0, max(1, (width * 0.18).toInt()), max(1, (height * 0.18).toInt())),
        Rect((width * 0.82).toInt(), 0, max(1, (width * 0.18).toInt()), max(1, (height * 0.18).toInt())),
        Rect(0, (height * 0.82).toInt(), max(1, (width * 0.18).toInt()), max(1, (height * 0.18).toInt())),
        Rect((width * 0.82).toInt(), (height * 0.82).toInt(), max(1, (width * 0.18).toInt()), max(1, (height * 0.18).toInt())),
        Rect(0, 0, width, max(1, (height * 0.05).toInt())),
        Rect(0, (height * 0.95).toInt(), width, max(1, (height * 0.05).toInt())),
    )
    return boxes.map { raw ->
        val rect = Rect(raw.x, raw.y, min(raw.width, width - raw.x), min(raw.height, height - raw.y))
        BackgroundPrototype(channelMedians(bgr.submat(rect)), channelMedians(hsv.submat(rect)))
    }
}

private fun channelMedians(mat: Mat): DoubleArray {
    val values = ByteArray((mat.total() * 3).toInt()).also { mat.get(0, 0, it) }
    return DoubleArray(3) { channel ->
        val samples = IntArray(values.size / 3) { index -> values[index * 3 + channel].toInt() and 0xff }
        samples.sort()
        if (samples.size % 2 == 1) samples[samples.size / 2].toDouble()
        else (samples[samples.size / 2 - 1] + samples[samples.size / 2]) / 2.0
    }
}

private fun selectForegroundComponents(mask: Mat, width: Int, height: Int): Mat {
    val labels = Mat()
    val stats = Mat()
    val centroids = Mat()
    val count = Imgproc.connectedComponentsWithStats(mask, labels, stats, centroids, 8, CvType.CV_32S)
    val components = mutableListOf<Pair<Int, Double>>()
    val total = width * height
    for (label in 1 until count) {
        val left = stats.get(label, Imgproc.CC_STAT_LEFT)[0].toInt()
        val top = stats.get(label, Imgproc.CC_STAT_TOP)[0].toInt()
        val boxWidth = stats.get(label, Imgproc.CC_STAT_WIDTH)[0].toInt()
        val boxHeight = stats.get(label, Imgproc.CC_STAT_HEIGHT)[0].toInt()
        val area = stats.get(label, Imgproc.CC_STAT_AREA)[0].toInt()
        if (area < max(14, (total * 0.0015).roundToInt()) || boxWidth < width * 0.06 || boxHeight < height * 0.06) continue
        val dx = (centroids.get(label, 0)[0] - width / 2.0) / max(1.0, width.toDouble())
        val dy = (centroids.get(label, 1)[0] - height / 2.0) / max(1.0, height.toDouble())
        val centerWeight = 1.0 / (1.0 + hypot(dx, dy) * 2.2)
        val touches = left == 0 || top == 0 || left + boxWidth >= width || top + boxHeight >= height
        components += label to area * centerWeight * if (touches) 0.65 else 1.0
    }
    if (components.isEmpty()) {
        labels.release(); stats.release(); centroids.release()
        return mask.clone()
    }
    val best = components.maxOf { it.second }
    val selected = Mat.zeros(mask.rows(), mask.cols(), CvType.CV_8UC1)
    val componentMask = Mat()
    components.filter { it.second >= best * 0.20 }.forEach { (label, _) ->
        Core.compare(labels, Scalar(label.toDouble()), componentMask, Core.CMP_EQ)
        Core.bitwise_or(selected, componentMask, selected)
    }
    componentMask.release(); labels.release(); stats.release(); centroids.release()
    return selected
}

private fun removeUiFrameArtifacts(mask: Mat, bgr: Mat): Mat {
    val seed = uiFrameArtifactSeed(bgr)
    val band = roiEdgeBandMask(mask.rows(), mask.cols())
    Core.bitwise_and(seed, band, seed)
    if (Core.countNonZero(seed) == 0) {
        seed.release(); band.release()
        return mask.clone()
    }
    val kernel3 = Mat.ones(3, 3, CvType.CV_8UC1)
    Imgproc.dilate(seed, seed, kernel3)
    kernel3.release()
    val kernel5 = Mat.ones(5, 5, CvType.CV_8UC1)
    Imgproc.dilate(band, band, kernel5)
    kernel5.release()
    Core.bitwise_and(seed, band, seed)
    val cleaned = mask.clone()
    cleaned.setTo(Scalar(0.0), seed)
    seed.release(); band.release()
    return cleaned
}

private fun uiFrameArtifactSeed(bgr: Mat): Mat {
    val hsv = Mat()
    Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV)
    val seed = Mat()
    Core.inRange(hsv, Scalar(0.0, 0.0, 130.0), Scalar(180.0, 130.0, 255.0), seed)
    hsv.release()
    val kernel = Mat.ones(3, 3, CvType.CV_8UC1)
    Imgproc.morphologyEx(seed, seed, Imgproc.MORPH_CLOSE, kernel)
    kernel.release()
    return seed
}

private fun roiEdgeBandMask(height: Int, width: Int): Mat {
    val result = Mat.zeros(height, width, CvType.CV_8UC1)
    val left = max(2, (width * 0.08).roundToInt())
    val right = max(2, (width * 0.22).roundToInt())
    val top = max(2, (height * 0.08).roundToInt())
    val bottom = max(2, (height * 0.08).roundToInt())
    result.submat(0, height, 0, left).setTo(Scalar(255.0))
    result.submat(0, height, width - right, width).setTo(Scalar(255.0))
    result.submat(0, top, 0, width).setTo(Scalar(255.0))
    result.submat(height - bottom, height, 0, width).setTo(Scalar(255.0))
    return result
}

private fun addedRegionEdgeDensity(bgr: Mat, baseMask: Mat, expandedMask: Mat): Double {
    val dilated = Mat()
    val kernel = Mat.ones(3, 3, CvType.CV_8UC1)
    Imgproc.dilate(baseMask, dilated, kernel)
    val notBase = Mat()
    Core.bitwise_not(dilated, notBase)
    val added = Mat()
    Core.bitwise_and(expandedMask, notBase, added)
    val pixels = Core.countNonZero(added)
    if (pixels < max(8, (added.total() * 0.0005).roundToInt())) {
        dilated.release(); kernel.release(); notBase.release(); added.release()
        return 0.0
    }
    val gray = Mat()
    Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
    val edge = Mat()
    Imgproc.Canny(gray, edge, 40.0, 110.0)
    Imgproc.dilate(edge, edge, kernel)
    Core.bitwise_and(edge, added, edge)
    val result = Core.countNonZero(edge) / pixels.toDouble()
    dilated.release(); kernel.release(); notBase.release(); added.release(); gray.release(); edge.release()
    return result
}

private fun labeledBonusScale(mask: Mat, bgr: Mat): Double {
    val bbox = maskBbox(mask) ?: return 1.0
    val foreground = Core.countNonZero(mask)
    val foregroundRatio = foreground / mask.total().toDouble()
    val widthRatio = bbox.width / mask.cols().toDouble()
    val heightRatio = bbox.height / mask.rows().toDouble()
    if (uiFrameArtifactRatio(mask, bgr) >= 0.015) return 0.0
    if (foregroundRatio >= 0.48 && widthRatio >= 0.72 && heightRatio >= 0.72) return 0.0
    return 1.0
}

private fun uiFrameArtifactRatio(mask: Mat, bgr: Mat): Double {
    val foreground = Core.countNonZero(mask)
    if (foreground == 0) return 0.0
    val seed = uiFrameArtifactSeed(bgr)
    val band = roiEdgeBandMask(mask.rows(), mask.cols())
    Core.bitwise_and(seed, band, seed)
    Core.bitwise_and(seed, mask, seed)
    val ratio = Core.countNonZero(seed) / foreground.toDouble()
    seed.release(); band.release()
    return ratio
}

private fun normalizeColor(bgr: Mat): Mat {
    val lab = Mat()
    Imgproc.cvtColor(bgr, lab, Imgproc.COLOR_BGR2Lab)
    val channels = ArrayList<Mat>(3)
    Core.split(lab, channels)
    val clahe = Imgproc.createCLAHE(1.6, Size(4.0, 4.0))
    clahe.apply(channels[0], channels[0])
    Core.merge(channels, lab)
    channels.forEach { it.release() }
    val result = Mat()
    Imgproc.cvtColor(lab, result, Imgproc.COLOR_Lab2BGR)
    lab.release()
    return result
}

private fun resizeFit(bgr: Mat, mask: Mat, size: Int): Pair<Mat, Mat> {
    val scale = min(size / max(1.0, bgr.cols().toDouble()), size / max(1.0, bgr.rows().toDouble()))
    val width = max(1, (bgr.cols() * scale).roundToInt())
    val height = max(1, (bgr.rows() * scale).roundToInt())
    val resizedBgr = Mat()
    val resizedMask = Mat()
    Imgproc.resize(bgr, resizedBgr, Size(width.toDouble(), height.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
    Imgproc.resize(mask, resizedMask, Size(width.toDouble(), height.toDouble()), 0.0, 0.0, Imgproc.INTER_NEAREST)
    val canvasBgr = Mat.zeros(size, size, CvType.CV_8UC3)
    val canvasMask = Mat.zeros(size, size, CvType.CV_8UC1)
    val left = (size - width) / 2
    val top = (size - height) / 2
    resizedBgr.copyTo(canvasBgr.submat(top, top + height, left, left + width))
    resizedMask.copyTo(canvasMask.submat(top, top + height, left, left + width))
    resizedBgr.release(); resizedMask.release()
    return canvasBgr to canvasMask
}

private fun perceptualHash(gray: Mat): Long {
    val resized = Mat()
    Imgproc.resize(gray, resized, Size(32.0, 32.0), 0.0, 0.0, Imgproc.INTER_AREA)
    resized.convertTo(resized, CvType.CV_32F)
    Core.dct(resized, resized)
    val values = FloatArray(32 * 32).also { resized.get(0, 0, it) }
    resized.release()
    val medianValues = FloatArray(49)
    var index = 0
    for (y in 1 until 8) for (x in 1 until 8) medianValues[index++] = values[y * 32 + x]
    medianValues.sort()
    val median = medianValues[medianValues.size / 2]
    var hash = 0L
    for (y in 0 until 8) for (x in 0 until 8) {
        if (values[y * 32 + x] > median) hash = hash or (1L shl (63 - (y * 8 + x)))
    }
    return hash
}

private fun matBits(mat: Mat): ByteArray {
    val values = ByteArray(mat.total().toInt()).also { mat.get(0, 0, it) }
    val result = ByteArray((values.size + 7) / 8)
    values.forEachIndexed { index, value ->
        if (value.toInt() != 0) result[index / 8] = (result[index / 8].toInt() or (1 shl (7 - index % 8))).toByte()
    }
    return result
}

private fun bitIoU(left: ByteArray, right: ByteArray): Double {
    var intersection = 0
    var union = 0
    for (index in left.indices) {
        val a = left[index].toInt() and 0xff
        val b = right[index].toInt() and 0xff
        intersection += Integer.bitCount(a and b)
        union += Integer.bitCount(a or b)
    }
    return if (union == 0) 0.0 else intersection / union.toDouble()
}

private fun byteCorrelation(left: ByteArray, right: ByteArray): Double {
    require(left.size == right.size)
    var sumLeft = 0.0
    var sumRight = 0.0
    var sumLeftSquared = 0.0
    var sumRightSquared = 0.0
    var sumProduct = 0.0
    for (index in left.indices) {
        val a = (left[index].toInt() and 0xff).toDouble()
        val b = (right[index].toInt() and 0xff).toDouble()
        sumLeft += a
        sumRight += b
        sumLeftSquared += a * a
        sumRightSquared += b * b
        sumProduct += a * b
    }
    return pearsonCorrelation(left.size, sumLeft, sumRight, sumLeftSquared, sumRightSquared, sumProduct)
}

private fun floatCorrelation(left: FloatArray, right: FloatArray): Double {
    require(left.size == right.size)
    var sumLeft = 0.0
    var sumRight = 0.0
    var sumLeftSquared = 0.0
    var sumRightSquared = 0.0
    var sumProduct = 0.0
    for (index in left.indices) {
        val a = left[index].toDouble()
        val b = right[index].toDouble()
        sumLeft += a
        sumRight += b
        sumLeftSquared += a * a
        sumRightSquared += b * b
        sumProduct += a * b
    }
    return pearsonCorrelation(left.size, sumLeft, sumRight, sumLeftSquared, sumRightSquared, sumProduct)
}

private fun pearsonCorrelation(
    count: Int,
    sumLeft: Double,
    sumRight: Double,
    sumLeftSquared: Double,
    sumRightSquared: Double,
    sumProduct: Double,
): Double {
    val numerator = count * sumProduct - sumLeft * sumRight
    val leftVariance = count * sumLeftSquared - sumLeft * sumLeft
    val rightVariance = count * sumRightSquared - sumRight * sumRight
    val denominator = sqrt(max(0.0, leftVariance) * max(0.0, rightVariance))
    return if (denominator <= 1e-12) 0.0 else (numerator / denominator).coerceIn(-1.0, 1.0)
}

private fun intersectionCount(left: Mat, right: Mat): Int {
    val intersection = Mat()
    Core.bitwise_and(left, right, intersection)
    val result = Core.countNonZero(intersection)
    intersection.release()
    return result
}

private fun maskBbox(mask: Mat): Rect? {
    if (Core.countNonZero(mask) == 0) return null
    val points = Mat()
    Core.findNonZero(mask, points)
    val rect = Imgproc.boundingRect(points)
    points.release()
    return rect
}

private fun paddedBbox(rect: Rect, width: Int, height: Int, ratio: Double): Rect {
    val padding = max(2, (max(rect.width, rect.height) * ratio).toInt())
    val left = max(0, rect.x - padding)
    val top = max(0, rect.y - padding)
    val right = min(width, rect.x + rect.width + padding)
    val bottom = min(height, rect.y + rect.height + padding)
    return Rect(left, top, right - left, bottom - top)
}

private fun normalizeCorrelation(value: Double): Double {
    if (!value.isFinite()) return 0.0
    return ((value.coerceIn(-1.0, 1.0) + 1.0) / 2.0).coerceIn(0.0, 1.0)
}

private fun rounded(value: Double): Double = kotlin.math.round(value * 1_000_000.0) / 1_000_000.0
private fun roundedMillis(value: Double): Double = kotlin.math.round(value * 1_000.0) / 1_000.0
private fun nanosToMillis(value: Long): Double = value / 1_000_000.0
