package com.artemchep.keyguard.common.service.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.io.asInputStream
import kotlinx.io.write

class AndroidTreeBackupObjectStoreTest {
    @Test
    fun `create or replace replaces existing object`() = runTest {
        val client = FakeAndroidTreeDocumentClient()
        val store = AndroidTreeBackupObjectStore(client)
        val key = BackupObjectKey("repo.zip")
        client.putFile("repo.zip", "old".encodeToByteArray())

        val info = store.write(key) { sink ->
            sink.write("new".encodeToByteArray())
        }

        assertEquals(key, info.key)
        assertContentEquals("new".encodeToByteArray(), store.readAll(key))
        assertEquals(
            listOf("repo.zip"),
            client.childNames(""),
        )
    }

    @Test
    fun `create or replace preserves existing object when publish rename fails`() = runTest {
        val client = FakeAndroidTreeDocumentClient()
        val store = AndroidTreeBackupObjectStore(client)
        val key = BackupObjectKey("repo.zip")
        val cause = IOException("failed to publish replacement")
        client.putFile(key.value, "old".encodeToByteArray())
        client.onRenamePath = { fromPath, displayName ->
            if (fromPath.isTempReplacementFor("repo.zip") && displayName == "repo.zip") {
                throw cause
            }
            true
        }

        val error = assertFailsWith<BackupObjectStoreException.Transient> {
            store.write(key) { sink ->
                sink.write("new".encodeToByteArray())
            }
        }

        assertEquals(BackupObjectStoreOperation.Write, error.operation)
        assertEquals(key, error.key)
        assertEquals(cause, error.cause)
        assertContentEquals("old".encodeToByteArray(), store.readAll(key))
        assertEquals(
            listOf("repo.zip"),
            client.childNames(""),
        )
    }

    @Test
    fun `create or replace preserves existing object when staging fails`() = runTest {
        val client = FakeAndroidTreeDocumentClient()
        val store = AndroidTreeBackupObjectStore(client)
        val key = BackupObjectKey("repo.zip")
        client.putFile(key.value, "old".encodeToByteArray())
        client.onRenamePath = { fromPath, displayName ->
            !(fromPath == "repo.zip" && displayName.endsWith(".old.tmp"))
        }

        val error = assertFailsWith<BackupObjectStoreException.Transient> {
            store.write(key) { sink ->
                sink.write("new".encodeToByteArray())
            }
        }

        assertEquals(BackupObjectStoreOperation.Write, error.operation)
        assertEquals(key, error.key)
        assertContentEquals("old".encodeToByteArray(), store.readAll(key))
        assertEquals(
            listOf("repo.zip"),
            client.childNames(""),
        )
    }

    @Test
    fun `publish rename returning null preserves existing object`() = runTest {
        val client = FakeAndroidTreeDocumentClient()
        val store = AndroidTreeBackupObjectStore(client)
        val key = BackupObjectKey("repo.zip")
        client.putFile(key.value, "old".encodeToByteArray())
        client.onRenamePath = { fromPath, displayName ->
            !(fromPath.isTempReplacementFor("repo.zip") && displayName == "repo.zip")
        }

        val error = assertFailsWith<BackupObjectStoreException.Transient> {
            store.write(key) { sink ->
                sink.write("new".encodeToByteArray())
            }
        }

        assertEquals(BackupObjectStoreOperation.Write, error.operation)
        assertEquals(key, error.key)
        assertContentEquals("old".encodeToByteArray(), store.readAll(key))
        assertEquals(
            listOf("repo.zip"),
            client.childNames(""),
        )
    }

    @Test
    fun `write rejects create file display name mismatch`() = runTest {
        val client = FakeAndroidTreeDocumentClient()
        val store = AndroidTreeBackupObjectStore(client)
        val key = BackupObjectKey("repo.zip")
        client.onCreateFileName = { _, displayName -> "$displayName (1)" }

        val error = assertFailsWith<BackupObjectStoreException.Transient> {
            store.write(key) { sink ->
                sink.write("payload".encodeToByteArray())
            }
        }

        assertEquals(BackupObjectStoreOperation.Write, error.operation)
        assertEquals(key, error.key)
        assertFalse(client.exists(key.value))
        assertTrue(client.childNames("").isEmpty())
    }

    @Test
    fun `write rejects create directory display name mismatch`() = runTest {
        val client = FakeAndroidTreeDocumentClient()
        val store = AndroidTreeBackupObjectStore(client)
        val key = BackupObjectKey("snapshots/snapshot-1.zip")
        client.onCreateDirectoryName = { parentPath, displayName ->
            if (parentPath.isEmpty() && displayName == "snapshots") {
                "$displayName (1)"
            } else {
                displayName
            }
        }

        val error = assertFailsWith<BackupObjectStoreException.Transient> {
            store.write(key) { sink ->
                sink.write("payload".encodeToByteArray())
            }
        }

        assertEquals(BackupObjectStoreOperation.Write, error.operation)
        assertEquals(key, error.key)
        assertFalse(client.exists(key.value))
        assertTrue(client.childNames("").isEmpty())
    }

