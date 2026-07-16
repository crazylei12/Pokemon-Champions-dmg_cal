package com.crazylei12.pokemonchampionsassistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private const val SLOT_COUNT = 6
private val STAT_IDS = listOf("hp", "atk", "def", "spa", "spd", "spe")
private val STAT_CELLS = mapOf(
    "hp" to doubleArrayOf(0.24, 0.39, 0.21, 0.41),
    "atk" to doubleArrayOf(0.24, 0.39, 0.43, 0.64),
    "def" to doubleArrayOf(0.24, 0.39, 0.66, 0.90),
    "spa" to doubleArrayOf(0.71, 0.86, 0.21, 0.41),
    "spd" to doubleArrayOf(0.71, 0.86, 0.43, 0.64),
    "spe" to doubleArrayOf(0.71, 0.86, 0.66, 0.90),
)

internal fun selectStatValueCandidates(candidates: List<Int?>): Int? {
    val values = candidates.mapIndexedNotNull { index, value -> value?.let { index to it } }
    if (values.isEmpty()) return null
    return values.groupBy { it.second }.entries
        .sortedWith(
            compareByDescending<Map.Entry<Int, List<Pair<Int, Int>>>> { it.value.size }
                .thenByDescending { entry -> entry.key > 32 }
                .thenBy { entry -> entry.value.minOf { it.first } },
        )
        .first()
        .key
}

internal fun correctSixNineDigitConfusions(value: Int, holeYs: List<Double?>): Int {
    val corrected = value.toString().mapIndexed { index, digit ->
        val holeY = holeYs.getOrNull(index)
        when {
            digit == '6' && holeY != null && holeY < 0.45 -> '9'
            digit == '9' && holeY != null && holeY > 0.55 -> '6'
            else -> digit
        }
    }.joinToString("")
    return corrected.toIntOrNull() ?: value
}

internal fun correctTwoThreeMiddleDigitConfusion(value: Int, lowerLeftRatio: Double?): Int {
    if (value !in 100..999 || lowerLeftRatio == null) return value
    val middle = value / 10 % 10
    return when {
        middle == 2 && lowerLeftRatio <= 0.38 -> value + 10
        middle == 3 && lowerLeftRatio >= 0.43 -> value - 10
        else -> value
    }
}

internal fun normalizeStatValueDigitCount(value: Int, detectedDigitCount: Int): Int {
    val digits = value.toString()
    if (detectedDigitCount !in 1..3 || digits.length <= detectedDigitCount) return value
    return digits.take(detectedDigitCount).toIntOrNull() ?: value
}

internal fun statCropHorizontalRanges(region: DoubleArray): List<Pair<Double, Double>> =
    if (region[0] < 0.5) {
        listOf(0.24 to 0.39, 0.23 to 0.415)
    } else {
        listOf(0.71 to 0.86, 0.70 to 0.885)
    }

internal fun normalizeTeamCardRows(cards: List<Rect>): List<Rect> = cards
    .sortedBy { it.centerY() }
    .chunked(2)
    .flatMap { unsortedRow ->
        val row = unsortedRow.sortedBy { it.left }
        if (row.size != 2) return@flatMap row
        val panelTop = row.maxOf { it.top }
        val panelBottom = row.minOf { it.bottom }
        if (panelBottom <= panelTop) row else row.map { Rect(it.left, panelTop, it.right, panelBottom) }
    }

internal fun selectUnambiguousRecognitionEntity(
    candidates: List<RecognitionEntity>,
    ambiguityMargin: Double = 0.01,
): RecognitionEntity? {
    val ranked = candidates.groupBy(RecognitionEntity::canonicalId).values
        .map { sameEntity ->
            sameEntity.maxWith(
                compareBy<RecognitionEntity> { it.confidence }
                    .thenBy { normalizeLookup(it.originalText).length },
            )
        }
        .sortedWith(
            compareByDescending<RecognitionEntity> { it.confidence }
                .thenByDescending { normalizeLookup(it.originalText).length },
        )
    val best = ranked.firstOrNull() ?: return null
    val runnerUp = ranked.getOrNull(1)
    return if (runnerUp != null && best.confidence - runnerUp.confidence <= ambiguityMargin) null else best
}

internal fun entityCropRegions(field: String): List<DoubleArray> = when (field) {
    "species" -> listOf(doubleArrayOf(0.04, 0.58, 0.0, 0.30))
    "ability" -> listOf(doubleArrayOf(0.04, 0.58, 0.20, 0.55))
    "item" -> listOf(doubleArrayOf(0.04, 0.58, 0.45, 0.82))
    else -> {
        val row = field.removePrefix("move").toInt()
        val tops = doubleArrayOf(0.0, 0.25, 0.48, 0.71)
        val bottoms = doubleArrayOf(0.31, 0.55, 0.79, 1.0)
        listOf(
            doubleArrayOf(0.50, 0.99, tops[row], bottoms[row]),
            doubleArrayOf(0.66, 0.99, tops[row], bottoms[row]),
        )
    }
}

enum class OwnTeamPageType { MOVE_ITEM, STATS }

internal fun classifyOwnTeamPage(statEvidence: Int, moveItemEvidence: Int): OwnTeamPageType = when {
    moveItemEvidence >= SLOT_COUNT -> OwnTeamPageType.MOVE_ITEM
    statEvidence >= SLOT_COUNT -> OwnTeamPageType.STATS
    else -> OwnTeamPageType.MOVE_ITEM
}

data class RecognitionEntity(
    val entityType: String,
    val canonicalId: String,
    val showdownId: String,
    val displayName: String,
    val originalText: String,
    val confidence: Double,
) {
    fun toJson() = JSONObject().apply {
        put("entityType", entityType)
        put("canonicalId", canonicalId)
        put("showdownId", showdownId)
        put("displayName", displayName)
        put("originalText", originalText)
        put("confidence", confidence)
        put("source", "ocr_android_mlkit")
    }

    companion object {
        fun fromJson(json: JSONObject) = RecognitionEntity(
            entityType = json.getString("entityType"),
            canonicalId = json.getString("canonicalId"),
            showdownId = json.getString("showdownId"),
            displayName = json.optString("displayName", json.getString("showdownId")),
            originalText = json.optString("originalText"),
            confidence = json.optDouble("confidence", 0.0),
        )
    }
}

