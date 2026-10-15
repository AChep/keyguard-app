@file:OptIn(com.artemchep.keyguard.util.io.InternalKeyguardIoApi::class)

package com.artemchep.keyguard.feature.attachmentpreview

import com.artemchep.keyguard.test.testCopyText
import com.artemchep.keyguard.util.zip.ZipArchive
import com.artemchep.keyguard.util.zip.ZipConfig
import com.artemchep.keyguard.util.zip.ZipEntry
import com.artemchep.keyguard.util.zip.ZipArchiveException
import com.artemchep.keyguard.util.zip.createZipService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runTest
import com.artemchep.keyguard.util.io.scratch.PrivateTemporaryStorage
import kotlinx.io.IOException
import kotlinx.io.RawSource
import kotlinx.io.RawSink
import kotlinx.io.readString
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AttachmentArchiveSessionTest {
    @Test
    fun foldersSortFirstAndBackRestoresTheFolderWithoutReloading() = runTest {
        val archive = zip(listOf("z.txt", "folder/b.txt", "folder/a.txt", "inner.zip"))
        val session = AttachmentArchiveSession(backgroundScope, "test.zip", archive, testCopyText(), onError = {})
        try {
            val root = assertIs<AttachmentPreviewContent.Archive>(session.state.value.content)
            assertEquals(listOf("folder", "inner.zip", "z.txt"), root.items.map { it.name })
            assertTrue(root.breadcrumbs.isEmpty())
            assertNull(root.items[1].onOpen)
            assertNotNull(root.items[1].onSave)
            root.items.first().onOpen!!()
            val folder = assertIs<AttachmentPreviewContent.Archive>(session.state.value.content)
            assertEquals("folder", folder.path)
            assertEquals(listOf("a.txt", "b.txt"), folder.items.map { it.name })
            assertEquals(listOf("test.zip", "folder"), folder.breadcrumbs.map { it.name })
            assertNull(folder.breadcrumbs.last().onClick)
            folder.items.first().onOpen!!()
            val preview = session.state.first { !it.busy && it.content is AttachmentPreviewContent.Text }
            assertEquals("a.txt", preview.fileName)
            assertEquals("folder/a.txt", assertIs<AttachmentPreviewContent.Text>(preview.content).text)
            preview.onBack!!()
            assertEquals("folder", assertIs<AttachmentPreviewContent.Archive>(session.state.value.content).path)
            session.state.value.onBack!!()
            assertEquals("", assertIs<AttachmentPreviewContent.Archive>(session.state.value.content).path)
        } finally { session.close() }
    }

    @Test
    fun breadcrumbsOpenTheirFolders() = runTest {
        val archive = zip(listOf("one/two/file.txt"))
        val session = AttachmentArchiveSession(backgroundScope, "test.zip", archive, testCopyText(), onError = {})
        try {
            val root = assertIs<AttachmentPreviewContent.Archive>(session.state.value.content)
            root.items.single().onOpen!!()
            assertIs<AttachmentPreviewContent.Archive>(session.state.value.content).items.single().onOpen!!()
            val nested = assertIs<AttachmentPreviewContent.Archive>(session.state.value.content)
            assertEquals("one/two", nested.path)
            assertEquals(listOf("test.zip", "one", "two"), nested.breadcrumbs.map { it.name })
            nested.breadcrumbs[1].onClick!!()
            assertEquals("one", assertIs<AttachmentPreviewContent.Archive>(session.state.value.content).path)
            assertIs<AttachmentPreviewContent.Archive>(session.state.value.content).breadcrumbs.first().onClick!!()
            assertEquals("", assertIs<AttachmentPreviewContent.Archive>(session.state.value.content).path)
        } finally { session.close() }
    }

    @Test
    fun passwordRetryAndDismissalKeepTheArchiveOpen() = runTest {
        val session = AttachmentArchiveSession(
            backgroundScope,
            "test.zip",
            zip(listOf("secret.txt"), "secret"),
            testCopyText(),
            onError = {},
        )
        try {
            val content = assertIs<AttachmentPreviewContent.Archive>(session.state.value.content)
            content.items.single().onOpen!!()
            assertNotNull(session.state.value.password).onSubmit("wrong")
            val wrong = session.state.first { !it.busy && it.password?.incorrect == true }
            wrong.password!!.onCancel()
            assertNull(session.state.value.password)
            content.items.single().onOpen!!()
            session.state.value.password!!.onSubmit("secret")
            val ready = session.state.first { !it.busy && it.content is AttachmentPreviewContent.Text }
            assertIs<AttachmentPreviewContent.Text>(ready.content)
        } finally { session.close() }
    }

    @Test
    fun implicitFolderCountIsBoundedAndUnsafePathsAreNotActions() = runTest {
        zip(listOf("../outside.txt", "good.txt")).use { archive ->
            val unsafe = archiveTree(archive.entries).getValue("").first { it.name == "../outside.txt" }
            assertEquals(false, assertIs<ArchiveNode.File>(unsafe).entry.isSafe)
        }
        zip((0 until 5001).map { "folder$it/a.txt" }).use { archive ->
            assertFailsWith<ZipArchiveException> { archiveTree(archive.entries) }
        }
    }

    @Test
    fun outputNamesAreBasenamesAndByteBounded() {
        assertEquals("notes.txt", archiveExportName("../../notes.txt"))
        assertEquals("attachment", archiveExportName(".."))
        assertTrue(archiveExportName("я".repeat(200)).encodeToByteArray().size <= 180)
        assertEquals("a".repeat(178), archiveExportName("a".repeat(178) + "🙂"))
        assertEquals("🙂".repeat(45), archiveExportName("🙂".repeat(50)))
    }

    @Test
    fun savingStagesOnlyOnRequestAndReleasesTheVerifiedExport() = runTest {
        val storage = TestExportStorage()
        var stages = 0
        val session = AttachmentArchiveSession(
            backgroundScope, "test.zip", zip(listOf("file.txt")), testCopyText(), onError = {},
            storageFactory = { stages++; storage },
        )
        val content = assertIs<AttachmentPreviewContent.Archive>(session.state.value.content)
        assertEquals(0, stages)
        try {
            content.items.single().onSave!!()
            val export = session.state.first { !it.busy && it.export != null }.export!!
            assertEquals(1, stages)
            assertTrue(storage.sealed)
            val output = Buffer()
            export.writeTo(output)
            assertEquals("file.txt", output.readString())
            export.finish(AttachmentPreviewExportOutcome.Saved)
            session.state.first { it.export == null }
            assertTrue(storage.closed)
        } finally { session.close() }
        content.items.single().onSave!!()
        assertEquals(1, stages)
    }

    @Test
    fun closingReleasesPendingExportsAndIgnoresLateCallbacksEvenWhenCleanupFails() = runTest {
        val storage = TestExportStorage(failOnClose = true)
        val session = AttachmentArchiveSession(
            backgroundScope, "test.zip", zip(listOf("secret.txt"), "secret"), testCopyText(), onError = {},
            storageFactory = { storage },
        )
        val content = assertIs<AttachmentPreviewContent.Archive>(session.state.value.content)
        content.items.single().onSave!!()
        val prompt = assertNotNull(session.state.value.password)
        prompt.onSubmit("secret")
        val export = session.state.first { !it.busy && it.export != null }.export!!
        assertFailsWith<IOException> { session.close() }
        assertTrue(storage.closed)
        assertFailsWith<IllegalStateException> { content.source.openEntry(0, "secret", 100) }
        val closedState = session.state.value
        prompt.onSubmit("secret")
        prompt.onCancel()
        export.finish(AttachmentPreviewExportOutcome.Failed)
        yield()
        assertEquals(closedState, session.state.value)
        assertFailsWith<IllegalStateException> { export.writeTo(Buffer()) }
    }

    @Test
    fun closingBeforeAnEntryStartsCancelsWorkAndIgnoresItsCallbacks() = runTest {
        val session = AttachmentArchiveSession(
            backgroundScope, "test.zip", zip(listOf("file.txt")), testCopyText(), onError = {},
        )
        val root = assertIs<AttachmentPreviewContent.Archive>(session.state.value.content)
        root.items.single().onOpen!!()
        session.close()
        assertNull(session.state.value.export)
        root.items.single().onOpen!!()
        assertIs<AttachmentPreviewContent.Error>(session.state.value.content)
    }

    @Test
    fun cancellationBetweenChunksRemovesStagingAndKeepsBrowsingAvailable() = runTest {
        lateinit var session: AttachmentArchiveSession
        val storage = TestExportStorage { session.state.value.onCancel?.invoke() }
        val errors = mutableListOf<AttachmentPreviewError>()
        session = AttachmentArchiveSession(
            backgroundScope,
            "test.zip",
            zip(listOf("file.txt"), content = "a".repeat(32_768)),
            testCopyText(),
            onError = { errors += it },
            storageFactory = { storage },
        )
        try {
            val root = assertIs<AttachmentPreviewContent.Archive>(session.state.value.content)
            root.items.single().onSave!!()
            storage.closedSignal.await()
            val ready = session.state.first { !it.busy }
            assertNull(ready.export)
            assertTrue(errors.isEmpty())
            assertTrue(!storage.sealed)
            root.items.single().onOpen!!()
            session.state.first { it.content is AttachmentPreviewContent.Text && !it.busy }
        } finally { session.close() }
    }

    @Test
    fun cancelledExportReleasesStagingWithoutReportingFailure() = runTest {
        val storage = TestExportStorage()
        val errors = mutableListOf<AttachmentPreviewError>()
        val session = AttachmentArchiveSession(
            backgroundScope, "test.zip", zip(listOf("file.txt")), testCopyText(),
            onError = { errors += it }, storageFactory = { storage },
        )
        try {
            val root = assertIs<AttachmentPreviewContent.Archive>(session.state.value.content)
            root.items.single().onSave!!()
            val export = session.state.first { !it.busy && it.export != null }.export!!
            export.finish(AttachmentPreviewExportOutcome.Cancelled)
            session.state.first { it.export == null }
            assertTrue(storage.closed)
            assertTrue(errors.isEmpty())
        } finally { session.close() }
    }

    @Test
    fun exportFailureReleasesStagingAndReportsAnError() = runTest {
        val storage = TestExportStorage()
        val error = CompletableDeferred<AttachmentPreviewError>()
        val session = AttachmentArchiveSession(
            backgroundScope, "test.zip", zip(listOf("file.txt")), testCopyText(),
            onError = { error.complete(it) },
            storageFactory = { storage },
        )
        try {
            val root = assertIs<AttachmentPreviewContent.Archive>(session.state.value.content)
            root.items.single().onSave!!()
            val export = session.state.first { !it.busy && it.export != null }.export!!
            export.finish(AttachmentPreviewExportOutcome.Failed)
            session.state.first { it.export == null }
            assertEquals(AttachmentPreviewError.ArchiveExportFailed, error.await())
            assertTrue(storage.closed)
        } finally { session.close() }
    }

    private suspend fun zip(names: List<String>, password: String? = null, content: String? = null): ZipArchive {
        val output = Buffer()
        createZipService().zip(output, ZipConfig(password?.let(ZipConfig::Encryption)), names.map { name ->
            ZipEntry(name, ZipEntry.Data.In { Buffer().apply { write((content ?: name).encodeToByteArray()) } })
        })
        return ZipArchive(output.readByteArray())
    }
}

private class TestExportStorage(
    private val failOnClose: Boolean = false,
    private val onWrite: () -> Unit = {},
) : PrivateTemporaryStorage {
    private val output = Buffer()
    private var bytes = byteArrayOf()
    var sealed = false
    var closed = false
    val closedSignal = CompletableDeferred<Unit>()
    override fun sink(): RawSink = object : RawSink {
        override fun write(source: Buffer, byteCount: Long) {
            output.write(source, byteCount)
            onWrite()
        }
        override fun flush() = Unit
        override fun close() = Unit
    }
    override fun sealForReading() {
        bytes = output.readByteArray()
        sealed = true
    }
    override fun source(): RawSource {
        check(sealed && !closed)
        return Buffer().apply { write(bytes) }
    }
    override fun close() {
        bytes.fill(0)
        closed = true
        closedSignal.complete(Unit)
        if (failOnClose) throw IOException("Temporary storage cleanup failed")
    }
}
