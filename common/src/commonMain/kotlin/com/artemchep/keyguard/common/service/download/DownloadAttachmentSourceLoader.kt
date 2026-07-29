package com.artemchep.keyguard.common.service.download

import arrow.core.left
import arrow.core.right
import com.artemchep.keyguard.common.io.throwIfFatalOrCancellation
import com.artemchep.keyguard.common.model.DownloadAttachmentRequestData
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.common.service.keepass.DefaultKeePassAttachmentStorageFactory
import com.artemchep.keyguard.common.service.keepass.KeePassAttachmentReader
import com.artemchep.keyguard.common.service.staging.StagingSpoolFactory
import com.artemchep.keyguard.common.service.text.Base32Service
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.service.webdav.KtorWebDavClientFactory
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.provider.bitwarden.repository.BitwardenCipherRepository
import com.artemchep.keyguard.provider.bitwarden.repository.ServiceTokenRepository
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.kodein.di.DirectDI
import org.kodein.di.direct
import org.kodein.di.instance

interface DownloadAttachmentSourceLoader {
    fun fileLoader(
        request: DownloadAttachmentRequestData,
        writer: DownloadWriter,
    ): Flow<DownloadProgress>
}

internal class DownloadAttachmentSessionUnavailableException : IllegalStateException(
    "The vault session is not available.",
)

class DownloadAttachmentSourceLoaderImpl internal constructor(
    private val downloadTask: DownloadTask,
    private val getVaultSession: GetVaultSession,
) : DownloadAttachmentSourceLoader {
    constructor(directDI: DirectDI) : this(
        downloadTask = directDI.instance(),
        getVaultSession = directDI.instance(),
    )

    override fun fileLoader(
        request: DownloadAttachmentRequestData,
        writer: DownloadWriter,
    ): Flow<DownloadProgress> = when (val source = request.source) {
        is DownloadAttachmentRequestData.DirectSource -> downloadTask.fileLoader(
            data = source.data,
            key = request.encryptionKey,
            writer = writer,
        )

        is DownloadAttachmentRequestData.UrlSource -> downloadTask.fileLoader(
            url = source.url,
            key = request.encryptionKey,
            writer = writer,
        )

        is DownloadAttachmentRequestData.KeePassSource -> flow {
            when (val session = getVaultSession().first()) {
                is MasterSession.Key -> {
                    val loader = session.di.direct.instance<KeePassAttachmentSourceLoader>()
                    emitAll(
                        loader.fileLoader(
                            request = request,
                            source = source,
                            writer = writer,
                        ),
                    )
                }

                is MasterSession.Empty -> {
                    emit(DownloadProgress.Loading())
                    emit(
                        DownloadProgress.Complete(
                            DownloadAttachmentSessionUnavailableException().left(),
                        ),
                    )
                }
            }
        }
    }
}

internal interface KeePassAttachmentSourceLoader {
    fun fileLoader(
        request: DownloadAttachmentRequestData,
        source: DownloadAttachmentRequestData.KeePassSource,
        writer: DownloadWriter,
    ): Flow<DownloadProgress>
}

internal class KeePassAttachmentSourceLoaderImpl internal constructor(
    private val keePassSourceResolver: KeePassAttachmentSourceResolver,
    private val keePassAttachmentReader: KeePassAttachmentReader,
) : KeePassAttachmentSourceLoader {
    constructor(directDI: DirectDI) : this(
        keePassSourceResolver = KeePassAttachmentSourceResolverImpl(
            tokenRepository = directDI.instance<ServiceTokenRepository>(),
            cipherRepository = directDI.instance<BitwardenCipherRepository>(),
            base32Service = directDI.instance<Base32Service>(),
        ),
        keePassAttachmentReader = KeePassAttachmentReader(
            base64Service = directDI.instance<Base64Service>(),
            storageFactory = DefaultKeePassAttachmentStorageFactory(
                fileService = directDI.instance<FileService>(),
                webDavClientFactory = KtorWebDavClientFactory(
                    httpClient = directDI.instance<HttpClient>(),
                ),
            ),
            stagingSpoolFactory = directDI.instance<StagingSpoolFactory>(),
        ),
    )

    override fun fileLoader(
        request: DownloadAttachmentRequestData,
        source: DownloadAttachmentRequestData.KeePassSource,
        writer: DownloadWriter,
    ): Flow<DownloadProgress> = channelFlow {
        send(DownloadProgress.Loading())
        val downloadContext = currentCoroutineContext()
        val result = try {
            withContext(Dispatchers.IO) {
                keePassSourceResolver.resolve(request, source).use { resolved ->
                    keePassAttachmentReader.read(
                        token = resolved.token,
                        contentHash = resolved.contentHash,
                        expectedSize = resolved.expectedSize,
                    ).use { staged ->
                        trySend(
                            DownloadProgress.Loading(
                                downloaded = 0L,
                                total = staged.size,
                            ),
                        )
                        staged.source().use { verifiedSource ->
                            writer.writeVerifiedSource(
                                source = verifiedSource,
                                checkCancellation = downloadContext::ensureActive,
                                onProgress = { downloaded ->
                                    trySend(
                                        DownloadProgress.Loading(
                                            downloaded = downloaded,
                                            total = staged.size,
                                        ),
                                    )
                                },
                            )
                        }
                    }
                }
            }
            writer.locationUri().right()
        } catch (e: Throwable) {
            currentCoroutineContext().ensureActive()
            e.throwIfFatalOrCancellation()
            e.left()
        }
        send(DownloadProgress.Complete(result))
    }
}
