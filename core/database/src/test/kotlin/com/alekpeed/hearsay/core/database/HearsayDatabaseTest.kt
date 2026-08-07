package com.alekpeed.hearsay.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.alekpeed.hearsay.core.database.dao.ChartDao
import com.alekpeed.hearsay.core.database.entity.BeatEventEntity
import com.alekpeed.hearsay.core.database.entity.ChordEventEntity
import com.alekpeed.hearsay.core.database.entity.MediaAssetEntity
import com.alekpeed.hearsay.core.database.entity.ProjectEntity
import com.alekpeed.hearsay.core.database.entity.RevisionEntity
import com.alekpeed.hearsay.core.database.entity.SectionEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HearsayDatabaseTest {

    private lateinit var database: HearsayDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HearsayDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() = database.close()

    private fun project(id: String = "p1") = ProjectEntity(
        id = id,
        title = "Blue in Green",
        artist = "Miles Davis",
        album = "Kind of Blue",
        tags = listOf("jazz", "ballad"),
        createdAtMs = 1,
        updatedAtMs = 1,
        durationMs = 327_000,
        analysisStatus = "NOT_STARTED",
        analysisProfile = "BALANCED",
        activeRevisionId = null,
        keyLabel = null,
        tempoBpm = null,
        lastPracticedAtMs = null,
    )

    private fun revision(id: String = "r1", projectId: String = "p1", source: String = "MACHINE") =
        RevisionEntity(id, projectId, null, 2, "Analysis", source, null)

    private fun chord(revisionId: String, localId: String, startMs: Long) = ChordEventEntity(
        id = ChartDao.chordEventId(revisionId, localId),
        revisionId = revisionId,
        localId = localId,
        startMs = startMs,
        endMs = startMs + 2000,
        chordJson = """{"root":{"letter":"D","alteration":0},"quality":"MINOR","seventh":"MINOR"}""",
        displaySymbol = "Dm7",
        rootPitchClass = 2,
        bassPitchClass = 2,
        confidence = 0.8f,
        source = "MACHINE",
        userConfirmed = false,
    )

    @Test
    fun `a project survives a round trip with its tags and assets`() = runTest {
        database.projectDao().upsertProject(project())
        database.projectDao().upsertAsset(
            MediaAssetEntity(
                id = "a1",
                projectId = "p1",
                role = "SOURCE",
                uri = "content://media/1",
                storageMode = "REFERENCED",
                mimeType = "audio/flac",
                durationMs = 327_000,
                sampleRate = 44_100,
                channels = 2,
                fileSizeBytes = 42_000_000,
                checksum = "abc123",
                availability = "AVAILABLE",
                displayName = "blue-in-green.flac",
            ),
        )

        val stored = database.projectDao().getProject("p1")
        assertNotNull(stored)
        assertEquals(listOf("jazz", "ballad"), stored?.project?.tags)
        assertEquals(1, stored?.assets?.size)
        assertEquals("audio/flac", stored?.assets?.first()?.mimeType)
    }

    @Test
    fun `a source is found again by its checksum`() = runTest {
        database.projectDao().upsertProject(project())
        database.projectDao().upsertAsset(
            MediaAssetEntity(
                "a1", "p1", "SOURCE", "content://media/1", "REFERENCED", "audio/wav",
                1000, 44_100, 2, 100, "checksum-1", "AVAILABLE", "take.wav",
            ),
        )
        assertEquals("a1", database.projectDao().findByChecksum("checksum-1")?.id)
        assertNull(database.projectDao().findByChecksum("nothing"))
    }

    @Test
    fun `deleting a project takes its analysis with it`() = runTest {
        database.projectDao().upsertProject(project())
        database.revisionDao().insert(revision())
        database.chartDao().upsertChords(listOf(chord("r1", "e1", 0), chord("r1", "e2", 2000)))
        database.chartDao().insertBeats(listOf(BeatEventEntity(0, "r1", 0, 1, 1, 1f, "MACHINE")))
        database.chartDao().insertSections(
            listOf(SectionEntity(ChartDao.sectionId("r1", "s1"), "r1", "s1", "Head", 0, 4000, 0, 1f, "MACHINE")),
        )

        assertEquals(2, database.chartDao().chords("r1").size)

        database.projectDao().deleteProject("p1")

        assertEquals(0, database.chartDao().chords("r1").size)
        assertEquals(0, database.chartDao().beats("r1").size)
        assertEquals(0, database.chartDao().sections("r1").size)
        assertTrue(database.revisionDao().revisionsFor("p1").isEmpty())
    }

    @Test
    fun `copying a chart to a new revision keeps the original intact`() = runTest {
        database.projectDao().upsertProject(project())
        database.revisionDao().insert(revision())
        database.revisionDao().insert(revision(id = "r2", source = "USER"))
        database.chartDao().upsertChords(listOf(chord("r1", "e1", 0), chord("r1", "e2", 2000)))

        database.chartDao().copyChart(fromRevisionId = "r1", toRevisionId = "r2")

        val original = database.chartDao().chords("r1")
        val copy = database.chartDao().chords("r2")
        assertEquals(2, original.size)
        assertEquals(2, copy.size)
        // Local ids are what let a corrected chord be traced back to what the model said.
        assertEquals(original.map { it.localId }, copy.map { it.localId })
        assertEquals(listOf("r2:e1", "r2:e2"), copy.map { it.id })
    }

    @Test
    fun `editing a copied chord leaves the machine revision untouched`() = runTest {
        database.projectDao().upsertProject(project())
        database.revisionDao().insert(revision())
        database.revisionDao().insert(revision(id = "r2", source = "USER"))
        database.chartDao().upsertChords(listOf(chord("r1", "e1", 0)))
        database.chartDao().copyChart("r1", "r2")

        database.chartDao().upsertChords(
            listOf(database.chartDao().chords("r2").single().copy(displaySymbol = "D7", source = "USER")),
        )

        assertEquals("Dm7", database.chartDao().chords("r1").single().displaySymbol)
        assertEquals("D7", database.chartDao().chords("r2").single().displaySymbol)
    }

    @Test
    fun `chord rows come back in time order and stream changes`() = runTest {
        database.projectDao().upsertProject(project())
        database.revisionDao().insert(revision())
        database.chartDao().upsertChords(listOf(chord("r1", "e2", 4000), chord("r1", "e1", 0)))

        assertEquals(listOf(0L, 4000L), database.chartDao().observeChords("r1").first().map { it.startMs })
    }

    @Test
    fun `confirming a chord is recorded`() = runTest {
        database.projectDao().upsertProject(project())
        database.revisionDao().insert(revision())
        database.chartDao().upsertChords(listOf(chord("r1", "e1", 0)))

        database.chartDao().setConfirmed(ChartDao.chordEventId("r1", "e1"), true)

        assertTrue(database.chartDao().chords("r1").single().userConfirmed)
    }

    @Test
    fun `the machine revision is findable after user revisions exist`() = runTest {
        database.projectDao().upsertProject(project())
        database.revisionDao().insert(revision())
        database.revisionDao().insert(revision(id = "r2", source = "USER"))

        assertEquals("r1", database.revisionDao().machineRevision("p1")?.id)
        assertEquals(2, database.revisionDao().revisionsFor("p1").size)
    }
}