    @Test
    fun `write refuses non-directory parent path conflict`() = runTest {
        val client = FakeAndroidTreeDocumentClient()
        val store = AndroidTreeBackupObjectStore(client)
        val key = BackupObjectKey("snapshots/snapshot-1.zip")
        client.putFile("snapshots", "payload".encodeToByteArray())

        val error = assertFailsWith<BackupObjectStoreException.AlreadyExists> {
            store.write(key) { sink ->
                sink.write("new".encodeToByteArray())
            }
        }

        assertEquals(key, error.key)
        assertEquals(
            listOf("snapshots"),
            client.childNames(""),
        )
    }

    @Test
    fun `delete ignores directory keys`() = runTest {
        val client = FakeAndroidTreeDocumentClient()
        val store = AndroidTreeBackupObjectStore(client)
        client.putFile("snapshots/snapshot-1.zip", "payload".encodeToByteArray())

        store.delete(BackupObjectKey("snapshots"))

        assertTrue(client.exists("snapshots"))
        assertTrue(client.exists("snapshots/snapshot-1.zip"))
    }

    @Test
    fun `child listing failure is transient and does not delete parent directory`() = runTest {
        val client = FakeAndroidTreeDocumentClient()
        val store = AndroidTreeBackupObjectStore(client)
        val key = BackupObjectKey("snapshots/snapshot-1.zip")
        client.putFile(key.value, "payload".encodeToByteArray())
        client.onDeletePath = { deletedPath ->
            if (deletedPath == key.value) {
                client.failListChildrenAt += "snapshots"
            }
        }

        val error = assertFailsWith<BackupObjectStoreException.Transient> {
            store.delete(key)
        }

        assertEquals(BackupObjectStoreOperation.Delete, error.operation)
        assertFalse(client.exists(key.value))
        assertTrue(client.exists("snapshots"))
    }

    @Test
    fun `null input stream is transient`() = runTest {
        val client = FakeAndroidTreeDocumentClient()
        val store = AndroidTreeBackupObjectStore(client)
        val key = BackupObjectKey("repo.zip")
        client.putFile(key.value, "payload".encodeToByteArray())
        client.nullInputAt += key.value

        val error = assertFailsWith<BackupObjectStoreException.Transient> {
            store.read(key)
        }

        assertEquals(BackupObjectStoreOperation.Read, error.operation)
        assertEquals(key, error.key)
    }

    @Test
    fun `streaming read failure is transient`() = runTest {
        val client = FakeAndroidTreeDocumentClient()
        val store = AndroidTreeBackupObjectStore(client)
        val key = BackupObjectKey("repo.zip")
        val cause = IOException("failed to read ${key.value}")
        client.putFile(key.value, "payload".encodeToByteArray())
        client.inputReadFailuresAt[key.value] = cause

        val error = assertFailsWith<BackupObjectStoreException.Transient> {
            store.readAll(key)
        }

        assertEquals(BackupObjectStoreOperation.Read, error.operation)
        assertEquals(key, error.key)
        assertEquals(cause, error.cause)
    }

    @Test
    fun `streaming close failure is transient`() = runTest {
        val client = FakeAndroidTreeDocumentClient()
        val store = AndroidTreeBackupObjectStore(client)
        val key = BackupObjectKey("repo.zip")
        val cause = IOException("failed to close ${key.value}")
        client.putFile(key.value, "payload".encodeToByteArray())
        client.inputCloseFailuresAt[key.value] = cause

        val source = store.read(key)
        assertContentEquals("payload".encodeToByteArray(), source.asInputStream().readBytes())
        val error = assertFailsWith<BackupObjectStoreException.Transient> {
            source.close()
        }

        assertEquals(BackupObjectStoreOperation.Read, error.operation)
        assertEquals(key, error.key)
        assertEquals(cause, error.cause)
    }

    @Test
    fun `null output stream is transient and cleans up temp file`() = runTest {
        val client = FakeAndroidTreeDocumentClient()
        val store = AndroidTreeBackupObjectStore(client)
        val key = BackupObjectKey("repo.zip")
        client.nullOutputForTempFiles = true

        val error = assertFailsWith<BackupObjectStoreException.Transient> {
            store.write(key) { sink ->
                sink.write("payload".encodeToByteArray())
            }
        }

        assertEquals(BackupObjectStoreOperation.Write, error.operation)
        assertEquals(key, error.key)
        assertFalse(client.exists(key.value))
        assertTrue(client.childNames("").none { it.endsWith(".tmp") })
    }

