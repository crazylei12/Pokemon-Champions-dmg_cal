package com.crazylei12.pokemonchampionsassistant.replay

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

internal class GameAudioCapture private constructor(
    private val record: AudioRecord,
    val channelCount: Int,
    val gameUid: Int,
) : AutoCloseable {
    companion object {
        private const val LOG_TAG = "GameAudioCapture"

        @SuppressLint("MissingPermission")
        fun create(context: Context, projection: MediaProjection): GameAudioCapture {
            val gameUid = context.packageManager.getApplicationInfo(
                POKEMON_CHAMPIONS_GAME_PACKAGE,
                PackageManager.ApplicationInfoFlags.of(0),
            ).uid
            val capture = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUid(gameUid)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val candidates = listOf(
                AudioFormat.CHANNEL_IN_STEREO to 2,
                AudioFormat.CHANNEL_IN_MONO to 1,
            )
            var lastFailure: Throwable? = null
            for ((channelMask, channelCount) in candidates) {
                var candidateRecord: AudioRecord? = null
                try {
                    val minimum = AudioRecord.getMinBufferSize(
                        REPLAY_AUDIO_SAMPLE_RATE,
                        channelMask,
                        AudioFormat.ENCODING_PCM_16BIT,
                    )
                    check(minimum > 0) { "AudioRecord minimum buffer query failed: $minimum" }
                    val format = AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(REPLAY_AUDIO_SAMPLE_RATE)
                        .setChannelMask(channelMask)
                        .build()
                    val record = AudioRecord.Builder()
                        .setAudioFormat(format)
                        .setAudioPlaybackCaptureConfig(capture)
                        .setBufferSizeInBytes(max(minimum * 2, REPLAY_AUDIO_SAMPLE_RATE * channelCount))
                        .build()
                        .also { candidateRecord = it }
                    check(record.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord failed to initialize" }
                    return GameAudioCapture(record, channelCount, gameUid)
                } catch (error: Throwable) {
                    runCatching { candidateRecord?.release() }
                    lastFailure = error
                }
            }
            throw lastFailure ?: IllegalStateException("No playback-capture audio format is available")
        }
    }

    private var encoder: MediaCodec? = null
    private val stopRequested = AtomicBoolean(false)
    private val captureFinished = CountDownLatch(1)
    private val encodeFinished = CountDownLatch(1)
    private val failure = AtomicReference<Throwable?>(null)
    private val failureNotified = AtomicBoolean(false)
    private val released = AtomicBoolean(false)
    private val pcmFrameSizeBytes = channelCount * 2
    private val pcmRing = PcmRingBuffer(
        capacity = REPLAY_AUDIO_SAMPLE_RATE * pcmFrameSizeBytes,
        frameSizeBytes = pcmFrameSizeBytes,
    )

    @SuppressLint("MissingPermission")
    fun preflight(durationMs: Long = 2_500L): ReplayPcmSignalSummary {
        val accumulator = ReplayPcmSignalAccumulator()
        val buffer = ShortArray(4_096)
        record.startRecording()
        try {
            val deadline = SystemClock.elapsedRealtime() + durationMs
            while (SystemClock.elapsedRealtime() < deadline) {
                val count = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (count > 0) accumulator.add(buffer, count)
                if (count == AudioRecord.ERROR_DEAD_OBJECT || count == AudioRecord.ERROR_INVALID_OPERATION) {
                    error("AudioRecord preflight failed: $count")
                }
            }
        } finally {
            runCatching { record.stop() }
        }
        return accumulator.summary()
    }

    @SuppressLint("MissingPermission")
    fun start(muxer: Mp4MuxerCoordinator, onFailure: (Throwable) -> Unit) {
        check(encoder == null) { "Audio encoder already started" }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val captureWorker = Thread(
            { captureLoop(onFailure) },
            "replay-audio-capture",
        )
        val encodeWorker = Thread(
            { encodeLoop(codec, muxer, onFailure) },
            "replay-audio-codec",
        )
        var codecStarted = false
        var captureWorkerStarted = false
        var encodeWorkerStarted = false
        try {
            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                REPLAY_AUDIO_SAMPLE_RATE,
                channelCount,
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, REPLAY_AUDIO_BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            codecStarted = true
            record.startRecording()
            encoder = codec
            captureWorker.start()
            captureWorkerStarted = true
            encodeWorker.start()
            encodeWorkerStarted = true
        } catch (error: Throwable) {
            stopRequested.set(true)
            runCatching { record.stop() }
            pcmRing.close()
            if (captureWorkerStarted) runCatching { captureWorker.join(2_000L) }
            if (encodeWorkerStarted) runCatching { encodeWorker.join(2_000L) }
            encoder = null
            if (codecStarted) runCatching { codec.stop() }
            runCatching { codec.release() }
            throw error
        }
    }

    fun signalEndAndAwait(timeoutSeconds: Long = 8L) {
        if (encoder == null) return
        stopRequested.set(true)
        runCatching { record.stop() }
        check(captureFinished.await(timeoutSeconds, TimeUnit.SECONDS)) { "Timed out stopping playback capture" }
        check(encodeFinished.await(timeoutSeconds, TimeUnit.SECONDS)) { "Timed out draining AAC encoder" }
        failure.get()?.let { throw it }
    }

    private fun captureLoop(onFailure: (Throwable) -> Unit) {
        val samples = ShortArray(4_096)
        try {
            while (!stopRequested.get()) {
                val count = record.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                if (count > 0) {
                    val bytes = ByteArray(count * 2)
                    repeat(count) { index ->
                        val value = samples[index].toInt()
                        bytes[index * 2] = (value and 0xff).toByte()
                        bytes[index * 2 + 1] = ((value ushr 8) and 0xff).toByte()
                    }
                    pcmRing.write(bytes)
                } else if (
                    !stopRequested.get() &&
                    (count == AudioRecord.ERROR_DEAD_OBJECT || count == AudioRecord.ERROR_INVALID_OPERATION)
                ) {
                    error("AudioRecord read failed: $count")
                }
            }
        } catch (error: Throwable) {
            if (!stopRequested.get() && failure.compareAndSet(null, error)) {
                notifyFailure(error, onFailure)
            }
            stopRequested.set(true)
        } finally {
            pcmRing.close()
            captureFinished.countDown()
        }
    }

    private fun encodeLoop(
        codec: MediaCodec,
        muxer: Mp4MuxerCoordinator,
        onFailure: (Throwable) -> Unit,
    ) {
        val info = MediaCodec.BufferInfo()
        val ptsClock = AudioPtsClock()
        val pcm = ByteArray(16_384)
        var inputEnded = false
        var outputEnded = false
        try {
            while (!outputEnded) {
                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(10_000L)
                    if (inputIndex >= 0) {
                        val input = codec.getInputBuffer(inputIndex) ?: error("AAC encoder returned no input buffer")
                        input.clear()
                        val readResult = pcmRing.read(pcm, maximumBytes = minOf(pcm.size, input.capacity()))
                        val droppedFrames = readResult.droppedBytesBeforeRead / pcmFrameSizeBytes
                        if (droppedFrames > 0L) {
                            ptsClock.skipFrames(droppedFrames)
                            Log.w(
                                LOG_TAG,
                                "Playback capture overflow dropped $droppedFrames frame(s); preserving the AAC timeline gap",
                            )
                        }
                        val bytesRead = readResult.bytesRead
                        if (bytesRead < 0) {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                ptsClock.nextPresentationTimeUs(0),
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputEnded = true
                        } else if (bytesRead > 0) {
                            input.put(pcm, 0, bytesRead)
                            val frames = bytesRead / pcmFrameSizeBytes
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                bytesRead,
                                ptsClock.nextPresentationTimeUs(frames),
                                0,
                            )
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, 0, ptsClock.nextPresentationTimeUs(0), 0)
                        }
                    }
                }

                var drainAgain = true
                while (drainAgain) {
                    when (val outputIndex = codec.dequeueOutputBuffer(info, if (inputEnded) 10_000L else 0L)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> drainAgain = false
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                            muxer.addTrack(ReplayTrackKind.AUDIO, codec.outputFormat)
                        else -> if (outputIndex >= 0) {
                            val output = codec.getOutputBuffer(outputIndex)
                            if (output != null && info.size > 0) {
                                muxer.writeSample(ReplayTrackKind.AUDIO, output, info)
                            }
                            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            codec.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            if (failure.compareAndSet(null, error)) notifyFailure(error, onFailure)
        } finally {
            runCatching { record.stop() }
            encodeFinished.countDown()
        }
    }

    private fun notifyFailure(error: Throwable, onFailure: (Throwable) -> Unit) {
        if (failureNotified.compareAndSet(false, true)) onFailure(error)
    }

    override fun close() {
        if (!released.compareAndSet(false, true)) return
        stopRequested.set(true)
        runCatching { record.stop() }
        pcmRing.close()
        if (encoder != null) {
            runCatching { captureFinished.await(2, TimeUnit.SECONDS) }
            runCatching { encodeFinished.await(2, TimeUnit.SECONDS) }
        }
        runCatching { record.release() }
        encoder?.let { codec ->
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
        encoder = null
    }
}
