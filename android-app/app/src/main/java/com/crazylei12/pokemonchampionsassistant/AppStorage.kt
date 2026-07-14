package com.crazylei12.pokemonchampionsassistant

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal fun File.writeUtf8Atomically(body: String) {
    val directory = requireNotNull(parentFile) { "文件必须有父目录" }
    directory.mkdirs()
    val temporary = directory.resolve(".$name.tmp")
    temporary.writeText(body, Charsets.UTF_8)
    try {
        try {
            Files.move(
                temporary.toPath(),
                toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    } catch (error: Exception) {
        temporary.delete()
        throw error
    }
}

/** Removes JSON files exported by builds that predate app-private storage. */
object LegacyRecognitionStorageMigration {
    private const val PREFERENCES = "storage_migrations"
    private const val MIGRATION_KEY = "private_team_storage_v1"

    fun runInBackground(context: Context) {
        val appContext = context.applicationContext
        val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        if (preferences.getBoolean(MIGRATION_KEY, false)) return
        Thread({
            runCatching {
                val removed = removeLegacyFiles(appContext)
                preferences.edit().putBoolean(MIGRATION_KEY, true).apply()
                Log.i("AppStorage", "Removed $removed legacy recognition files")
            }.onFailure { error ->
                Log.w("AppStorage", "Legacy recognition cleanup will be retried", error)
            }
        }, "storage-migration").start()
    }

    private fun removeLegacyFiles(context: Context): Int {
        var removed = 0
        context.filesDir.resolve("team-preview-results").takeIf { it.exists() }?.let { directory ->
            removed += directory.walkBottomUp().count { it.isFile && it.delete() }
            directory.deleteRecursively()
        }

        val privateTeams = context.filesDir.resolve("saved-teams")
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME)
        val selection = "${MediaStore.Downloads.OWNER_PACKAGE_NAME} = ? AND " +
            "(${MediaStore.Downloads.DISPLAY_NAME} LIKE ? OR ${MediaStore.Downloads.DISPLAY_NAME} LIKE ?)"
        val arguments = arrayOf(
            context.packageName,
            "android-team-preview-%.json",
            "android-own-team-%.json",
        )
        context.contentResolver.query(collection, projection, selection, arguments, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val name = cursor.getString(nameColumn)
                val isPreview = name.startsWith("android-team-preview-")
                val hasPrivateTeamCopy = name.startsWith("android-own-team-") && privateTeams.resolve(name).isFile
                if (isPreview || hasPrivateTeamCopy) {
                    removed += context.contentResolver.delete(ContentUris.withAppendedId(collection, id), null, null)
                }
            }
        }
        return removed
    }
}
