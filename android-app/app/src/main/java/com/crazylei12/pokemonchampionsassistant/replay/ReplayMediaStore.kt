package com.crazylei12.pokemonchampionsassistant.replay

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

internal data class PendingReplayMedia(
    val uri: Uri,
    val descriptor: ParcelFileDescriptor,
    val displayName: String,
)

internal data class SavedReplay(
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val hasAudio: Boolean,
)

internal const val REPLAY_STALE_PENDING_MIN_AGE_MS = 60_000L

internal fun replayStalePendingCutoffEpochSeconds(nowEpochMillis: Long): Long =
    ((nowEpochMillis - REPLAY_STALE_PENDING_MIN_AGE_MS).coerceAtLeast(0L)) / 1_000L

internal class ReplayMediaStore(context: Context) {
    companion object {
        const val RELATIVE_PATH = "DCIM/Pokemon Champions Replays/"
        internal const val LEGACY_RELATIVE_PATH = "Movies/Pokemon Champions Replays/"
        private const val FILE_PREFIX = "pokemon-champions_"
        private val NAME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
    }

    private val resolver = context.contentResolver
    private val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    fun createPending(): PendingReplayMedia {
        val nowMillis = System.currentTimeMillis()
        val displayName = "$FILE_PREFIX${LocalDateTime.now().format(NAME_FORMAT)}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.Video.Media.DATE_TAKEN, nowMillis)
            put(MediaStore.Video.Media.DATE_MODIFIED, nowMillis / 1_000L)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: error("MediaStore 无法创建待写入回放")
        val descriptor = try {
            resolver.openFileDescriptor(uri, "rw")
                ?: error("MediaStore 无法打开待写入回放")
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
        return PendingReplayMedia(uri, descriptor, displayName)
    }

    fun publish(
        pending: PendingReplayMedia,
        durationMs: Long,
        hasAudio: Boolean,
    ): SavedReplay {
        runCatching { pending.descriptor.close() }
        val updated = resolver.update(
            pending.uri,
            ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
                put(MediaStore.Video.Media.DATE_MODIFIED, System.currentTimeMillis() / 1_000L)
            },
            null,
            null,
        )
        check(updated == 1) { "MediaStore 无法发布已完成回放" }
        resolver.notifyChange(pending.uri, null)
        val size = resolver.query(
            pending.uri,
            arrayOf(MediaStore.Video.Media.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        } ?: 0L
        return SavedReplay(pending.uri, pending.displayName, size, durationMs, hasAudio)
    }

    fun migrateLegacyReplaysToGallery(): Int {
        val rows = mutableListOf<Pair<Long, Long>>()
        resolver.query(
            collection,
            arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DATE_ADDED),
            "${MediaStore.Video.Media.IS_PENDING}=0 AND " +
                "${MediaStore.Video.Media.RELATIVE_PATH}=? AND " +
                "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?",
            arrayOf(LEGACY_RELATIVE_PATH, "$FILE_PREFIX%"),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) rows += cursor.getLong(0) to cursor.getLong(1)
        }
        var migrated = 0
        rows.forEach { (id, dateAddedSeconds) ->
            val uri = ContentUris.withAppendedId(collection, id)
            val nowMillis = System.currentTimeMillis()
            migrated += resolver.update(
                uri,
                ContentValues().apply {
                    put(MediaStore.Video.Media.RELATIVE_PATH, RELATIVE_PATH)
                    put(
                        MediaStore.Video.Media.DATE_TAKEN,
                        if (dateAddedSeconds > 0L) dateAddedSeconds * 1_000L else nowMillis,
                    )
                    put(MediaStore.Video.Media.DATE_MODIFIED, nowMillis / 1_000L)
                },
                null,
                null,
            )
            resolver.notifyChange(uri, null)
        }
        return migrated
    }

    fun discard(pending: PendingReplayMedia?) {
        if (pending == null) return
        runCatching { pending.descriptor.close() }
        runCatching { resolver.delete(pending.uri, null, null) }
    }

    fun cleanupStalePending(nowEpochMillis: Long = System.currentTimeMillis()): Int {
        // Capture the cutoff before querying. A new replay may be created while this
        // asynchronous cleanup is still running, but its DATE_ADDED will be newer than
        // this fixed cutoff and therefore cannot be mistaken for a stale item.
        val cutoffEpochSeconds = replayStalePendingCutoffEpochSeconds(nowEpochMillis)
        val ids = mutableListOf<Long>()
        resolver.query(
            MediaStore.setIncludePending(collection),
            arrayOf(MediaStore.Video.Media._ID),
                "${MediaStore.Video.Media.IS_PENDING}=1 AND " +
                "${MediaStore.Video.Media.RELATIVE_PATH} IN (?, ?) AND " +
                "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ? AND " +
                "${MediaStore.Video.Media.DATE_ADDED}<=?",
            arrayOf(RELATIVE_PATH, LEGACY_RELATIVE_PATH, "$FILE_PREFIX%", cutoffEpochSeconds.toString()),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) ids += cursor.getLong(0)
        }
        var deleted = 0
        ids.forEach { id ->
            deleted += resolver.delete(ContentUris.withAppendedId(collection, id), null, null)
        }
        return deleted
    }
}
