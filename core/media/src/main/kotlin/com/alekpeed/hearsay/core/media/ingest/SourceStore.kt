package com.alekpeed.hearsay.core.media.ingest

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.alekpeed.hearsay.core.common.dispatchers.Dispatcher
import com.alekpeed.hearsay.core.common.dispatchers.HearsayDispatcher
import com.alekpeed.hearsay.core.model.project.SourceAvailability
import com.alekpeed.hearsay.core.model.project.StorageMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns where a project's source audio lives.
 *
 * Two modes, both required by the product: [StorageMode.REFERENCED] keeps the user's own file where
 * they put it and holds a persisted permission to read it, and [StorageMode.MANAGED_COPY] takes a
 * private copy so the project keeps working when the original moves. Referenced files are never
 * modified or deleted by this app.
 */
@Singleton
class SourceStore @Inject constructor(
    @ApplicationContext private val context: Context,
    @Dispatcher(HearsayDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) {

    private val sourcesDir: File get() = File(context.filesDir, "sources").apply { mkdirs() }

    /**
     * Asks the platform to keep read access to a picked document across restarts.
     * Returns false when the grant is refused, which the caller must surface rather than hide.
     */
    fun persistReadPermission(uri: Uri): Boolean = runCatching {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        true
    }.getOrElse { false }

    fun releaseReadPermission(uri: Uri) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** Copies the picked file into app-private storage and returns the copy's URI. */
    suspend fun copyIntoManagedStorage(uri: Uri, projectId: String, displayName: String?): Uri? =
        withContext(ioDispatcher) {
            val extension = displayName?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() }
            val target = File(sourcesDir, if (extension != null) "$projectId.$extension" else projectId)
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output) }
                } ?: return@runCatching null
                Uri.fromFile(target)
            }.getOrElse {
                target.delete()
                null
            }
        }

    /** Deletes only files this app created. A referenced source is left alone. */
    suspend fun deleteManagedCopy(uri: Uri): Boolean = withContext(ioDispatcher) {
        val path = uri.path ?: return@withContext false
        val file = File(path)
        if (!file.canonicalPath.startsWith(sourcesDir.canonicalPath)) return@withContext false
        file.delete()
    }

    suspend fun availabilityOf(uri: Uri): SourceAvailability = withContext(ioDispatcher) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { }
            SourceAvailability.AVAILABLE
        }.getOrElse { error ->
            when (error) {
                is SecurityException -> SourceAvailability.PERMISSION_LOST
                else -> SourceAvailability.MISSING
            }
        }
    }

    fun freeSpaceBytes(): Long = context.filesDir.usableSpace
}