    @Test
    fun `list failure is transient`() = runTest {
        val client = FakeAndroidTreeDocumentClient()
        val store = AndroidTreeBackupObjectStore(client)
        client.failListChildrenAt += ""

        val error = assertFailsWith<BackupObjectStoreException.Transient> {
            store.list(BackupObjectKeyPrefix(""))
        }

        assertEquals(BackupObjectStoreOperation.List, error.operation)
    }

    @Test
    fun `provider runtime failure is transient`() = runTest {
        val client = FakeAndroidTreeDocumentClient()
        val store = AndroidTreeBackupObjectStore(client)
        val cause = IllegalStateException("provider crashed")
        client.providerListChildrenFailuresAt[""] = cause

        val error = assertFailsWith<BackupObjectStoreException.Transient> {
            store.list(BackupObjectKeyPrefix(""))
        }

        assertEquals(BackupObjectStoreOperation.List, error.operation)
        assertEquals(cause, error.cause)
    }

    @Test
    fun `unsupported provider runtime failure is permission denied`() = runTest {
        val client = FakeAndroidTreeDocumentClient()
        val store = AndroidTreeBackupObjectStore(client)
        val key = BackupObjectKey("repo.zip")
        val cause = UnsupportedOperationException("Create not supported")
        client.providerCreateFileFailure = cause

        val error = assertFailsWith<BackupObjectStoreException.PermissionDenied> {
            store.write(key) { sink ->
                sink.write("payload".encodeToByteArray())
            }
        }

        assertEquals(BackupObjectStoreOperation.Write, error.operation)
        assertEquals(key, error.key)
        assertEquals(cause, error.cause)
    }

    private suspend fun AndroidTreeBackupObjectStore.readAll(
        key: BackupObjectKey,
    ): ByteArray = read(key).use { source ->
        source.asInputStream().readBytes()
    }
}

private class FakeAndroidTreeDocumentClient : AndroidTreeDocumentClient {
    private val nodes = linkedMapOf(
        ROOT_ID to Node(
            id = ROOT_ID,
            parentId = null,
            name = "",
            isDirectory = true,
        ),
    )
    private var nextId = 0

    val failListChildrenAt = mutableSetOf<String>()
    val providerListChildrenFailuresAt = mutableMapOf<String, RuntimeException>()
    val nullInputAt = mutableSetOf<String>()
    val inputReadFailuresAt = mutableMapOf<String, IOException>()
    val inputCloseFailuresAt = mutableMapOf<String, IOException>()
    val nullOutputAt = mutableSetOf<String>()
    var providerCreateFileFailure: RuntimeException? = null
    var nullOutputForTempFiles = false
    var onCreateDirectoryName: (String, String) -> String = { _, displayName -> displayName }
    var onCreateFileName: (String, String) -> String = { _, displayName -> displayName }
    var onDeletePath: (String) -> Unit = {}
    var onRenamePath: (String, String) -> Boolean = { _, _ -> true }

    override val root: AndroidTreeDocumentEntry
        get() = entry(ROOT_ID)

    override fun listChildren(
        parent: AndroidTreeDocumentEntry,
    ): List<AndroidTreeDocumentEntry> {
        val path = path(parent.id)
        if (path in failListChildrenAt) {
            throw IOException("failed to list $path")
        }
        providerListChildrenFailuresAt[path]?.let { cause ->
            throw AndroidTreeDocumentProviderException(cause)
        }
        return nodes
            .values
            .filter { it.parentId == parent.id }
            .map { entry(it.id) }
    }

    override fun createDirectory(
        parent: AndroidTreeDocumentEntry,
        displayName: String,
    ): AndroidTreeDocumentEntry = createNode(
        parentId = parent.id,
        name = onCreateDirectoryName(path(parent.id), displayName),
        isDirectory = true,
    )

    override fun createFile(
        parent: AndroidTreeDocumentEntry,
        mimeType: String,
        displayName: String,
    ): AndroidTreeDocumentEntry {
        providerCreateFileFailure?.let { cause ->
            throw AndroidTreeDocumentProviderException(cause)
        }
        return createNode(
            parentId = parent.id,
            name = onCreateFileName(path(parent.id), displayName),
            isDirectory = false,
            data = ByteArray(0),
        )
    }

    override fun deleteDocument(
        document: AndroidTreeDocumentEntry,
    ): Boolean {
        val path = path(document.id)
        onDeletePath(path)
        deleteRecursive(document.id)
        return true
    }

    override fun renameDocument(
        document: AndroidTreeDocumentEntry,
        displayName: String,
    ): AndroidTreeDocumentEntry? {
        val path = path(document.id)
        if (!onRenamePath(path, displayName)) {
            return null
        }
        node(document.id).name = displayName
        return entry(document.id)
    }

