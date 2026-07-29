package com.artemchep.keyguard.common.service.download

import arrow.core.Either
import arrow.core.right
import com.artemchep.keyguard.common.model.DownloadAttachmentRequestData
import com.artemchep.keyguard.common.model.MasterKdfVersion
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.usecase.GetVaultSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

class DownloadAttachmentSourceLoaderTest {
    @Test
    fun `keepass request reports retryable failure while vault is unavailable`() = runTest {
        val loader = DownloadAttachmentSourceLoaderImpl(
            downloadTask = UnusedDownloadTask,
            getVaultSession = TestGetVaultSession(MasterSession.Empty()),
        )

        val complete = loader.fileLoader(
            request = keePassRequest(),
            writer = DownloadWriter.SinkWriter(Buffer()),
        ).last()

        val result = assertIs<DownloadProgress.Complete>(complete).result
        val error = assertIs<Either.Left<Throwable>>(result).value
        assertIs<DownloadAttachmentSessionUnavailableException>(error)
    }

    @Test
    fun `keepass request resolves loader from current vault session per collection`() = runTest {
        val firstLoader = RecordingKeePassAttachmentSourceLoader("first")
        val secondLoader = RecordingKeePassAttachmentSourceLoader("second")
        val vaultSession = TestGetVaultSession(session(firstLoader))
        val loader = DownloadAttachmentSourceLoaderImpl(
            downloadTask = UnusedDownloadTask,
            getVaultSession = vaultSession,
        )
        val request = keePassRequest()
        val writer = DownloadWriter.SinkWriter(Buffer())

        val firstResult = loader.fileLoader(request, writer).last()
        vaultSession.value = session(secondLoader)
        val secondResult = loader.fileLoader(request, writer).last()

        assertEquals("first", assertCompleteRight(firstResult))
        assertEquals("second", assertCompleteRight(secondResult))
        assertEquals(listOf(request.source), firstLoader.sources)
        assertEquals(listOf(request.source), secondLoader.sources)
    }

    private fun session(
        loader: KeePassAttachmentSourceLoader,
    ) = MasterSession.Key(
        masterKey = MasterKey(
            version = MasterKdfVersion.V0,
            byteArray = byteArrayOf(1, 2, 3),
        ),
        di = DI {
            bindSingleton<KeePassAttachmentSourceLoader> {
                loader
            }
        },
        origin = MasterSession.Key.Persisted,
        createdAt = Instant.fromEpochMilliseconds(1L),
    )

    private fun assertCompleteRight(progress: DownloadProgress): String? {
        val complete = assertIs<DownloadProgress.Complete>(progress)
        return assertIs<Either.Right<String?>>(complete.result).value
    }
}

private fun keePassRequest() = DownloadAttachmentRequestData(
    localCipherId = "local-cipher",
    remoteCipherId = "remote-cipher",
    attachmentId = "attachment",
    source = DownloadAttachmentRequestData.KeePassSource(
        hashRef = "hashref://content-hash",
        expectedSize = 42L,
    ),
    name = "attachment.bin",
    encryptionKey = null,
)

private class TestGetVaultSession(
    initialValue: MasterSession,
) : GetVaultSession {
    private val state = MutableStateFlow(initialValue)

    var value: MasterSession
        get() = state.value
        set(value) {
            state.value = value
        }

    override val valueOrNull: MasterSession
        get() = state.value

    override fun invoke(): Flow<MasterSession> = state
}

private class RecordingKeePassAttachmentSourceLoader(
    private val result: String,
) : KeePassAttachmentSourceLoader {
    val sources = mutableListOf<DownloadAttachmentRequestData.KeePassSource>()

    override fun fileLoader(
        request: DownloadAttachmentRequestData,
        source: DownloadAttachmentRequestData.KeePassSource,
        writer: DownloadWriter,
    ): Flow<DownloadProgress> {
        sources += source
        return flowOf(DownloadProgress.Complete(result.right()))
    }
}

private data object UnusedDownloadTask : DownloadTask {
    override fun fileLoader(
        data: ByteArray,
        key: ByteArray?,
        writer: DownloadWriter,
    ): Flow<DownloadProgress> = error("Direct download is not expected.")

    override fun fileLoader(
        url: String,
        key: ByteArray?,
        writer: DownloadWriter,
    ): Flow<DownloadProgress> = error("URL download is not expected.")
}
