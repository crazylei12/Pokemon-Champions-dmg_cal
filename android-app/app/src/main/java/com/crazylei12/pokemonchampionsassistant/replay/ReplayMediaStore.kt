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

internal class ReplayMediaStore(context: Context) {
    companion object {
        const val RELATIVE_PATH = "Movies/Pokemon Champions Replays/"
        private const val FILE_PREFIX = "pokemon-champions_"
        private val NAME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
    }

    private val resolver = context.contentResolver
    private val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    fun createPending(): PendingReplayMedia {
        val displayName = "$FILE_PREFIX${LocalDateTime.now().format(NAME_FORMAT)}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, RELATIVE_PATH)
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
            },
            null,
            null,
        )
        check(updated == 1) { "MediaStore 无法发布已完成回放" }
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

    fun discard(pending: PendingReplayMedia?) {
        if (pending == null) return
        runCatching { pending.descriptor.close() }
        runCatching { resolver.delete(pending.uri, null, null) }
    }

    fun cleanupStalePending(): Int {
        val ids = mutableListOf<Long>()
        resolver.query(
            MediaStore.setIncludePending(collection),
            arrayOf(MediaStore.Video.Media._ID),
                "${MediaStore.Video.Media.IS_PENDING}=1 AND " +
                "${MediaStore.Video.Media.RELATIVE_PATH}=? AND " +
                "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?",
            arrayOf(RELATIVE_PATH, "$FILE_PREFIX%"),
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