    override fun openInputStream(
        document: AndroidTreeDocumentEntry,
    ): InputStream? {
        val path = path(document.id)
        if (path in nullInputAt) {
            return null
        }
        inputReadFailuresAt[path]?.let { cause ->
            return FailingReadInputStream(cause)
        }
        val data = node(document.id).data ?: ByteArray(0)
        inputCloseFailuresAt[path]?.let { cause ->
            return FailingCloseInputStream(
                bytes = data,
                cause = cause,
            )
        }
        return ByteArrayInputStream(data)
    }

    override fun openOutputStream(
        document: AndroidTreeDocumentEntry,
        mode: String,
    ): OutputStream? {
        val node = node(document.id)
        if (path(document.id) in nullOutputAt) {
            return null
        }
        if (nullOutputForTempFiles && node.name.endsWith(".tmp")) {
            return null
        }
        return object : ByteArrayOutputStream() {
            override fun close() {
                super.close()
                node.data = toByteArray()
            }
        }
    }

    fun putFile(
        path: String,
        data: ByteArray,
    ) {
        val parts = path.split('/')
        val parent = ensureDirectory(parts.dropLast(1))
        createNode(
            parentId = parent.id,
            name = parts.last(),
            isDirectory = false,
            data = data,
        )
    }

    fun exists(
        path: String,
    ): Boolean = resolve(path) != null

    fun childNames(
        path: String,
    ): List<String> {
        val parent = resolve(path) ?: return emptyList()
        return nodes
            .values
            .filter { it.parentId == parent.id }
            .map { it.name }
    }

    private fun ensureDirectory(
        parts: List<String>,
    ): Node {
        var current = node(ROOT_ID)
        parts.forEach { part ->
            current = nodes
                .values
                .firstOrNull {
                    it.parentId == current.id &&
                            it.name == part &&
                            it.isDirectory
                }
                ?: createNode(
                    parentId = current.id,
                    name = part,
                    isDirectory = true,
                ).let { node(it.id) }
        }
        return current
    }

    private fun createNode(
        parentId: String,
        name: String,
        isDirectory: Boolean,
        data: ByteArray? = null,
    ): AndroidTreeDocumentEntry {
        val id = "node-${nextId++}"
        nodes[id] = Node(
            id = id,
            parentId = parentId,
            name = name,
            isDirectory = isDirectory,
            data = data,
        )
        return entry(id)
    }

    private fun deleteRecursive(
        id: String,
    ) {
        nodes
            .values
            .filter { it.parentId == id }
            .map { it.id }
            .forEach(::deleteRecursive)
        if (id != ROOT_ID) {
            nodes.remove(id)
        }
    }

    private fun resolve(
        path: String,
    ): Node? {
        if (path.isEmpty()) {
            return node(ROOT_ID)
        }
        var current = node(ROOT_ID)
        path.split('/').forEach { part ->
            current = nodes
                .values
                .firstOrNull {
                    it.parentId == current.id &&
                            it.name == part
                }
                ?: return null
        }
        return current
    }

    private fun path(
        id: String,
    ): String {
        if (id == ROOT_ID) {
            return ""
        }
        val names = mutableListOf<String>()
        var current = node(id)
        while (current.id != ROOT_ID) {
            names += current.name
            current = node(requireNotNull(current.parentId))
        }
        return names
            .asReversed()
            .joinToString("/")
    }

    private fun entry(
        id: String,
    ): AndroidTreeDocumentEntry {
        val node = node(id)
        return AndroidTreeDocumentEntry(
            id = node.id,
            uri = null,
            name = node.name,
            isDirectory = node.isDirectory,
            size = node.data?.size?.toLong(),
            updatedAt = Instant.fromEpochMilliseconds(1L),
        )
    }

    private fun node(
        id: String,
    ): Node = requireNotNull(nodes[id]) {
        "Missing fake document node $id."
    }

    private data class Node(
        val id: String,
        val parentId: String?,
        var name: String,
        val isDirectory: Boolean,
        var data: ByteArray? = null,
    )

    private companion object {
        const val ROOT_ID = "root"
    }
}

private class FailingReadInputStream(
    private val cause: IOException,
) : InputStream() {
    override fun read(): Int {
        throw cause
    }
}

private class FailingCloseInputStream(
    bytes: ByteArray,
    private val cause: IOException,
) : ByteArrayInputStream(bytes) {
    override fun close() {
        throw cause
    }
}

private fun String.isTempReplacementFor(
    fileName: String,
): Boolean = startsWith("$fileName.") &&
        endsWith(".tmp") &&
        !endsWith(".old.tmp")
