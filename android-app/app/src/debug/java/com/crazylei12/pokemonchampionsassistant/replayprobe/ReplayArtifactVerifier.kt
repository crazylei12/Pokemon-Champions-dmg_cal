package com.crazylei12.pokemonchampionsassistant.replayprobe

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import org.json.JSONArray
import org.json.JSONObject

internal object ReplayArtifactVerifier {
    const val ACTION_VERIFY =
        "com.crazylei12.pokemonchampionsassistant.debug.VERIFY_REPLAY_ARTIFACT"
    private const val LOG_TAG = "ReplayArtifactVerifier"

    fun verify(context: Context, uri: Uri): File {
        val outputDirectory = File(
            checkNotNull(context.getExternalFilesDir(null)),
            "replay-probe/artifact-inspection-${System.currentTimeMillis()}",
        ).apply { mkdirs() }
        val report = runCatching { inspect(context, uri, outputDirectory) }
            .getOrElse { error ->
                Log.e(LOG_TAG, "Replay artifact inspection failed", error)
                JSONObject()
                    .put("ok", false)
                    .put("uri", uri.toString())
                    .put("error", error.stackTraceToString())
            }
        val reportFile = File(outputDirectory, "inspection.json")
        reportFile.writeText(report.toString(2), Charsets.UTF_8)
        Log.i(LOG_TAG, "Replay artifact inspection: ${reportFile.absolutePath}")
        return reportFile
    }

    private fun inspect(context: Context, uri: Uri, outputDirectory: File): JSONObject {
        val tracks = JSONArray()
        val trackCount = MediaExtractor().useExtractor { extractor ->
            extractor.setDataSource(context, uri, null)
            repeat(extractor.trackCount) { trackIndex ->
                tracks.put(inspectTrack(context, uri, trackIndex, extractor.getTrackFormat(trackIndex)))
            }
            extractor.trackCount
        }

        val retriever = MediaMetadataRetriever()
        val frames = JSONArray()
        try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val frameTimesUs = listOf(
                0L,
                durationMs * 250L,
                durationMs * 500L,
                durationMs * 2_000L / 3L,
                (durationMs - 250L).coerceAtLeast(0L) * 1_000L,
            )
            frameTimesUs.forEachIndexed { index, timeUs ->
                val bitmap = checkNotNull(
                    retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC),
                ) { "Could not decode verification frame $index at $timeUs us" }
                val frameFile = File(outputDirectory, "frame-$index.jpg")
                FileOutputStream(frameFile).use { stream ->
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, stream))
                }
                frames.put(
                    JSONObject()
                        .put("timeUs", timeUs)
                        .put("width", bitmap.width)
                        .put("height", bitmap.height)
                        .put("path", frameFile.absolutePath),
                )
                bitmap.recycle()
            }
            return JSONObject()
                .put("ok", trackCount > 0 && frames.length() == frameTimesUs.size)
                .put("uri", uri.toString())
                .put("durationMs", durationMs)
                .put("mime", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE))
                .put("width", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH))
                .put("height", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT))
                .put("rotation", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION))
                .put("captureFrameRate", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE))
                .put("hasAudio", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
                .put("tracks", tracks)
                .put("frames", frames)
        } finally {
            retriever.release()
        }
    }

    private fun inspectTrack(
        context: Context,
        uri: Uri,
        trackIndex: Int,
        format: MediaFormat,
    ): JSONObject {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            extractor.selectTrack(trackIndex)
            val maximumSampleSize = format.integerOrNull(MediaFormat.KEY_MAX_INPUT_SIZE)
                ?.coerceIn(64 * 1_024, 8 * 1_024 * 1_024)
                ?: 4 * 1_024 * 1_024
            val buffer = ByteBuffer.allocate(maximumSampleSize)
            var sampleCount = 0L
            var firstPtsUs: Long? = null
            var lastPtsUs: Long? = null
            var totalBytes = 0L
            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val timeUs = extractor.sampleTime
                if (firstPtsUs == null) firstPtsUs = timeUs
                lastPtsUs = timeUs
                sampleCount += 1
                totalBytes += size
                if (!extractor.advance()) break
            }
            return JSONObject()
                .put("index", trackIndex)
                .put("mime", format.stringOrNull(MediaFormat.KEY_MIME))
                .put("durationUs", format.longOrNull(MediaFormat.KEY_DURATION))
                .put("width", format.integerOrNull(MediaFormat.KEY_WIDTH))
                .put("height", format.integerOrNull(MediaFormat.KEY_HEIGHT))
                .put("frameRate", format.integerOrNull(MediaFormat.KEY_FRAME_RATE))
                .put("sampleRate", format.integerOrNull(MediaFormat.KEY_SAMPLE_RATE))
                .put("channelCount", format.integerOrNull(MediaFormat.KEY_CHANNEL_COUNT))
                .put("bitRate", format.integerOrNull(MediaFormat.KEY_BIT_RATE))
                .put("sampleCount", sampleCount)
                .put("firstPtsUs", firstPtsUs)
                .put("lastPtsUs", lastPtsUs)
                .put("totalBytes", totalBytes)
        } finally {
            extractor.release()
        }
    }

    private inline fun <T> MediaExtractor.useExtractor(block: (MediaExtractor) -> T): T = try {
        block(this)
    } finally {
        release()
    }

    private fun MediaFormat.integerOrNull(key: String): Int? =
        if (containsKey(key)) getInteger(key) else null

    private fun MediaFormat.longOrNull(key: String): Long? =
        if (containsKey(key)) getLong(key) else null

    private fun MediaFormat.stringOrNull(key: String): String? =
        if (containsKey(key)) getString(key) else null
}
