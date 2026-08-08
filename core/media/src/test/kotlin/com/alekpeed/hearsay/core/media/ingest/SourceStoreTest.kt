package com.alekpeed.hearsay.core.media.ingest

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.alekpeed.hearsay.core.model.project.SourceAvailability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class SourceStoreTest {

    private lateinit var context: Context
    private lateinit var store: SourceStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = SourceStore(context, Dispatchers.Unconfined)
    }

    @Test
    fun `a copy is written into app-private storage`() = runTest {
        val original = File(context.cacheDir, "original.wav").apply { writeBytes(ByteArray(64) { 7 }) }

        val copied = store.copyIntoManagedStorage(Uri.fromFile(original), "project-1", "original.wav")

        val copiedFile = File(requireNotNull(copied?.path))
        assertTrue(copiedFile.exists())
        assertTrue(copiedFile.absolutePath.startsWith(File(context.filesDir, "sources").absolutePath))
        assertEquals(64, copiedFile.length())
        // The extension is kept so the platform decoder can still recognize the container.
        assertTrue(copiedFile.name.endsWith(".wav"))
    }

    @Test
    fun `deleting a managed copy removes only files this app created`() = runTest {
        val original = File(context.cacheDir, "original.wav").apply { writeBytes(ByteArray(8)) }
        val copied = requireNotNull(store.copyIntoManagedStorage(Uri.fromFile(original), "project-1", "original.wav"))

        assertTrue(store.deleteManagedCopy(copied))
        assertFalse(File(requireNotNull(copied.path)).exists())
    }

    @Test
    fun `a file outside app storage is never deleted`() = runTest {
        // The user's own library is not ours to touch. A path outside the managed directory must be
        // refused even when it is handed to the delete call directly.
        val userFile = File(context.cacheDir, "the-users-own-recording.wav").apply { writeBytes(ByteArray(8)) }

        assertFalse(store.deleteManagedCopy(Uri.fromFile(userFile)))
        assertTrue(userFile.exists())
    }

    @Test
    fun `a traversal path out of app storage is refused`() = runTest {
        val escape = Uri.fromFile(File(context.filesDir, "sources/../../escaped.wav"))
        assertFalse(store.deleteManagedCopy(escape))
    }

    @Test
    fun `a readable file reports as available`() = runTest {
        val file = File(context.cacheDir, "present.wav").apply { writeBytes(ByteArray(8)) }
        assertEquals(SourceAvailability.AVAILABLE, store.availabilityOf(Uri.fromFile(file)))
    }

    @Test
    fun `a file that is gone reports as missing rather than failing`() = runTest {
        val missing = Uri.fromFile(File(context.cacheDir, "not-here.wav"))
        assertEquals(SourceAvailability.MISSING, store.availabilityOf(missing))
    }

    @Test
    fun `copying an unreadable source leaves nothing behind`() = runTest {
        val missing = Uri.fromFile(File(context.cacheDir, "not-here.wav"))

        assertEquals(null, store.copyIntoManagedStorage(missing, "project-2", "not-here.wav"))
        assertFalse(File(File(context.filesDir, "sources"), "project-2.wav").exists())
    }
}
