package com.crazylei12.pokemonchampionsassistant.replay

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.ParcelFileDescriptor
import java.nio.ByteBuffer

internal class Mp4MuxerCoordinator(
    descriptor: ParcelFileDescriptor,
    expectAudio: Boolean,
) : AutoCloseable {
    private data class PendingSample(
        val kind: ReplayTrackKind,
        val bytes: ByteArray,
        val presentationTimeUs: Long,
        val flags: Int,
    )

    private val muxer = MediaMuxer(descriptor.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val gate = MuxerTrackGate(expectAudio)
    private val trackIndices = mutableMapOf<ReplayTrackKind, Int>()
    private val pendingSamples = ArrayDeque<PendingSample>()
    private var pendingBytes = 0L
    private var started = false
    private var closed = false
    private var wroteSample = false
    private var maximumPresentationTimeUs = 0L

    @Synchronized
    fun addTrack(kind: ReplayTrackKind, format: MediaFormat) {
        check(!closed) { "Muxer is closed" }
        check(kind !in trackIndices) { "Muxer track already exists: $kind" }
        trackIndices[kind] = muxer.addTrack(format)
        if (gate.markReady(kind)) {
            muxer.start()
            started = true
            while (pendingSamples.isNotEmpty()) writePending(pendingSamples.removeFirst())
            pendingBytes = 0L
        }
    }

    @Synchronized
    fun writeSample(kind: ReplayTrackKind, source: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (closed || info.size <= 0 || info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return
        val duplicate = source.duplicate().apply {
            position(info.offset)
            limit(info.offset + info.size)
        }
        val bytes = ByteArray(info.size)
        duplicate.get(bytes)
        val pending = PendingSample(kind, bytes, info.presentationTimeUs, info.flags)
        if (started) {
            writePending(pending)
        } else {
            pendingBytes += bytes.size
            check(pendingBytes <= 16L * 1024L * 1024L) { "Muxer track negotiation buffered too much output" }
            pendingSamples.addLast(pending)
        }
    }

    private fun writePending(sample: PendingSample) {
        val track = trackIndices[sample.kind] ?: error("Missing muxer track: ${sample.kind}")
        val info = MediaCodec.BufferInfo().apply {
            set(0, sample.bytes.size, sample.presentationTimeUs, sample.flags)
        }
        muxer.writeSampleData(track, ByteBuffer.wrap(sample.bytes), info)
        wroteSample = true
        maximumPresentationTimeUs = maxOf(maximumPresentationTimeUs, sample.presentationTimeUs)
    }

    @Synchronized
    fun finish(): Long {
        check(!closed) { "Muxer is already closed" }
        check(started) { "Muxer never received all required track formats" }
        check(wroteSample) { "Muxer received no media samples" }
        closed = true
        var failure: Throwable? = null
        try {
            muxer.stop()
        } catch (error: Throwable) {
            failure = error
        }
        try {
            muxer.release()
        } catch (error: Throwable) {
            val primaryFailure = failure
            if (primaryFailure == null) failure = error else primaryFailure.addSuppressed(error)
        } finally {
            pendingSamples.clear()
            pendingBytes = 0L
        }
        failure?.let { throw it }
        return maximumPresentationTimeUs / 1_000L
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        if (started) runCatching { muxer.stop() }
        runCatching { muxer.release() }
        pendingSamples.clear()
    }
}
