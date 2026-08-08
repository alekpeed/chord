package com.alekpeed.hearsay.core.media.ingest

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.alekpeed.hearsay.core.common.dispatchers.Dispatcher
import com.alekpeed.hearsay.core.common.dispatchers.HearsayDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import javax.inject.Inject

/** What the app could learn about a file before deciding whether it can work with it. */
data class ProbedMedia(
    val displayName: String?,
    val title: String?,
    val artist: String?,
    val album: String?,
    val mimeType: String?,
    val durationMs: Long,
    val sampleRate: Int?,
    val channels: Int?,
    val fileSizeBytes: Long?,
    val hasAudioTrack: Boolean,
)

/**
 * Reads metadata out of a user-selected file.
 *
 * Everything here can fail on a real device — a URI whose permission was revoked, a container the
 * platform decoder does not support, a file that has since been deleted — so every field is
 * optional and the caller decides what is fatal.
 */
class MediaProbe @Inject constructor(
    @ApplicationContext private val context: Context,
    @Dispatcher(HearsayDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) {

    suspend fun probe(uri: Uri): ProbedMedia? = withContext(ioDispatcher) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val audio = audioFormat(uri)
            ProbedMedia(
                displayName = displayName(uri),
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                    ?: context.contentResolver.getType(uri),
                durationMs = durationMs,
                sampleRate = audio?.first,
                channels = audio?.second,
                fileSizeBytes = fileSize(uri),
                hasAudioTrack = audio != null,
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * Content hash used to notice that a file is already in the library.
     *
     * Hashing is capped: past [MaxHashBytes] the leading bytes plus the exact size are enough to
     * separate real songs, and reading a whole video file to import its audio is not worth the wait.
     */
    suspend fun checksum(uri: Uri): String? = withContext(ioDispatcher) {
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (total < MaxHashBytes) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                    total += read
                }
            } ?: return@withContext null
            fileSize(uri)?.let { digest.update(it.toString().toByteArray()) }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            null
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    private fun fileSize(uri: Uri): Long? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }
    }.getOrNull()

    /** Sample rate and channel count of the first audio track, or null when there is no audio at all. */
    private fun audioFormat(uri: Uri): Pair<Int?, Int?>? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            (0 until extractor.trackCount)
                .map { extractor.getTrackFormat(it) }
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?.let { format ->
                    val rate = format.takeIf { it.containsKey(MediaFormat.KEY_SAMPLE_RATE) }
                        ?.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    val channels = format.takeIf { it.containsKey(MediaFormat.KEY_CHANNEL_COUNT) }
                        ?.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    rate to channels
                }
        } catch (error: Exception) {
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    private companion object {
        const val MaxHashBytes = 32L * 1024 * 1024
    }
}