data class RecognizedSlot(
    val slotIndex: Int,
    val species: RecognitionEntity?,
    val ability: RecognitionEntity? = null,
    val item: RecognitionEntity? = null,
    val moves: List<RecognitionEntity> = emptyList(),
    val moveSlotIndexes: List<Int> = moves.indices.toList(),
    val actualStats: Map<String, Int> = emptyMap(),
) {
    fun toJson() = JSONObject().apply {
        put("slotIndex", slotIndex)
        species?.let { put("species", it.toJson()) }
        ability?.let { put("ability", it.toJson()) }
        item?.let { put("item", it.toJson()) }
        put("moves", JSONArray().apply { moves.forEach { put(it.toJson()) } })
        put("moveSlotIndexes", JSONArray().apply { moveSlotIndexes.forEach(::put) })
        put("actualStats", JSONObject().apply { actualStats.forEach(::put) })
    }

    companion object {
        fun fromJson(json: JSONObject): RecognizedSlot {
            val moves = json.optJSONArray("moves") ?: JSONArray()
            val moveSlotIndexes = json.optJSONArray("moveSlotIndexes")
            val stats = json.optJSONObject("actualStats") ?: JSONObject()
            return RecognizedSlot(
                slotIndex = json.getInt("slotIndex"),
                species = json.optJSONObject("species")?.let(RecognitionEntity::fromJson),
                ability = json.optJSONObject("ability")?.let(RecognitionEntity::fromJson),
                item = json.optJSONObject("item")?.let(RecognitionEntity::fromJson),
                moves = (0 until moves.length()).mapNotNull { index ->
                    moves.optJSONObject(index)?.let(RecognitionEntity::fromJson)
                },
                moveSlotIndexes = if (moveSlotIndexes == null) {
                    (0 until moves.length()).toList()
                } else {
                    (0 until moveSlotIndexes.length()).map(moveSlotIndexes::getInt)
                },
                actualStats = stats.keys().asSequence().associateWith(stats::getInt),
            )
        }
    }
}

data class RecognizedOwnTeamPage(
    val type: OwnTeamPageType,
    val width: Int,
    val height: Int,
    val slots: List<RecognizedSlot>,
    val recognized: Int,
    val total: Int,
    val capturedAt: String = Instant.now().toString(),
) {
    fun toJson() = JSONObject().apply {
        put("sceneType", if (type == OwnTeamPageType.MOVE_ITEM) "OWN_TEAM_MOVE_ITEM" else "OWN_TEAM_STATS")
        put("image", JSONObject().put("width", width).put("height", height).put("capturedAt", capturedAt))
        put("slots", JSONArray().apply { slots.forEach { put(it.toJson()) } })
        put("recognition", JSONObject().put("recognized", recognized).put("total", total)
            .put("rate", if (total == 0) 0.0 else recognized.toDouble() / total))
    }

    companion object {
        fun fromJson(json: JSONObject): RecognizedOwnTeamPage {
            val sceneType = json.getString("sceneType")
            val image = json.getJSONObject("image")
            val slots = json.getJSONArray("slots")
            val recognition = json.getJSONObject("recognition")
            return RecognizedOwnTeamPage(
                type = if (sceneType == "OWN_TEAM_STATS") OwnTeamPageType.STATS else OwnTeamPageType.MOVE_ITEM,
                width = image.getInt("width"),
                height = image.getInt("height"),
                slots = (0 until slots.length()).map { RecognizedSlot.fromJson(slots.getJSONObject(it)) },
                recognized = recognition.getInt("recognized"),
                total = recognition.getInt("total"),
                capturedAt = image.optString("capturedAt", Instant.now().toString()),
            )
        }
    }
}

data class ImportSaveResult(
    val message: String,
    val savedFileName: String? = null,
    val savedJson: JSONObject? = null,
    val nextStep: OwnTeamImportNextStep? = null,
)

