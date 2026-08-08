package com.alekpeed.hearsay.feature.library

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.alekpeed.hearsay.core.model.project.AnalysisProfile
import com.alekpeed.hearsay.core.model.project.AnalysisStatus
import com.alekpeed.hearsay.core.model.project.MediaAsset
import com.alekpeed.hearsay.core.model.project.MediaRole
import com.alekpeed.hearsay.core.model.project.Project
import com.alekpeed.hearsay.core.model.project.ProjectWithSource
import com.alekpeed.hearsay.core.model.project.SourceAvailability
import com.alekpeed.hearsay.core.model.project.StorageMode
import com.alekpeed.hearsay.core.model.repository.ImportFailure
import com.alekpeed.hearsay.core.model.repository.ImportResult
import com.alekpeed.hearsay.core.model.repository.MediaImportRepository
import com.alekpeed.hearsay.core.model.repository.ProjectRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {

    private val projects = MutableStateFlow<List<ProjectWithSource>>(emptyList())
    private var importResult: ImportResult = ImportResult.Success("p1")
    private val importCalls = mutableListOf<Triple<String, StorageMode, AnalysisProfile>>()

    private val projectRepository = object : ProjectRepository {
        override fun observeLibrary(): Flow<List<ProjectWithSource>> = projects
        override fun observeProject(projectId: String) =
            projects.map { list -> list.firstOrNull { it.project.id == projectId } }

        override suspend fun getProject(projectId: String) =
            projects.value.firstOrNull { it.project.id == projectId }

        override suspend fun updateMetadata(projectId: String, title: String, artist: String?, album: String?) = Unit
        override suspend fun updateAvailability(mediaAssetId: String, availability: SourceAvailability) = Unit
        override suspend fun relinkSource(projectId: String, uri: String, storageMode: StorageMode) = Unit
        override suspend fun markPracticed(projectId: String, atMs: Long) = Unit
        override suspend fun deleteProject(projectId: String) {
            projects.value = projects.value.filterNot { it.project.id == projectId }
        }
    }

    private val importRepository = object : MediaImportRepository {
        override suspend fun import(uri: String, storageMode: StorageMode, profile: AnalysisProfile): ImportResult {
            importCalls += Triple(uri, storageMode, profile)
            return importResult
        }

        override suspend fun probe(uri: String): MediaAsset? = null
    }

    private fun project(id: String, title: String, artist: String? = null) = ProjectWithSource(
        project = Project(
            id = id,
            title = title,
            artist = artist,
            album = null,
            tags = listOf("standards"),
            createdAtMs = 0,
            updatedAtMs = 0,
            durationMs = 1000,
            analysisStatus = AnalysisStatus.NOT_STARTED,
            activeRevisionId = null,
        ),
        source = MediaAsset(
            "a-$id", id, MediaRole.SOURCE, "content://$id", StorageMode.REFERENCED,
            "audio/mpeg", 1000, 44_100, 2, 10, "sum-$id", SourceAvailability.AVAILABLE, "$title.mp3",
        ),
    )

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = LibraryViewModel(projectRepository, importRepository)

    @Test
    fun `an empty library says so rather than showing an empty list`() = runTest {
        viewModel().uiState.test {
            assertEquals(LibraryUiState.Empty, awaitItemOfType<LibraryUiState.Empty>())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `search matches title, artist and tags`() = runTest {
        projects.value = listOf(project("1", "Blue in Green", "Miles Davis"), project("2", "Giant Steps", "Coltrane"))
        val model = viewModel()

        model.uiState.test {
            awaitItemOfType<LibraryUiState.Content>()

            model.onQueryChange("coltrane")
            assertEquals(listOf("Giant Steps"), awaitMatching { it.query == "coltrane" }.projects.map { it.project.title })

            model.onQueryChange("standards")
            assertEquals(2, awaitMatching { it.query == "standards" }.projects.size)

            model.onQueryChange("nothing here")
            assertTrue(awaitMatching { it.query == "nothing here" }.projects.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `importing passes the chosen storage mode through`() = runTest {
        val model = viewModel()
        model.onImport("content://picked", StorageMode.MANAGED_COPY)

        assertEquals(
            Triple("content://picked", StorageMode.MANAGED_COPY, AnalysisProfile.BALANCED),
            importCalls.single(),
        )
    }

    @Test
    fun `a duplicate import is reported, not silently repeated`() = runTest {
        importResult = ImportResult.AlreadyImported("p9", "Blue in Green")
        val model = viewModel()

        model.onImport("content://picked", StorageMode.REFERENCED)

        val message = model.messages.value
        assertTrue(message is LibraryMessage.Duplicate)
        assertEquals("Blue in Green", (message as LibraryMessage.Duplicate).title)
    }

    @Test
    fun `a failed import explains what to do about it`() = runTest {
        importResult = ImportResult.Failed(ImportFailure.InsufficientStorage)
        val model = viewModel()

        model.onImport("content://picked", StorageMode.MANAGED_COPY)

        val message = model.messages.value
        assertTrue(message is LibraryMessage.Failed)
        assertTrue((message as LibraryMessage.Failed).reason.contains("free space"))
    }

    @Test
    fun `a message is shown once`() = runTest {
        val model = viewModel()
        model.onImport("content://picked", StorageMode.REFERENCED)
        assertTrue(model.messages.value != null)

        model.onMessageShown()
        assertEquals(null, model.messages.value)
    }

    @Test
    fun `deleting removes the project from the library`() = runTest {
        val target = project("1", "Blue in Green")
        projects.value = listOf(target, project("2", "Giant Steps"))
        val model = viewModel()

        model.uiState.test {
            awaitItemOfType<LibraryUiState.Content>()
            model.onDelete(target)
            assertEquals(listOf("Giant Steps"), awaitMatching { it.projects.size == 1 }.projects.map { it.project.title })
            cancelAndIgnoreRemainingEvents()
        }
    }

    private suspend inline fun <reified T : LibraryUiState> ReceiveTurbine<LibraryUiState>.awaitItemOfType(): T {
        repeat(MaxEmissions) {
            val item = awaitItem()
            if (item is T) return item
        }
        error("No ${T::class.simpleName} within $MaxEmissions emissions")
    }

    private suspend fun ReceiveTurbine<LibraryUiState>.awaitMatching(
        predicate: (LibraryUiState.Content) -> Boolean,
    ): LibraryUiState.Content {
        repeat(MaxEmissions) {
            val item = awaitItem()
            if (item is LibraryUiState.Content && predicate(item)) return item
        }
        error("No matching Content state within $MaxEmissions emissions")
    }

    private companion object {
        const val MaxEmissions = 20
    }
}
