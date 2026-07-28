package com.artemchep.keyguard.common.service.download

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.artemchep.keyguard.common.exception.HttpException
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.Argon2Mode
import com.artemchep.keyguard.common.model.CryptoHashAlgorithm
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.download.scheduler.DownloadBackgroundScheduler
import com.artemchep.keyguard.common.service.download.store.DownloadFileStore
import com.artemchep.keyguard.common.service.text.impl.Base64ServiceImpl
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.common.util.CodeException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

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

    private fun createManager(
        repository: DownloadRepository = DownloadRepositoryInMemory(),
        fileStore: DownloadFileStore = FakeDownloadFileStore(),
        scheduler: DownloadBackgroundScheduler = RecordingDownloadBackgroundScheduler(),
        task: DownloadTask = FakeDownloadTask {
            flowOf(DownloadProgress.Complete("file://download-1".right()))
        },
        scope: CoroutineScope,
    ) = DownloadManagerImpl(
        windowCoroutineScope = TestWindowCoroutineScope(scope),
        downloadRepository = repository,
        downloadTask = task,
        downloadFileStore = fileStore,
        downloadBackgroundScheduler = scheduler,
        base64Service = Base64ServiceImpl(),
        cryptoGenerator = SharedDownloadManagerCryptoGenerator("download-1"),
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

private class FakeDownloadFileStore : DownloadFileStore {
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