class OwnTeamOcrEngine(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val tasks = CloseSafeSerialExecutor()
    private val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    private val statRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val catalog = EntityCatalog(appContext)

    fun recognize(bitmap: Bitmap, callback: (Result<RecognizedOwnTeamPage>) -> Unit) {
        if (!tasks.submit {
            val result = runCatching { recognizeBlocking(bitmap) }
            callback(result)
        }) callback(Result.failure(CancellationException("我方队伍识别器已关闭")))
    }

    private fun recognizeBlocking(bitmap: Bitmap): RecognizedOwnTeamPage {
        val cards = TeamCardDetector.detect(bitmap)
        val cardResults = cards.map { card ->
            val crop = Bitmap.createBitmap(bitmap, card.left, card.top, card.width(), card.height())
            try {
                val text = Tasks.await(
                    recognizer.process(InputImage.fromBitmap(crop, 0)),
                    30,
                    TimeUnit.SECONDS,
                )
                val lines = text.textBlocks.flatMap { block -> block.lines }.mapNotNull { line ->
                    val rect = line.boundingBox ?: return@mapNotNull null
                    OcrLine(
                        text = normalizeText(line.text),
                        rect = rect,
                        tokens = line.elements.mapNotNull { element ->
                            element.boundingBox?.let { Token(normalizeText(element.text), it) }
                        }.filter { it.text.isNotBlank() },
                    )
                }.filter { it.text.isNotBlank() }
                OcrCard(card.width(), card.height(), lines)
            } finally {
                crop.recycle()
            }
        }

        val statEvidence = cardResults.sumOf { card ->
            STAT_IDS.count { stat -> pickNumber(card, STAT_CELLS.getValue(stat), stat) != null }
        }
        val moveItemEvidence = cardResults.sumOf { card ->
            listOf(
                "ability" to "ability",
                "item" to "item",
                "move0" to "move",
                "move1" to "move",
                "move2" to "move",
                "move3" to "move",
            ).count { (field, entityType) -> pickField(card, field, entityType) != null }
        }
        val type = classifyOwnTeamPage(statEvidence, moveItemEvidence)
        val slots = if (type == OwnTeamPageType.STATS) {
            cardResults.mapIndexed { index, card -> parseStatsSlot(index, card, bitmap, cards[index]) }
        } else {
            cardResults.mapIndexed { index, card -> parseMoveItemSlot(index, card, bitmap, cards[index]) }
        }
        val recognized = if (type == OwnTeamPageType.STATS) {
            slots.sumOf { (if (it.species != null) 1 else 0) + it.actualStats.size }
        } else {
            slots.sumOf {
                (if (it.species != null) 1 else 0) + (if (it.ability != null) 1 else 0) +
                    (if (it.item != null) 1 else 0) + it.moves.size
            }
        }
        return RecognizedOwnTeamPage(type, bitmap.width, bitmap.height, slots, recognized, SLOT_COUNT * 7)
    }

    private fun parseMoveItemSlot(index: Int, card: OcrCard, source: Bitmap, cardRect: Rect): RecognizedSlot {
        fun field(name: String, type: String): RecognitionEntity? {
            val cardCandidate = pickField(card, name, type)
            if (cardCandidate?.confidence == 1.0) return cardCandidate
            val cropCandidate = recognizeEntityCrop(source, cardRect, name, type)
            return selectUnambiguousRecognitionEntity(listOfNotNull(cardCandidate, cropCandidate))
        }
        val indexedMoves = (0..3).map { moveIndex -> moveIndex to field("move$moveIndex", "move") }
        return RecognizedSlot(
            slotIndex = index,
            species = field("species", "species"),
            ability = field("ability", "ability"),
            item = field("item", "item"),
            moves = indexedMoves.mapNotNull { it.second },
            moveSlotIndexes = indexedMoves.mapNotNull { (moveIndex, move) -> move?.let { moveIndex } },
        )
    }

    private fun recognizeEntityCrop(
        source: Bitmap,
        card: Rect,
        field: String,
        entityType: String,
    ): RecognitionEntity? {
        fun recognizeRegion(region: DoubleArray): RecognitionEntity? {
            val leftRatio = (region[0] - 0.01).coerceAtLeast(0.0)
            val rightRatio = (region[1] + 0.01).coerceAtMost(1.0)
            val left = (card.left + card.width() * leftRatio).toInt().coerceIn(0, source.width - 1)
            val right = (card.left + card.width() * rightRatio).toInt().coerceIn(left + 1, source.width)
            val top = (card.top + card.height() * region[2]).toInt().coerceIn(0, source.height - 1)
            val bottom = (card.top + card.height() * region[3]).toInt().coerceIn(top + 1, source.height)
            val crop = Bitmap.createBitmap(source, left, top, right - left, bottom - top)
            val scaled = Bitmap.createScaledBitmap(crop, crop.width * 3, crop.height * 3, true)
            if (scaled !== crop) crop.recycle()
            return try {
                fun recognizeCandidates(bitmap: Bitmap): List<RecognitionEntity> {
                    val text = Tasks.await(
                        recognizer.process(InputImage.fromBitmap(bitmap, 0)),
                        20,
                        TimeUnit.SECONDS,
                    )
                    return (text.textBlocks.flatMap { block -> block.lines }.map { it.text } + text.text)
                        .mapNotNull { catalog.resolve(normalizeText(it), entityType) }
                }
                val originalCandidates = recognizeCandidates(scaled)
                val thresholdCandidates = thresholdForDigits(scaled, 180).let { thresholded ->
                    try { recognizeCandidates(thresholded) } finally { thresholded.recycle() }
                }
                selectUnambiguousRecognitionEntity(originalCandidates + thresholdCandidates)
            } finally {
                scaled.recycle()
            }
        }

        val regions = entityCropRegions(field)
        val primary = recognizeRegion(regions.first())
        if (regions.size == 1 || (primary != null && primary.confidence >= 0.90)) return primary
        return selectUnambiguousRecognitionEntity(
            listOfNotNull(primary, recognizeRegion(regions[1])),
        )
    }

    private fun recognizeSpecies(
        card: OcrCard,
        source: Bitmap,
        cardRect: Rect,
    ): RecognitionEntity? {
        val cardCandidate = card.lines.mapNotNull { line ->
            val pos = relativeCenter(line.rect, card)
            if (pos.first >= 0.58 || pos.second >= 0.28) null
            else catalog.resolve(line.text, "species")
        }.let(::selectUnambiguousRecognitionEntity)
        if (cardCandidate?.confidence == 1.0) return cardCandidate
        return selectUnambiguousRecognitionEntity(
            listOfNotNull(cardCandidate, recognizeEntityCrop(source, cardRect, "species", "species")),
        )
    }

    private fun recognizeStatValue(
        source: Bitmap,
        card: Rect,
        region: DoubleArray,
        stat: String,
        wholeCardValue: Int?,
    ): Int? {
        val cropValue = recognizeStatCrop(source, card, region, stat)
        val correctedWholeCardValue = wholeCardValue?.let { value ->
            correctStatValueFromSource(value, source, card, region, stat)
        }
        return selectStatValueCandidates(listOf(cropValue, correctedWholeCardValue))
    }

    private fun parseStatsSlot(index: Int, card: OcrCard, source: Bitmap, cardRect: Rect): RecognizedSlot {
        val species = recognizeSpecies(card, source, cardRect)
        val stats = STAT_IDS.mapNotNull { stat ->
            val region = STAT_CELLS.getValue(stat)
            recognizeStatValue(source, cardRect, region, stat, pickNumber(card, region, stat))
                ?.let { stat to it }
        }.toMap()
        return RecognizedSlot(index, species = species, actualStats = stats)
    }

    private fun recognizeStatCrop(source: Bitmap, card: Rect, region: DoubleArray, stat: String): Int? {
        val topRatio = (region[2] - 0.025).coerceAtLeast(0.0)
        val bottomRatio = (region[3] + 0.025).coerceAtMost(1.0)
        val top = (card.top + card.height() * topRatio).toInt().coerceIn(0, source.height - 1)
        val bottom = (card.top + card.height() * bottomRatio).toInt().coerceIn(top + 1, source.height)
        val values = mutableListOf<Int?>()
        statCropHorizontalRanges(region).forEach { (leftRatio, rightRatio) ->
            val left = (card.left + card.width() * leftRatio).toInt().coerceIn(0, source.width - 1)
            val right = (card.left + card.width() * rightRatio).toInt().coerceIn(left + 1, source.width)
            val crop = Bitmap.createBitmap(source, left, top, right - left, bottom - top)
            val smooth = Bitmap.createScaledBitmap(crop, crop.width * 6, crop.height * 6, true)
            val thresholded = thresholdForDigits(crop, 180).let { binary ->
                try {
                    Bitmap.createScaledBitmap(binary, crop.width * 6, crop.height * 6, false)
                } finally {
                    binary.recycle()
                }
            }
            try {
                val smoothValue = readStatValue(smooth, stat)
                    ?.let { correctCommonDigitConfusions(it, crop) }
                    ?.takeIf { isPlausibleStatValue(it, stat) }
                val thresholdValue = readStatValue(thresholded, stat)
                    ?.let { correctCommonDigitConfusions(it, crop) }
                    ?.takeIf { isPlausibleStatValue(it, stat) }
                values += smoothValue
                values += thresholdValue
            } finally {
                crop.recycle()
                smooth.recycle()
                thresholded.recycle()
            }
        }
        return selectStatValueCandidates(values)
    }

    private fun correctStatValueFromSource(
        value: Int,
        source: Bitmap,
        card: Rect,
        region: DoubleArray,
        stat: String,
    ): Int? {
        val topRatio = (region[2] - 0.025).coerceAtLeast(0.0)
        val bottomRatio = (region[3] + 0.025).coerceAtMost(1.0)
        val top = (card.top + card.height() * topRatio).toInt().coerceIn(0, source.height - 1)
        val bottom = (card.top + card.height() * bottomRatio).toInt().coerceIn(top + 1, source.height)
        val candidates = statCropHorizontalRanges(region).map { (leftRatio, rightRatio) ->
            val left = (card.left + card.width() * leftRatio).toInt().coerceIn(0, source.width - 1)
            val right = (card.left + card.width() * rightRatio).toInt().coerceIn(left + 1, source.width)
            val crop = Bitmap.createBitmap(source, left, top, right - left, bottom - top)
            try {
                correctCommonDigitConfusions(value, crop).takeIf { isPlausibleStatValue(it, stat) }
            } finally {
                crop.recycle()
            }
        }
        return selectStatValueCandidates(candidates)
    }

    private fun isPlausibleStatValue(value: Int, stat: String): Boolean =
        value <= 500 && if (stat == "hp") value >= 1 else value >= 10

    private fun readStatValue(bitmap: Bitmap, stat: String): Int? {
        val text = Tasks.await(
            statRecognizer.process(InputImage.fromBitmap(bitmap, 0)),
            20,
            TimeUnit.SECONDS,
        ).text.replace(Regex("[Il|!]"), "1").replace(Regex("[Oo]"), "0")
        return Regex("\\d{1,3}").findAll(text).mapNotNull { it.value.toIntOrNull() }
            .filter { value -> if (stat == "hp") value >= 1 else value >= 10 }
            .sortedWith(compareByDescending<Int> { it > 32 }.thenByDescending { it })
            .firstOrNull()
    }

    private fun correctCommonDigitConfusions(value: Int, crop: Bitmap): Int {
        var corrected = value
        if (corrected in 10..999) {
            val holeYs = statDigitHoleYs(crop, corrected.toString().length)
            corrected = normalizeStatValueDigitCount(corrected, holeYs.size)
            corrected = correctSixNineDigitConfusions(corrected, holeYs)
        }
        val middle = corrected / 10 % 10
        val lowerLeftRatio = if (corrected in 100..999 && (middle == 2 || middle == 3)) {
            middleDigitLowerLeftRatio(crop)
        } else {
            null
        }
        return correctTwoThreeMiddleDigitConfusion(corrected, lowerLeftRatio)
    }

    private fun statDigitHoleYs(crop: Bitmap, digitCount: Int): List<Double?> {
        val width = crop.width
        val height = crop.height
        val pixels = IntArray(width * height)
        crop.getPixels(pixels, 0, width, 0, 0, width, height)
        val bright = BooleanArray(pixels.size) { index ->
            val color = pixels[index]
            (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000 >= 180
        }
        val visited = BooleanArray(bright.size)
        val queue = IntArray(bright.size)
        val components = mutableListOf<Rect>()
        for (start in bright.indices) {
            if (!bright[start] || visited[start]) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true
            var left = width
            var top = height
            var right = 0
            var bottom = 0
            var area = 0
            while (head < tail) {
                val current = queue[head++]
                val x = current % width
                val y = current / width
                left = min(left, x); top = min(top, y); right = max(right, x); bottom = max(bottom, y); area++
                fun push(next: Int, valid: Boolean) {
                    if (valid && bright[next] && !visited[next]) {
                        visited[next] = true
                        queue[tail++] = next
                    }
                }
                push(current - 1, x > 0)
                push(current + 1, x < width - 1)
                push(current - width, y > 0)
                push(current + width, y < height - 1)
            }
            val rect = Rect(left, top, right + 1, bottom + 1)
            if (area >= 30 && rect.top > height * 0.35 && rect.height() in (height * 0.25).toInt()..(height * 0.65).toInt()) {
                components += rect
            }
        }

        return components.sortedBy { it.left }.take(digitCount).map { digit ->
            digitHoleY(bright, width, digit)
        }
    }

    private fun digitHoleY(bright: BooleanArray, sourceWidth: Int, digit: Rect): Double? {
        val localWidth = digit.width()
        val localHeight = digit.height()
        val exterior = BooleanArray(localWidth * localHeight)
        val queue = IntArray(localWidth * localHeight)
        var head = 0
        var tail = 0
        fun enqueue(x: Int, y: Int) {
            val local = y * localWidth + x
            val source = (digit.top + y) * sourceWidth + digit.left + x
            if (!bright[source] && !exterior[local]) {
                exterior[local] = true
                queue[tail++] = local
            }
        }
        for (x in 0 until localWidth) { enqueue(x, 0); enqueue(x, localHeight - 1) }
        for (y in 0 until localHeight) { enqueue(0, y); enqueue(localWidth - 1, y) }
        while (head < tail) {
            val current = queue[head++]
            val x = current % localWidth
            val y = current / localWidth
            fun push(nx: Int, ny: Int) {
                if (nx !in 0 until localWidth || ny !in 0 until localHeight) return
                val local = ny * localWidth + nx
                val source = (digit.top + ny) * sourceWidth + digit.left + nx
                if (!bright[source] && !exterior[local]) {
                    exterior[local] = true
                    queue[tail++] = local
                }
            }
            push(x - 1, y); push(x + 1, y); push(x, y - 1); push(x, y + 1)
        }
        var holePixels = 0
        var holeYTotal = 0L
        for (y in 0 until localHeight) for (x in 0 until localWidth) {
            val local = y * localWidth + x
            val source = (digit.top + y) * sourceWidth + digit.left + x
            if (!bright[source] && !exterior[local]) {
                holePixels++
                holeYTotal += y
            }
        }
        if (holePixels < 4) return null
        return holeYTotal.toDouble() / holePixels / localHeight
    }

    private fun middleDigitLowerLeftRatio(crop: Bitmap): Double? {
        val width = crop.width
        val height = crop.height
        val pixels = IntArray(width * height)
        crop.getPixels(pixels, 0, width, 0, 0, width, height)
        val bright = BooleanArray(pixels.size) { index ->
            val color = pixels[index]
            (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000 >= 180
        }
        val visited = BooleanArray(bright.size)
        val queue = IntArray(bright.size)
        val components = mutableListOf<Rect>()
        for (start in bright.indices) {
            if (!bright[start] || visited[start]) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true
            var left = width
            var top = height
            var right = 0
            var bottom = 0
            var area = 0
            while (head < tail) {
                val current = queue[head++]
                val x = current % width
                val y = current / width
                left = min(left, x); top = min(top, y); right = max(right, x); bottom = max(bottom, y); area++
                fun push(next: Int, valid: Boolean) {
                    if (valid && bright[next] && !visited[next]) {
                        visited[next] = true
                        queue[tail++] = next
                    }
                }
                push(current - 1, x > 0)
                push(current + 1, x < width - 1)
                push(current - width, y > 0)
                push(current + width, y < height - 1)
            }
            val rect = Rect(left, top, right + 1, bottom + 1)
            if (area >= 30 && rect.top > height * 0.35 && rect.height() in (height * 0.25).toInt()..(height * 0.65).toInt()) {
                components += rect
            }
        }
        val digits = components.sortedBy { it.left }
        if (digits.size < 3) return null
        val digit = digits[1]
        val middleX = digit.left + digit.width() / 2
        val lowerY = digit.top + digit.height() / 2
        var leftCount = 0
        var rightCount = 0
        for (y in lowerY until digit.bottom) for (x in digit.left until digit.right) {
            if (!bright[y * width + x]) continue
            if (x < middleX) leftCount++ else rightCount++
        }
        val total = leftCount + rightCount
        return if (total == 0) null else leftCount.toDouble() / total
    }

    private fun thresholdForDigits(source: Bitmap, threshold: Int): Bitmap {
        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        for (index in pixels.indices) {
            val color = pixels[index]
            val gray = (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000
            pixels[index] = if (gray >= threshold) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, source.width, source.height, Bitmap.Config.ARGB_8888)
    }

    private fun pickField(card: OcrCard, field: String, entityType: String): RecognitionEntity? {
        return card.lines.mapNotNull { line ->
            val pos = relativeCenter(line.rect, card)
            if (!isFieldPosition(field, pos.first, pos.second)) return@mapNotNull null
            catalog.resolve(line.text, entityType)
        }.maxByOrNull { it.confidence }
    }

    override fun close() {
        tasks.closeAfterPending {
            recognizer.close()
            statRecognizer.close()
        }
    }
}

private data class OcrLine(val text: String, val rect: Rect, val tokens: List<Token>)
private data class Token(val text: String, val rect: Rect)
private data class OcrCard(val width: Int, val height: Int, val lines: List<OcrLine>)

private fun relativeCenter(rect: Rect, card: OcrCard): Pair<Double, Double> {
    return ((rect.exactCenterX() / card.width.coerceAtLeast(1)).toDouble() to
        (rect.exactCenterY() / card.height.coerceAtLeast(1)).toDouble())
}

private fun isFieldPosition(field: String, rx: Double, ry: Double): Boolean = when {
    field == "species" -> rx < 0.58 && ry < 0.29
    field == "ability" -> rx < 0.58 && ry >= 0.22 && ry < 0.53
    field == "item" -> rx < 0.58 && ry >= 0.48 && ry < 0.80
    field.startsWith("move") && rx >= 0.52 -> {
        val row = when {
            ry < 0.29 -> 0
            ry < 0.52 -> 1
            ry < 0.75 -> 2
            else -> 3
        }
        row == field.removePrefix("move").toInt()
    }
    else -> false
}

private fun pickNumber(card: OcrCard, region: DoubleArray, stat: String): Int? {
    val allTokens = card.lines.flatMap { line -> if (line.tokens.isEmpty()) listOf(Token(line.text, line.rect)) else line.tokens }
    val width = card.width.coerceAtLeast(1)
    val height = card.height.coerceAtLeast(1)
    val candidates = mutableListOf<Triple<Int, Double, Double>>()
    for (token in allTokens) {
        val matches = Regex("[+-]?\\d+").findAll(token.text).toList()
        for (match in matches) {
            val value = abs(match.value.toIntOrNull() ?: continue)
            if (value > 999 || (stat == "hp" && value < 1) || (stat != "hp" && value < 10)) continue
            val ratio = if (matches.size == 1 || token.text.isEmpty()) 0.5 else
                (match.range.first + match.range.last + 1.0) / (2.0 * token.text.length)
            val cx = (token.rect.left + token.rect.width() * ratio) / width
            val cy = token.rect.exactCenterY() / height
            if (cx !in region[0]..region[1] || cy !in region[2]..region[3]) continue
            val distance = kotlin.math.hypot(cx - (region[0] + region[1]) / 2, cy - (region[2] + region[3]) / 2)
            candidates += Triple(value, if (value > 32) 1.0 else 0.0, distance)
        }
    }
    return candidates.sortedWith(compareByDescending<Triple<Int, Double, Double>> { it.second }
        .thenBy { it.third }.thenByDescending { it.first }).firstOrNull()?.first
}

private class EntityCatalog(context: Context) {
    private data class Alias(val normalized: String, val exactLocalized: Boolean, val entity: RecognitionEntity)
    private val byType: Map<String, List<Alias>>

    init {
        val array = JSONArray(context.assets.open("recognition/zh-Hans.json").bufferedReader().use { it.readText() })
        val mutable = mutableMapOf<String, MutableList<Alias>>()
        for (index in 0 until array.length()) {
            val entry = array.getJSONObject(index)
            val type = entry.getString("entityType")
            val localized = entry.optJSONObject("localizedNames")?.optJSONArray("zh-Hans") ?: JSONArray()
            val localizedNames = (0 until localized.length()).map(localized::getString)
            val aliases = entry.optJSONArray("aliases") ?: JSONArray()
            val names = buildList {
                addAll(localizedNames)
                for (i in 0 until aliases.length()) add(aliases.getString(i))
                entry.optString("englishName").takeIf(String::isNotBlank)?.let(::add)
                entry.optString("showdownId").takeIf(String::isNotBlank)?.let(::add)
            }.distinct()
            val display = localizedNames.firstOrNull() ?: entry.optString("englishName", entry.getString("showdownId"))
            for (name in names) {
                mutable.getOrPut(type, ::mutableListOf).add(
                    Alias(
                        normalizeLookup(name),
                        name in localizedNames,
                        RecognitionEntity(type, entry.getString("canonicalId"), entry.getString("showdownId"), display, name, 1.0),
                    )
                )
            }
        }
        byType = mutable
    }

    fun resolve(rawText: String, type: String): RecognitionEntity? {
        val input = normalizeLookup(rawText)
        if (input.isBlank()) return null
        val candidates = byType[type].orEmpty().mapNotNull { alias ->
            val inputs = buildList {
                add(input)
                input.filter { it.code > 127 }.takeIf { it.length >= 2 }?.let(::add)
            }.distinct()
            val confidence = inputs.maxOf { candidate -> when {
                candidate == alias.normalized -> if (alias.exactLocalized) 1.0 else 0.96
                candidate.length >= 2 && alias.normalized.contains(candidate) ->
                    0.78 + 0.08 * candidate.length / alias.normalized.length
                candidate.length >= 2 && candidate.contains(alias.normalized) ->
                    0.82 + 0.08 * alias.normalized.length / candidate.length
                candidate.length >= 2 && levenshteinWithinOne(candidate, alias.normalized) -> 0.74
                else -> 0.0
            } }
            if (confidence == 0.0) null else alias.entity.copy(originalText = rawText, confidence = confidence)
        }
        return candidates.firstOrNull { it.confidence == 1.0 }
            ?: selectUnambiguousRecognitionEntity(candidates)
    }
}

private object TeamCardDetector {
    fun detect(bitmap: Bitmap): List<Rect> {
        val scale = min(1.0, 900.0 / bitmap.width)
        val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val small = if (width == bitmap.width) bitmap else Bitmap.createScaledBitmap(bitmap, width, height, true)
        try {
            val pixels = IntArray(width * height)
            small.getPixels(pixels, 0, width, 0, 0, width, height)
            val mask = BooleanArray(pixels.size)
            pixels.forEachIndexed { index, color ->
                val r = Color.red(color) / 255.0
                val g = Color.green(color) / 255.0
                val b = Color.blue(color) / 255.0
                val maxValue = max(r, max(g, b))
                val minValue = min(r, min(g, b))
                val delta = maxValue - minValue
                var hue = when {
                    delta == 0.0 -> 0.0
                    maxValue == r -> 60 * (((g - b) / delta) % 6)
                    maxValue == g -> 60 * ((b - r) / delta + 2)
                    else -> 60 * ((r - g) / delta + 4)
                }
                if (hue < 0) hue += 360
                val saturation = if (maxValue == 0.0) 0.0 else delta / maxValue
                mask[index] = hue in 220.0..280.0 && saturation >= 0.18 && maxValue >= 0.20
            }
            val visited = BooleanArray(mask.size)
            val queue = IntArray(mask.size)
            val components = mutableListOf<Component>()
            for (start in mask.indices) {
                if (!mask[start] || visited[start]) continue
                var head = 0
                var tail = 0
                queue[tail++] = start
                visited[start] = true
                var area = 0
                var left = width
                var top = height
                var right = 0
                var bottom = 0
                while (head < tail) {
                    val current = queue[head++]
                    val x = current % width
                    val y = current / width
                    area++
                    left = min(left, x); top = min(top, y); right = max(right, x); bottom = max(bottom, y)
                    fun push(next: Int, valid: Boolean) {
                        if (valid && mask[next] && !visited[next]) {
                            visited[next] = true
                            queue[tail++] = next
                        }
                    }
                    push(current - 1, x > 0)
                    push(current + 1, x < width - 1)
                    push(current - width, y > 0)
                    push(current + width, y < height - 1)
                }
                components += Component(left, top, right - left + 1, bottom - top + 1, area)
            }
            val sourceArea = bitmap.width.toLong() * bitmap.height
            val candidates = components.map { component ->
                Rect(
                    (component.left / scale).toInt(),
                    (component.top / scale).toInt(),
                    ((component.left + component.width) / scale).toInt().coerceAtMost(bitmap.width),
                    ((component.top + component.height) / scale).toInt().coerceAtMost(bitmap.height),
                ) to (component.area / (scale * scale))
            }.filter { (rect, area) ->
                area > sourceArea * 0.003 && rect.width() > bitmap.width * 0.15 && rect.width() < bitmap.width * 0.48 &&
                    rect.height() > bitmap.height * 0.05 && rect.height() < bitmap.height * 0.25
            }.sortedByDescending { it.second }.take(SLOT_COUNT).map { it.first }
            if (candidates.size != SLOT_COUNT) error("应检测到 6 个队伍卡片，实际检测到 ${candidates.size} 个")
            return normalizeTeamCardRows(candidates)
        } finally {
            if (small !== bitmap) small.recycle()
        }
    }

    private data class Component(val left: Int, val top: Int, val width: Int, val height: Int, val area: Int)
}

class OwnTeamImportRepository(private val context: Context) {
    companion object {
        private const val PENDING_FILE = "pending-own-team.json"
        private const val DRAFT_FILE = "own-team-import-draft.json"
    }

    private var moveItemPage: RecognizedOwnTeamPage? = null
    private var statsPage: RecognizedOwnTeamPage? = null

    init {
        restoreDraft()
    }

    fun accept(page: RecognizedOwnTeamPage): ImportSaveResult {
        syncDraftState()
        val updated = updateOwnTeamDraft(moveItemPage, statsPage, page)
        moveItemPage = updated.moveItemPage
        statsPage = updated.statsPage
        persistDraft()
        val move = moveItemPage
        val stats = statsPage
        when (nextOwnTeamImportStep(move, stats)) {
            OwnTeamImportNextStep.CAPTURE_MOVE_ITEM -> return ImportSaveResult(
                "能力值页 ${page.recognized}/${page.total} 已保留；请继续识别招式/道具页",
                nextStep = OwnTeamImportNextStep.CAPTURE_MOVE_ITEM,
            )
            OwnTeamImportNextStep.CAPTURE_STATS -> return ImportSaveResult(
                if (updated.restarted) {
                    "检测到新的招式/道具页，已清空上一轮未完成缓存；本页 ${page.recognized}/${page.total} 已保留，请继续识别能力值页"
                } else {
                    "招式/道具页 ${page.recognized}/${page.total} 已保留；请继续识别能力值页"
                },
                nextStep = OwnTeamImportNextStep.CAPTURE_STATS,
            )
            OwnTeamImportNextStep.MANUAL_CORRECTION -> return ImportSaveResult(
                "双图结果已保留（招式 ${move!!.recognized}/${move.total}，能力值 ${stats!!.recognized}/${stats.total}）；请确认识别结果，必要时手动修正后保存",
                nextStep = OwnTeamImportNextStep.MANUAL_CORRECTION,
            )
            OwnTeamImportNextStep.NAME_TEAM -> Unit
        }
        val saved = createSavedTeam(requireNotNull(move), requireNotNull(stats))
        val partialMoveSlots = move.slots.count { it.moves.size in 1..3 }
        context.filesDir.resolve(PENDING_FILE).writeUtf8Atomically(saved.toString(2))
        moveItemPage = null
        statsPage = null
        context.filesDir.resolve(DRAFT_FILE).delete()
        return ImportSaveResult(
            buildString {
                append("双图识别完成；请为这支队伍命名后保存")
                if (partialMoveSlots > 0) append("；$partialMoveSlots 只宝可梦未带满 4 个招式，空技能槽允许保留")
            },
            savedJson = saved,
            nextStep = OwnTeamImportNextStep.NAME_TEAM,
        )
    }

    fun hasPendingTeam(): Boolean = context.filesDir.resolve(PENDING_FILE).isFile

    fun hasCorrectionDraft(): Boolean {
        syncDraftState()
        return nextOwnTeamImportStep(moveItemPage, statsPage) == OwnTeamImportNextStep.MANUAL_CORRECTION
    }

    fun loadCorrectionDraft(): OwnTeamCorrectionDraft {
        syncDraftState()
        require(nextOwnTeamImportStep(moveItemPage, statsPage) == OwnTeamImportNextStep.MANUAL_CORRECTION) {
            "没有等待手动修正的双页草稿"
        }
        return buildOwnTeamCorrectionDraft(requireNotNull(moveItemPage), requireNotNull(statsPage))
    }

    fun saveCorrectedTeam(
        teamName: String,
        draft: OwnTeamCorrectionDraft,
        slots: List<OwnTeamCorrectionSlot>,
    ): ImportSaveResult {
        val name = teamName.trim()
        require(name.isNotEmpty()) { "队伍名称不能为空" }
        require(name.length <= 30) { "队伍名称不能超过 30 个字符" }
        require(slots.size == SLOT_COUNT) { "队伍必须包含 6 个槽位" }
        val incomplete = slots.filterNot(OwnTeamCorrectionSlot::isComplete)
        require(incomplete.isEmpty()) {
            "槽位 ${incomplete.joinToString { (it.slotIndex + 1).toString() }} 仍有未补全字段"
        }
        val saved = createCorrectedSavedTeam(name, draft, slots.sortedBy(OwnTeamCorrectionSlot::slotIndex))
        val fileName = saved.getString("savedTeamId") + ".json"
        context.filesDir.resolve("saved-teams").resolve(fileName)
            .writeUtf8Atomically(saved.toString(2))
        context.filesDir.resolve(DRAFT_FILE).delete()
        context.filesDir.resolve(PENDING_FILE).delete()
        moveItemPage = null
        statsPage = null
        return ImportSaveResult("队伍“$name”已修正并保存，可在首页和计算页使用", fileName, saved)
    }

    fun savePendingTeam(teamName: String): ImportSaveResult {
        val name = teamName.trim()
        require(name.isNotEmpty()) { "队伍名称不能为空" }
        require(name.length <= 30) { "队伍名称不能超过 30 个字符" }
        val pending = context.filesDir.resolve(PENDING_FILE)
        require(pending.isFile) { "没有等待命名的队伍" }
        val now = Instant.now().toString()
        val saved = JSONObject(pending.readText(Charsets.UTF_8)).apply {
            put("teamName", name)
            put("teamSlotName", name)
            put("updatedAt", now)
        }
        val fileName = saved.getString("savedTeamId") + ".json"
        context.filesDir.resolve("saved-teams").resolve(fileName)
            .writeUtf8Atomically(saved.toString(2))
        pending.delete()
        return ImportSaveResult("队伍“$name”已保存，可在首页和计算页使用", fileName, saved)
    }

    private fun persistDraft() {
        val draft = JSONObject().put("schemaVersion", 1).put("kind", "OwnTeamImportDraft")
        moveItemPage?.let { draft.put("moveItemPage", it.toJson()) }
        statsPage?.let { draft.put("statsPage", it.toJson()) }
        context.filesDir.resolve(DRAFT_FILE).writeUtf8Atomically(draft.toString(2))
    }

    private fun syncDraftState() {
        val draft = context.filesDir.resolve(DRAFT_FILE)
        if (!draft.isFile) {
            moveItemPage = null
            statsPage = null
        } else if (moveItemPage == null && statsPage == null) {
            restoreDraft()
        }
    }

    private fun restoreDraft() {
        val draft = context.filesDir.resolve(DRAFT_FILE)
        if (!draft.isFile) return
        runCatching {
            val root = JSONObject(draft.readText(Charsets.UTF_8))
            moveItemPage = root.optJSONObject("moveItemPage")?.let(RecognizedOwnTeamPage::fromJson)
            statsPage = root.optJSONObject("statsPage")?.let(RecognizedOwnTeamPage::fromJson)
        }.onFailure {
            moveItemPage = null
            statsPage = null
        }
    }

    private fun createSavedTeam(move: RecognizedOwnTeamPage, stats: RecognizedOwnTeamPage): JSONObject {
        val now = Instant.now()
        val stamp = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneId.systemDefault()).format(now)
        val savedTeamId = "android-own-team-$stamp"
        val members = JSONArray()
        stats.slots.forEach { statSlot ->
            val moveSlot = move.slots[statSlot.slotIndex]
            val moveSpecies = moveSlot.species
            val statSpecies = statSlot.species
            val speciesMismatch = moveSpecies?.canonicalId != statSpecies?.canonicalId
            val species = when {
                moveSpecies == null -> statSpecies
                statSpecies == null -> moveSpecies
                speciesMismatch && statSpecies.confidence > moveSpecies.confidence -> statSpecies
                else -> moveSpecies
            } ?: error("槽位 ${statSlot.slotIndex + 1} 缺少宝可梦")
            val member = JSONObject().apply {
                put("slotIndex", statSlot.slotIndex)
                put("species", species.toJson())
                put("level", 50)
                put("actualStats", JSONObject().apply { statSlot.actualStats.forEach(::put) })
                moveSlot.ability?.let { put("ability", it.toJson()) }
                moveSlot.item?.let { put("item", it.toJson()) }
                put("moves", JSONArray().apply { moveSlot.moves.forEach { put(it.toJson()) } })
                put("build", JSONObject().apply {
                    put("species", species.toJson())
                    put("level", 50)
                    put("actualStats", JSONObject().apply { statSlot.actualStats.forEach(::put) })
                    moveSlot.ability?.let { put("ability", it.toJson()) }
                    moveSlot.item?.let { put("item", it.toJson()) }
                    put("moves", JSONArray().apply {
                        moveSlot.moves.forEach { move -> put(JSONObject().put("move", move.toJson()).put("source", "OWN_BUILD")) }
                    })
                })
                put("warnings", JSONArray().apply {
                    if (speciesMismatch) {
                        if (species === statSpecies) {
                            put("Move page species '${moveSpecies?.displayName}' was replaced by higher-confidence stats page species '${statSpecies.displayName}'.")
                        } else {
                            put("Stats page species '${statSpecies?.displayName}' was replaced by slot-matched move page species '${moveSpecies?.displayName}'.")
                        }
                    }
                    if (moveSlot.moves.size in 1..3) {
                        put("Only ${moveSlot.moves.size}/4 move slots are filled; empty move slots are allowed.")
                    }
                })
            }
            members.put(member)
        }
        return JSONObject().apply {
            put("schemaVersion", 1)
            put("kind", "SavedOwnTeam")
            put("savedTeamId", savedTeamId)
            put("teamName", "")
            put("teamSlotName", "")
            put("status", "DAMAGE_READY")
            put("importStatus", "DAMAGE_READY")
            put("importSource", "SCREENSHOT")
            put("damageReady", true)
            put("userConfirmed", true)
            put("generatedAt", now.toString())
            put("createdAt", now.toString())
            put("updatedAt", now.toString())
            put("source", JSONObject().put("backend", "android_mlkit_chinese_bundled")
                .put("moveItemCapture", move.capturedAt).put("statsCapture", stats.capturedAt))
            put("members", members)
            put("warnings", JSONArray())
        }
    }

    private fun createCorrectedSavedTeam(
        teamName: String,
        draft: OwnTeamCorrectionDraft,
        slots: List<OwnTeamCorrectionSlot>,
    ): JSONObject {
        val now = Instant.now()
        val stamp = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneId.systemDefault()).format(now)
        val savedTeamId = "android-own-team-$stamp"
        val members = JSONArray().apply {
            slots.forEach { slot ->
                val config = slot.toPokemonConfig()
                put(JSONObject().apply {
                    put("slotIndex", slot.slotIndex)
                    put("species", config.species.toJson())
                    put("level", config.level)
                    put("actualStats", config.actualStats.toJson())
                    config.ability?.let { put("ability", it.toJson()) }
                    config.item?.let { put("item", it.toJson()) }
                    put("moves", JSONArray().apply { config.moves.forEach { put(it.entity.toJson()) } })
                    put("build", config.toSavedJson())
                    put("warnings", JSONArray().apply {
                        if (config.moves.size in 1..3) {
                            put("Only ${config.moves.size}/4 move slots are filled; empty move slots are allowed.")
                        }
                    })
                })
            }
        }
        return JSONObject().apply {
            put("schemaVersion", 1)
            put("kind", "SavedOwnTeam")
            put("savedTeamId", savedTeamId)
            put("teamName", teamName)
            put("teamSlotName", teamName)
            put("status", "DAMAGE_READY")
            put("importStatus", "DAMAGE_READY")
            put("importSource", "SCREENSHOT_MANUAL_CORRECTION")
            put("damageReady", true)
            put("userConfirmed", true)
            put("generatedAt", now.toString())
            put("createdAt", now.toString())
            put("updatedAt", now.toString())
            put("source", JSONObject().apply {
                put("backend", "android_mlkit_chinese_bundled")
                put("moveItemCapture", draft.moveItemCapturedAt)
                put("statsCapture", draft.statsCapturedAt)
                put("moveItemRecognized", draft.moveRecognized)
                put("moveItemTotal", draft.moveTotal)
                put("statsRecognized", draft.statsRecognized)
                put("statsTotal", draft.statsTotal)
                put("manualCorrection", true)
            })
            put("members", members)
            put("warnings", JSONArray())
        }
    }
}

private fun normalizeText(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
    .replace(Regex("\\s+"), "")

private fun normalizeLookup(value: String): String = normalizeText(value)
    .replace("·", "").replace("・", "").replace("’", "").replace("'", "")
    .lowercase(Locale.ROOT)

private fun levenshteinWithinOne(left: String, right: String): Boolean {
    if (abs(left.length - right.length) > 1) return false
    var edits = 0
    var li = 0
    var ri = 0
    while (li < left.length && ri < right.length) {
        if (left[li] == right[ri]) { li++; ri++; continue }
        edits++
        if (edits > 1) return false
        when {
            left.length > right.length -> li++
            right.length > left.length -> ri++
            else -> { li++; ri++ }
        }
    }
    return edits + (if (li < left.length || ri < right.length) 1 else 0) <= 1
}
