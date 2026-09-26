package com.artemchep.keyguard.common.service.download

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.artemchep.keyguard.common.exception.HttpException
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.Argon2Mode
import com.artemchep.keyguard.common.model.CryptoHashAlgorithm
import com.artemchep.keyguard.common.model.DownloadAttachmentRequestData
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.download.scheduler.DownloadBackgroundScheduler
import com.artemchep.keyguard.common.service.download.store.DownloadFileStore
import com.artemchep.keyguard.common.service.text.impl.Base64ServiceImpl
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.common.util.CodeException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadManagerImplTest {
    @Test
    fun `queue creates metadata starts direct data download and schedules background work`() = runTest {
        val repository = DownloadRepositoryInMemory()
        val fileStore = FakeDownloadFileStore()
        val scheduler = RecordingDownloadBackgroundScheduler()
        val task = FakeDownloadTask {
            flowOf(
                DownloadProgress.Loading(downloaded = 1, total = 2),
                DownloadProgress.Complete("file://download-1".right()),
            )
        }
        val manager = createManager(
            repository = repository,
            fileStore = fileStore,
            scheduler = scheduler,
            task = task,
            scope = backgroundScope,
        )

        val queue = manager.queue(
            DownloadQueueRequest(
                tag = downloadTag(),
                source = DownloadQueueRequest.Source.Direct(
                    url = "attachment://direct",
                    urlIsOneTime = true,
                    data = "payload".encodeToByteArray(),
                ),
                name = "attachment.txt",
                key = byteArrayOf(1, 2, 3),
                scheduleBackground = true,
            ),
        )
        val complete = queue.flow
            .filterIsInstance<DownloadProgress.Complete>()
            .first()

        assertEquals("download-1", queue.info.id)
        assertEquals(listOf("download-1"), scheduler.enqueuedIds)
        assertEquals(1, task.dataRequests.size)
        assertEquals(0, task.urlRequests.size)
        assertEquals(listOf("download-1"), fileStore.writerIds)
        assertIs<Either.Right<String?>>(complete.result)
        assertEquals(null, repository.getById("download-1").bind()?.error)
    }

    @Test
    fun `queue reuses existing file without invoking download task`() = runTest {
        val repository = DownloadRepositoryInMemory()
        val fileStore = FakeDownloadFileStore().apply {
            existingIds += "download-1"
        }
        val task = FakeDownloadTask {
            error("Existing file should not invoke the download task.")
        }
        val manager = createManager(
            repository = repository,
            fileStore = fileStore,
            task = task,
            scope = backgroundScope,
        )

        val queue = manager.queue(
            downloadQueueRequest(),
        )
        val complete = queue.flow
            .filterIsInstance<DownloadProgress.Complete>()
            .first()

        val result = assertIs<Either.Right<String?>>(complete.result)
        assertEquals("file://download-1/attachment.txt", result.value)
        assertEquals(0, task.dataRequests.size)
        assertEquals(0, task.urlRequests.size)
    }

    @Test
    fun `failed download stores retry error on metadata`() = runTest {
        val repository = DownloadRepositoryInMemory()
        val error = CodeException(code = 503, description = "unavailable")
        val manager = createManager(
            repository = repository,
            task = FakeDownloadTask {
                flowOf(DownloadProgress.Complete(error.left()))
            },
            scope = backgroundScope,
        )

        val queue = manager.queue(
            downloadQueueRequest(attempt = 2),
        )
        queue.flow
            .filterIsInstance<DownloadProgress.Complete>()
            .first()

        assertEquals(
            DownloadInfoEntity.Error(
                code = 503,
                attempt = 3,
                message = null,
            ),
            repository.getById(queue.info.id).bind()?.error,
        )
    }

    @Test
    fun `failed download stores http exception status code on metadata`() = runTest {
        val repository = DownloadRepositoryInMemory()
        val error = HttpException(
            statusCode = HttpStatusCode.NotFound,
            m = "missing",
            e = null,
        )
        val manager = createManager(
            repository = repository,
            task = FakeDownloadTask {
                flowOf(DownloadProgress.Complete(error.left()))
            },
            scope = backgroundScope,
        )

        val queue = manager.queue(
            downloadQueueRequest(),
        )
        queue.flow
            .filterIsInstance<DownloadProgress.Complete>()
            .first()

        assertEquals(
            DownloadInfoEntity.Error(
                code = HttpStatusCode.NotFound.value,
                attempt = 1,
                message = "missing",
            ),
            repository.getById(queue.info.id).bind()?.error,
        )
    }

    @Test
    fun `unavailable vault session does not consume restart attempt budget`() = runTest {
        val repository = DownloadRepositoryInMemory()
        val manager = createManager(
            repository = repository,
            task = FakeDownloadTask {
                flowOf(
                    DownloadProgress.Complete(
                        DownloadAttachmentSessionUnavailableException().left(),
                    ),
                )
            },
            scope = backgroundScope,
        )

        val queue = manager.queue(
            downloadQueueRequest(attempt = 3),
        )
        queue.flow
            .filterIsInstance<DownloadProgress.Complete>()
            .first()

        val error = repository.getById(queue.info.id).bind()?.error
        assertEquals(3, error?.attempt)
        assertEquals(true, error?.canRetry())
    }

    @Test
    fun `status falls back to stored downloaded file`() = runTest {
        val repository = DownloadRepositoryInMemory()
        val fileStore = FakeDownloadFileStore().apply {
            existingIds += "download-1"
        }
        val manager = createManager(
            repository = repository,
            fileStore = fileStore,
            scope = backgroundScope,
        )
        repository.put(downloadInfo()).bind()

        val status = manager.statusByDownloadId2("download-1")
            .first()

        val complete = assertIs<DownloadProgress.Complete>(status)
        val result = assertIs<Either.Right<String?>>(complete.result)
        assertEquals("file://download-1/attachment.txt", result.value)
    }

    @Test
    fun `remove by tag deletes metadata and stored file`() = runTest {
        val repository = DownloadRepositoryInMemory()
        val fileStore = FakeDownloadFileStore().apply {
            existingIds += "download-1"
        }
        val manager = createManager(
            repository = repository,
            fileStore = fileStore,
            scope = backgroundScope,
        )
        repository.put(downloadInfo()).bind()

        manager.removeByTag(downloadTag())

        assertEquals(null, repository.getById("download-1").bind())
        assertEquals(listOf("download-1"), fileStore.deletedIds)
    }

    @Test
    fun `remove by id awaits writer cleanup before deletion and requeue`() =
        assertRemovalWaitsForWriter(removeByTag = false)

    @Test
    fun `remove by tag awaits writer cleanup before deletion and requeue`() =
        assertRemovalWaitsForWriter(removeByTag = true)

    @Suppress("LongMethod") // Keep the gated removal/requeue scenario and its assertions together.
    private fun assertRemovalWaitsForWriter(removeByTag: Boolean) = runTest {
        val repository = DownloadRepositoryInMemory()
        val fileStore = FakeDownloadFileStore()
        val started = CompletableDeferred<Unit>()
        val cleanupStarted = CompletableDeferred<Unit>()
        val finishCleanup = CompletableDeferred<Unit>()
        var invocation = 0
        val manager = createManager(
            repository = repository,
            fileStore = fileStore,
            scope = backgroundScope,
            task = FakeDownloadTask {
                if (invocation++ == 0) {
                    flow {
                        emit(DownloadProgress.Loading(downloaded = 1, total = 2))
                        started.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            withContext(NonCancellable) {
                                cleanupStarted.complete(Unit)
                                finishCleanup.await()
                                // Model a commit that had already started when cancelled.
                                fileStore.existingIds += "download-1"
                            }
                        }
                    }
                } else {
                    flow {
                        fileStore.existingIds += "download-1"
                        emit(DownloadProgress.Complete("file://replacement".right()))
                    }
                }
            },
            // Reuse the ID deliberately, to exercise stale writer/cleanup protection.
            cryptoGenerator = SharedDownloadManagerCryptoGenerator("download-1", "download-1"),
        )
        manager.queue(downloadQueueRequest())
        started.await()

        val removal = async {
            if (removeByTag) {
                manager.removeByTag(downloadTag())
            } else {
                manager.removeByDownloadId("download-1")
            }
        }
        cleanupStarted.await()
        val replacement = async { manager.queue(downloadQueueRequest()) }
        runCurrent()

        assertFalse(removal.isCompleted)
        assertFalse(replacement.isCompleted)
        assertEquals(emptyList(), fileStore.deletedIds)
        assertEquals(listOf("download-1"), fileStore.writerIds)

        finishCleanup.complete(Unit)
        removal.await()
        val result = replacement.await().flow
            .filterIsInstance<DownloadProgress.Complete>()
            .first()

        assertEquals("file://replacement", assertIs<Either.Right<String?>>(result.result).value)
        assertEquals(listOf("download-1"), fileStore.deletedIds)
        assertEquals(setOf("download-1"), fileStore.existingIds)
        assertEquals(listOf("download-1", "download-1"), fileStore.writerIds)
        assertEquals(null, repository.getById("download-1").bind()?.error)
        manager.removeByDownloadId("download-1")
        assertIs<DownloadProgress.None>(manager.statusByDownloadId2("download-1").first())
        assertIs<DownloadProgress.None>(manager.statusByTag(downloadTag()).first())
    }

    @Test
    fun `requeue awaits previous writer before creating replacement`() = runTest {
        val fileStore = FakeDownloadFileStore()
        val started = CompletableDeferred<Unit>()
        val cleanupStarted = CompletableDeferred<Unit>()
        val finishCleanup = CompletableDeferred<Unit>()
        var invocation = 0
        val manager = createManager(
            fileStore = fileStore,
            scope = backgroundScope,
            task = FakeDownloadTask {
                if (invocation++ == 0) {
                    flow {
                        emit(DownloadProgress.Loading(downloaded = 1, total = 2))
                        started.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            withContext(NonCancellable) {
                                cleanupStarted.complete(Unit)
                                finishCleanup.await()
                            }
                        }
                    }
                } else {
                    flowOf(DownloadProgress.Complete("file://replacement".right()))
                }
            },
        )
        manager.queue(downloadQueueRequest())
        started.await()

        val replacement = async { manager.queue(downloadQueueRequest()) }
        cleanupStarted.await()
        assertFalse(replacement.isCompleted)
        assertEquals(listOf("download-1"), fileStore.writerIds)

        finishCleanup.complete(Unit)
        val result = replacement.await().flow
            .filterIsInstance<DownloadProgress.Complete>()
            .first()

        assertEquals("file://replacement", assertIs<Either.Right<String?>>(result.result).value)
        assertEquals(listOf("download-1", "download-1"), fileStore.writerIds)
    }

    @Test
    fun `restart reconstructs hashref as keepass source without http task`() = runTest {
        val requests = mutableListOf<DownloadAttachmentRequestData>()
        val sourceLoader = object : DownloadAttachmentSourceLoader {
            override fun fileLoader(
                request: DownloadAttachmentRequestData,
                writer: DownloadWriter,
            ): Flow<DownloadProgress> {
                requests += request
                return flowOf(DownloadProgress.Complete("file://download-1".right()))
            }
        }
        val manager = createManager(
            sourceLoader = sourceLoader,
            scope = backgroundScope,
        )
        val info = downloadInfo().copy(
            url = "hashref://restartable-content-hash",
            urlIsOneTime = false,
        )

        manager.queue(info).flow
            .filterIsInstance<DownloadProgress.Complete>()
            .first()

        val source = assertIs<DownloadAttachmentRequestData.KeePassSource>(
            requests.single().source,
        )
        assertEquals(info.url, source.hashRef)
        assertEquals(null, source.expectedSize)
    }

    @Test
    fun `other attachments can queue and cancel while a writer stops by id`() =
        assertIndependentDownloads(removeByTag = false)

    @Test
    fun `other attachments can queue and cancel while a writer stops by tag`() =
        assertIndependentDownloads(removeByTag = true)

    private fun assertIndependentDownloads(removeByTag: Boolean) = runTest {
        val first = GatedDownload()
        val second = GatedDownload().apply { finishCleanup.complete(Unit) }
        var invocation = 0
        val manager = createManager(
            scope = backgroundScope,
            task = FakeDownloadTask { if (invocation++ == 0) first.flow else second.flow },
        )
        manager.queue(downloadQueueRequest())
        first.started.await()
        val removal = async {
            if (removeByTag) manager.removeByTag(downloadTag())
            else manager.removeByDownloadId("download-1")
        }
        first.cleanupStarted.await()
        val otherTag = downloadTag().copy(attachmentId = "other")
        val otherQueue = async { manager.queue(downloadQueueRequest().copy(tag = otherTag)) }
        runCurrent()
        val queuedIndependently = otherQueue.isCompleted
        val otherRemoval = if (queuedIndependently) {
            second.started.await()
            async { manager.removeByTag(otherTag) }.also { runCurrent() }
        } else null
        val cancelledIndependently = otherRemoval?.isCompleted == true
        first.finishCleanup.complete(Unit)
        removal.await()
        otherQueue.await()
        otherRemoval?.await() ?: manager.removeByTag(otherTag)

        assertTrue(queuedIndependently, "Another attachment must queue before the first writer stops")
        assertTrue(cancelledIndependently, "Another attachment must cancel before the first writer stops")
    }

    @Test
    fun `slow file deletion does not hold up another attachment`() = runTest {
        val repository = DownloadRepositoryInMemory()
        repository.put(downloadInfo()).bind()
        val deleting = CompletableDeferred<Unit>()
        val finishDelete = CompletableDeferred<Unit>()
        val store = FakeDownloadFileStore(beforeDelete = {
            deleting.complete(Unit)
            finishDelete.await()
        })
        val manager = createManager(
            repository = repository,
            fileStore = store,
            scope = backgroundScope,
            cryptoGenerator = SharedDownloadManagerCryptoGenerator("download-2"),
        )
        val removal = async { manager.removeByDownloadId("download-1") }
        deleting.await()
        val otherQueue = async {
            manager.queue(downloadQueueRequest().copy(tag = downloadTag().copy(attachmentId = "other")))
        }
        runCurrent()
        val queuedIndependently = otherQueue.isCompleted
        assertTrue(repository.getById("download-1").bind() != null)
        finishDelete.complete(Unit)
        removal.await()
        otherQueue.await().flow.filterIsInstance<DownloadProgress.Complete>().first()

        assertTrue(queuedIndependently, "File deletion must not hold the shared repository lock")
        assertEquals(null, repository.getById("download-1").bind())
    }

    @Test
    fun `cancelled waiter and interrupted removal preserve serialization until writer stops`() = runTest {
        val first = GatedDownload()
        val store = FakeDownloadFileStore()
        var invocation = 0
        val manager = createManager(
            scope = backgroundScope,
            fileStore = store,
            task = FakeDownloadTask {
                if (invocation++ == 0) first.flow
                else flow {
                    store.existingIds += "download-1"
                    emit(DownloadProgress.Complete("file://replacement".right()))
                }
            },
        )
        manager.queue(downloadQueueRequest())
        first.started.await()
        val removal = async { manager.removeByDownloadId("download-1") }
        first.cleanupStarted.await()
        val waiter = async { manager.queue(downloadQueueRequest()) }
        runCurrent()
        waiter.cancelAndJoin()
        removal.cancelAndJoin()
        val replacement = async { manager.queue(downloadQueueRequest()) }
        runCurrent()
        assertFalse(replacement.isCompleted)
        assertEquals(listOf("download-1"), store.writerIds)
        assertEquals(emptyList(), store.deletedIds)
        first.finishCleanup.complete(Unit)
        val result = replacement.await().flow.filterIsInstance<DownloadProgress.Complete>().first()
        assertEquals("file://replacement", assertIs<Either.Right<String?>>(result.result).value)
        assertIs<DownloadProgress.Complete>(manager.statusByTag(downloadTag()).first())
        manager.removeByTag(downloadTag())
        assertIs<DownloadProgress.None>(manager.statusByTag(downloadTag()).first())
    }

    @Test
    fun `stale id removal cannot remove a replacement with the same tag`() = runTest {
        val repository = DownloadRepositoryInMemory()
        repository.put(downloadInfo()).bind()
        val deleting = CompletableDeferred<Unit>()
        val finishDelete = CompletableDeferred<Unit>()
        val store = FakeDownloadFileStore(beforeDelete = { info ->
            if (info.id == "download-1") {
                deleting.complete(Unit)
                finishDelete.await()
            }
        })
        val replacementDownload = GatedDownload().apply { finishCleanup.complete(Unit) }
        val manager = createManager(
            repository = repository,
            fileStore = store,
            scope = backgroundScope,
            task = FakeDownloadTask { replacementDownload.flow },
            cryptoGenerator = SharedDownloadManagerCryptoGenerator("download-2"),
        )
        val removal = async { manager.removeByTag(downloadTag()) }
        deleting.await()
        val replacement = async { manager.queue(downloadQueueRequest()) }
        runCurrent()
        val staleRemoval = async { manager.removeByDownloadId("download-1") }
        runCurrent()
        finishDelete.complete(Unit)
        removal.await()
        val queued = replacement.await()
        replacementDownload.started.await()
        staleRemoval.await()

        assertEquals("download-2", queued.info.id)
        assertEquals(listOf("download-1"), store.deletedIds)
        assertTrue(repository.getById("download-2").bind() != null)
        assertFalse(replacementDownload.cleanupStarted.isCompleted)
        manager.removeByDownloadId("download-2")
    }

    @Test
    fun `concurrent queues keep the same attachment serialized across lock handoffs`() = runTest {
        val first = GatedDownload()
        val second = GatedDownload()
        val scheduled = CompletableDeferred<Unit>()
        val finishScheduling = CompletableDeferred<Unit>()
        val store = FakeDownloadFileStore()
        var invocation = 0
        val manager = createManager(
            scope = backgroundScope,
            fileStore = store,
            scheduler = object : DownloadBackgroundScheduler {
                override suspend fun enqueue(downloadId: String) {
                    scheduled.complete(Unit)
                    finishScheduling.await()
                }
            },
            task = FakeDownloadTask {
                when (invocation++) {
                    0 -> first.flow
                    1 -> second.flow
                    else -> flow {
                        store.existingIds += "download-1"
                        emit(DownloadProgress.Complete("file://latest".right()))
                    }
                }
            },
        )
        val initial = async { manager.queue(downloadQueueRequest().copy(scheduleBackground = true)) }
        scheduled.await()
        first.started.await()
        val replacement = async { manager.queue(downloadQueueRequest()) }
        runCurrent()
        assertFalse(replacement.isCompleted)
        assertEquals(1, invocation)
        finishScheduling.complete(Unit)
        initial.await()
        first.cleanupStarted.await()
        // A new arrival must share the waiting operation's lock after the first
        // holder releases its registry reference.
        val latest = async { manager.queue(downloadQueueRequest()) }
        runCurrent()
        assertFalse(latest.isCompleted)
        assertEquals(1, invocation)
        first.finishCleanup.complete(Unit)
        replacement.await()
        second.cleanupStarted.await()
        assertFalse(latest.isCompleted)
        assertEquals(2, invocation)
        second.finishCleanup.complete(Unit)
        latest.await().flow.filterIsInstance<DownloadProgress.Complete>().first()
        assertEquals(3, invocation)
        assertIs<DownloadProgress.Complete>(manager.statusByDownloadId2("download-1").first())
        manager.removeByDownloadId("download-1")
    }

    @Test
    fun `failed deletion retains metadata and permits another removal`() = runTest {
        val repository = DownloadRepositoryInMemory()
        repository.put(downloadInfo()).bind()
        var attempts = 0
        val store = FakeDownloadFileStore(beforeDelete = {
            if (attempts++ == 0) error("Cannot delete file")
        })
        val manager = createManager(repository = repository, fileStore = store, scope = backgroundScope)
        assertFailsWith<IllegalStateException> { manager.removeByTag(downloadTag()) }
        assertTrue(repository.getById("download-1").bind() != null)
        manager.removeByDownloadId("download-1")
        assertEquals(null, repository.getById("download-1").bind())
        assertEquals(listOf("download-1"), store.deletedIds)
    }

    private fun createManager(
        repository: DownloadRepository = DownloadRepositoryInMemory(),
        fileStore: DownloadFileStore = FakeDownloadFileStore(),
        scheduler: DownloadBackgroundScheduler = RecordingDownloadBackgroundScheduler(),
        task: DownloadTask = FakeDownloadTask {
            flowOf(DownloadProgress.Complete("file://download-1".right()))
        },
        sourceLoader: DownloadAttachmentSourceLoader = task.asSourceLoader(),
        scope: CoroutineScope,
        cryptoGenerator: CryptoGenerator = SharedDownloadManagerCryptoGenerator("download-1"),
    ) = DownloadManagerImpl(
        windowCoroutineScope = TestWindowCoroutineScope(scope),
        downloadRepository = repository,
        sourceLoader = sourceLoader,
        downloadFileStore = fileStore,
        downloadBackgroundScheduler = scheduler,
        base64Service = Base64ServiceImpl(),
        cryptoGenerator = cryptoGenerator,
    )
}

