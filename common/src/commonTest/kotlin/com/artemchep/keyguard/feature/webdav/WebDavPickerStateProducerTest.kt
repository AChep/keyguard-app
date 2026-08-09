package com.artemchep.keyguard.feature.webdav

import arrow.core.Either
import arrow.core.right
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.usecase.ListWebDavDirectory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebDavPickerStateProducerTest {
    @Test
    fun `breadcrumbs remain bounded by root`() {
        val selectedPaths = mutableListOf<String>()

        val breadcrumbs = webDavPickerBreadcrumbs("one/two") { path ->
            selectedPaths += path
        }

        assertEquals(listOf("/", "one", "two"), breadcrumbs.map { it.name })
        breadcrumbs[0].onClick?.invoke()
        breadcrumbs[1].onClick?.invoke()
        breadcrumbs[2].onClick?.invoke()
        assertEquals(listOf("", "one"), selectedPaths)
    }

    @Test
    fun `validates create database file names`() {
        assertEquals(
            WebDavPickerState.FileNameError.Required,
            validateWebDavPickerFileName(" ", emptyList()),
        )
        assertEquals(
            WebDavPickerState.FileNameError.Invalid,
            validateWebDavPickerFileName("folder/vault.kdbx", emptyList()),
        )
        assertEquals(
            WebDavPickerState.FileNameError.ExtensionRequired,
            validateWebDavPickerFileName("vault.txt", emptyList()),
        )
        assertEquals(
            WebDavPickerState.FileNameError.AlreadyExists,
            validateWebDavPickerFileName("Vault.KDBX", listOf("vault.kdbx")),
        )
        assertNull(
            validateWebDavPickerFileName("new vault.kdbx", listOf("other.kdbx")),
        )
    }

    @Test
    fun `collection names participate in file name collisions`() {
        val items = listOf(
            pickerItem("vault.kdbx", isCollection = true),
            pickerItem("other.kdbx"),
        )

        val existingNames = webDavPickerExistingResourceNames(items)

        assertEquals(listOf("vault.kdbx", "other.kdbx"), existingNames)
        assertEquals(
            WebDavPickerState.FileNameError.AlreadyExists,
            validateWebDavPickerFileName("Vault.KDBX", existingNames),
        )
    }

    @Test
    fun `joins file name to current directory`() {
        assertEquals("vault.kdbx", joinWebDavPickerPath("", "vault.kdbx"))
        assertEquals(
            "nested/vault.kdbx",
            joinWebDavPickerPath("nested", "vault.kdbx"),
        )
    }

    @Test
    fun `sorts folders first and names case insensitively`() {
        val children = listOf(
            child("z.kdbx"),
            child("beta", isCollection = true),
            child("Alpha", isCollection = true),
            child("A.kdbx"),
        )

        assertEquals(
            listOf("Alpha", "beta", "A.kdbx", "z.kdbx"),
            sortWebDavDirectoryChildren(children).map { it.name },
        )
    }

    @Test
    fun `only keepass files can be opened in open mode`() {
        assertTrue(
            isWebDavPickerFileSelectable(
                WebDavPickerRoute.Mode.OpenKeePassDatabase,
                "vault.KDBX",
            ),
        )
        assertFalse(
            isWebDavPickerFileSelectable(
                WebDavPickerRoute.Mode.OpenKeePassDatabase,
                "notes.txt",
            ),
        )
        assertFalse(
            isWebDavPickerFileSelectable(
                WebDavPickerRoute.Mode.CreateKeePassDatabase,
                "vault.kdbx",
            ),
        )
    }

    @Test
    fun `changing folders cancels the stale directory request`() = runTest {
        val pathFlow = MutableStateFlow("slow")
        val refreshFlow = MutableStateFlow(0)
        val slowStarted = CompletableDeferred<Unit>()
        val slowCancelled = CompletableDeferred<Unit>()
        val loads = mutableListOf<DirectoryLoad>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            webDavPickerDirectoryLoadFlow(
                pathFlow = pathFlow,
                refreshFlow = refreshFlow,
            ) { path ->
                if (path == "slow") {
                    slowStarted.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        slowCancelled.complete(Unit)
                    }
                }
                emptyChildren()
            }.take(3).toList(loads)
        }

        slowStarted.await()
        pathFlow.value = "fast"
        job.join()

        slowCancelled.await()
        assertEquals(
            listOf("slow", "fast", "fast"),
            loads.map { (path, _) -> path },
        )
        assertIs<Loadable.Loading>(loads[0].second)
        assertIs<Loadable.Loading>(loads[1].second)
        assertIs<Loadable.Ok<*>>(loads[2].second)
    }

    @Test
    fun `refresh loads the current directory again`() = runTest {
        val pathFlow = MutableStateFlow("folder")
        val refreshFlow = MutableStateFlow(0)
        val firstLoadCompleted = CompletableDeferred<Unit>()
        val loadedPaths = mutableListOf<String>()
        val loads = mutableListOf<DirectoryLoad>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            webDavPickerDirectoryLoadFlow(
                pathFlow = pathFlow,
                refreshFlow = refreshFlow,
            ) { path ->
                loadedPaths += path
                emptyChildren()
            }.onEach { (_, load) ->
                if (load is Loadable.Ok) {
                    firstLoadCompleted.complete(Unit)
                }
            }.take(4).toList(loads)
        }

        firstLoadCompleted.await()
        refreshFlow.value += 1
        job.join()

        assertEquals(listOf("folder", "folder"), loadedPaths)
        assertEquals(
            listOf(
                Loadable.Loading::class,
                Loadable.Ok::class,
                Loadable.Loading::class,
                Loadable.Ok::class,
            ),
            loads.map { (_, load) -> load::class },
        )
    }
}

private typealias DirectoryLoad =
        Pair<String, Loadable<Either<Throwable, List<ListWebDavDirectory.Child>>>>

private fun emptyChildren(): Either<Throwable, List<ListWebDavDirectory.Child>> =
    emptyList<ListWebDavDirectory.Child>().right()

private fun child(
    name: String,
    isCollection: Boolean = false,
) = ListWebDavDirectory.Child(
    path = name,
    name = name,
    isCollection = isCollection,
    size = null,
    lastModified = null,
)

private fun pickerItem(
    name: String,
    isCollection: Boolean = false,
) = WebDavPickerState.Item(
    key = name,
    name = name,
    isCollection = isCollection,
    size = null,
    onClick = null,
)