private fun downloadTag() = DownloadInfoEntity.AttachmentDownloadTag(
    localCipherId = "local-cipher",
    remoteCipherId = "remote-cipher",
    attachmentId = "attachment",
)

private fun downloadQueueRequest(
    attempt: Int = 0,
) = DownloadQueueRequest(
    tag = downloadTag(),
    source = DownloadQueueRequest.Source.Url(
        url = "https://example.com/attachment",
        urlIsOneTime = false,
    ),
    name = "attachment.txt",
    attempt = attempt,
)

private fun downloadInfo() = DownloadInfoEntity(
    id = "download-1",
    localCipherId = "local-cipher",
    remoteCipherId = "remote-cipher",
    attachmentId = "attachment",
    url = "https://example.com/attachment",
    urlIsOneTime = false,
    name = "attachment.txt",
    createdDate = kotlin.time.Instant.fromEpochMilliseconds(0),
)

private data class TestWindowCoroutineScope(
    private val scope: CoroutineScope,
) : WindowCoroutineScope {
    override val coroutineContext: CoroutineContext
        get() = scope.coroutineContext
}

private class FakeDownloadFileStore(
    private val beforeDelete: suspend (DownloadInfoEntity) -> Unit = {},
) : DownloadFileStore {
    val existingIds = mutableSetOf<String>()
    val deletedIds = mutableListOf<String>()
    val writerIds = mutableListOf<String>()

    override suspend fun writer(info: DownloadInfoEntity): DownloadWriter {
        writerIds += info.id
        return DownloadWriter.SinkWriter(Buffer())
    }

    override suspend fun uri(info: DownloadInfoEntity): String =
        "file://${info.id}/${info.name}"

    override suspend fun exists(info: DownloadInfoEntity): Boolean =
        info.id in existingIds

    override suspend fun delete(info: DownloadInfoEntity): Boolean {
        beforeDelete(info)
        deletedIds += info.id
        existingIds -= info.id
        return true
    }
}

private class RecordingDownloadBackgroundScheduler : DownloadBackgroundScheduler {
    val enqueuedIds = mutableListOf<String>()

    override suspend fun enqueue(downloadId: String) {
        enqueuedIds += downloadId
    }
}

private class FakeDownloadTask(
    private val flowFactory: (DownloadWriter) -> Flow<DownloadProgress>,
) : DownloadTask {
    data class Request(
        val key: ByteArray?,
        val writer: DownloadWriter,
    )

    val dataRequests = mutableListOf<Request>()
    val urlRequests = mutableListOf<Request>()

    override fun fileLoader(
        data: ByteArray,
        key: ByteArray?,
        writer: DownloadWriter,
    ): Flow<DownloadProgress> {
        dataRequests += Request(key = key, writer = writer)
        return flowFactory(writer)
    }

    override fun fileLoader(
        url: String,
        key: ByteArray?,
        writer: DownloadWriter,
    ): Flow<DownloadProgress> {
        urlRequests += Request(key = key, writer = writer)
        return flowFactory(writer)
    }
}

private class SharedDownloadManagerCryptoGenerator(
    private vararg val uuids: String,
) : CryptoGenerator {
    private var uuidIndex = 0

    override fun uuid(): String =
        uuids.getOrElse(uuidIndex++) { "download-$uuidIndex" }

    override fun hkdf(
        seed: ByteArray,
        salt: ByteArray?,
        info: ByteArray?,
        length: Int,
    ): ByteArray = unsupported()

    override fun pbkdf2(
        seed: ByteArray,
        salt: ByteArray,
        iterations: Int,
        length: Int,
    ): ByteArray = unsupported()

    override fun argon2(
        mode: Argon2Mode,
        seed: ByteArray,
        salt: ByteArray,
        iterations: Int,
        memoryKb: Int,
        parallelism: Int,
    ): ByteArray = unsupported()

    override fun seed(length: Int): ByteArray = unsupported()

    override fun hmac(
        key: ByteArray,
        data: ByteArray,
        algorithm: CryptoHashAlgorithm,
    ): ByteArray = unsupported()

    override fun hashSha1(data: ByteArray): ByteArray = unsupported()

    override fun hashSha256(data: ByteArray): ByteArray = unsupported()

    override fun hashMd5(data: ByteArray): ByteArray = unsupported()

    override fun random(): Int = unsupported()

    override fun random(range: IntRange): Int = unsupported()

    private fun unsupported(): Nothing =
        error("Only uuid generation is expected in this test.")
}

private class GatedDownload {
    val started = CompletableDeferred<Unit>()
    val cleanupStarted = CompletableDeferred<Unit>()
    val finishCleanup = CompletableDeferred<Unit>()
    val flow: Flow<DownloadProgress> = flow {
        emit(DownloadProgress.Loading())
        started.complete(Unit)
        try {
            awaitCancellation()
        } finally {
            withContext(NonCancellable) {
                cleanupStarted.complete(Unit)
                finishCleanup.await()
            }
        }
    }
}
