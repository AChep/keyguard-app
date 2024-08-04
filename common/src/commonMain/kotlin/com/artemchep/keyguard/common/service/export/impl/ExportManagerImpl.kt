package com.artemchep.keyguard.common.service.export.impl

import arrow.core.left
import arrow.core.right
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.throwIfFatalOrCancellation
import com.artemchep.keyguard.common.model.DCollection
import com.artemchep.keyguard.common.model.DFilter
import com.artemchep.keyguard.common.model.DFolder
import com.artemchep.keyguard.common.model.DOrganization
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.DownloadAttachmentRequest
import com.artemchep.keyguard.common.model.fileName
import com.artemchep.keyguard.common.model.fileSize
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.dirs.DirsService
import com.artemchep.keyguard.common.service.download.DownloadProgress
import com.artemchep.keyguard.common.service.download.DownloadTask
import com.artemchep.keyguard.common.service.download.DownloadWriter
import com.artemchep.keyguard.common.service.export.ExportManager
import com.artemchep.keyguard.common.service.export.JsonExportService
import com.artemchep.keyguard.common.service.export.model.ExportRequest
import com.artemchep.keyguard.common.service.session.VaultSessionLocker
import com.artemchep.keyguard.common.service.zip.ZipConfig
import com.artemchep.keyguard.common.service.zip.ZipEntry
import com.artemchep.keyguard.common.service.zip.ZipService
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.common.usecase.DownloadAttachmentMetadata
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetCollections
import com.artemchep.keyguard.common.usecase.GetFolders
import com.artemchep.keyguard.common.usecase.GetOrganizations
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.common.util.flow.EventFlow
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import org.kodein.di.DirectDI
import org.kodein.di.instance
import java.io.File
import kotlin.concurrent.Volatile

open class ExportManagerBase(
    private val directDI: DirectDI,
    private val windowCoroutineScope: WindowCoroutineScope,
    private val cryptoGenerator: CryptoGenerator,
    private val jsonExportService: JsonExportService,
    private val dirsService: DirsService,
    private val zipService: ZipService,
    private val dateFormatter: DateFormatter,
    private val getOrganizations: GetOrganizations,
    private val getCollections: GetCollections,
    private val getFolders: GetFolders,
    private val getCiphers: GetCiphers,
    private val downloadTask: DownloadTask,
    private val downloadAttachmentMetadata: DownloadAttachmentMetadata,
    private val vaultSessionLocker: VaultSessionLocker,
    private val onLaunch: ExportManager.(String) -> Unit,
) : ExportManager {
    private data class PoolEntry(
        val id: String,
        val scope: CoroutineScope,
        val flow: Flow<DownloadProgress>,
    )

    private val sink =
        MutableStateFlow(persistentMapOf<String, PoolEntry>())

    private val mutex = Mutex()

    private val flowOfNone = flowOf(DownloadProgress.None)

    constructor(
        directDI: DirectDI,
        onLaunch: ExportManager.(String) -> Unit,
    ) : this(
        directDI = directDI,
        windowCoroutineScope = directDI.instance(),
        cryptoGenerator = directDI.instance(),
        jsonExportService = directDI.instance(),
        dirsService = directDI.instance(),
        zipService = directDI.instance(),
        dateFormatter = directDI.instance(),
        getOrganizations = directDI.instance(),
        getCollections = directDI.instance(),
        getFolders = directDI.instance(),
        getCiphers = directDI.instance(),
        downloadTask = directDI.instance(),
        downloadAttachmentMetadata = directDI.instance(),
        vaultSessionLocker = directDI.instance(),
        onLaunch = onLaunch,
    )

    private fun fileStatusBy(predicate: (PoolEntry) -> Boolean) = sink
        .map { state ->
            val entryOrNull = state.values.firstOrNull(predicate)
            entryOrNull?.flow
                ?: flowOfNone
        }
        .distinctUntilChanged()
        .flatMapLatest { it }

    override fun statusByExportId(
        exportId: String,
    ): Flow<DownloadProgress> = fileStatusBy { it.id == exportId }

    override suspend fun queue(
        request: ExportRequest,
    ): ExportManager.QueueResult {
        val entry = invoke2(
            filter = request.filter,
            password = request.password,
            exportAttachments = request.attachments,
        )
        onLaunch(entry.id)
        return ExportManager.QueueResult(
            exportId = entry.id,
            flow = entry.flow,
        )
    }

    private suspend fun invoke2(
        filter: DFilter,
        password: String,
        exportAttachments: Boolean,
    ) = kotlin.run {
        val id = cryptoGenerator.uuid()

        val sharedScope = windowCoroutineScope + SupervisorJob()
        val sharedFlow = flow {
            val internalFlow = channelFlow<DownloadProgress> {
                val result = try {
                    invoke(
                        filter = filter,
                        password = password,
                        exportAttachments = exportAttachments,
                    )
                } catch (e: Exception) {
                    e.throwIfFatalOrCancellation()

                    val result = e.left()
                    DownloadProgress.Complete(
                        result = result,
                    )
                }
                send(result)
            }

            try {
                emitAll(internalFlow)
            } finally {
                // Remove the export job
                withContext(NonCancellable) {
                    mutex.withLock {
                        sink.update { state ->
                            state.remove(id)
                        }
                    }
                }
            }
        }
            .onStart {
                val event = DownloadProgress.Loading()
                emit(event)
            }
            .shareIn(sharedScope, SharingStarted.Eagerly, replay = 1)

        val finalFlow = channelFlow<DownloadProgress> {
            val job = sharedScope.launch {
                // Keep the session alive while the vault is
                // being exported.
                launch {
                    vaultSessionLocker.keepAlive()
                }

                try {
                    sharedFlow
                        .onEach { status -> send(status) }
                        .collect()
                } finally {
                    // The scope is dead, but the flow is still alive, therefore
                    // someone has canceled the scope.
                    if (!this@channelFlow.isClosedForSend) {
                        val event = DownloadProgress.Complete(
                            result = RuntimeException("Canceled").left(),
                        )
                        trySend(event)
                    }
                }
            }

            job.join()
        }
            .transformWhile { progress ->
                emit(progress) // always emit progress
                progress is DownloadProgress.Loading
            }

        val entry = PoolEntry(
            id = id,
            scope = sharedScope,
            flow = finalFlow,
        )
        mutex.withLock {
            sink.update { state ->
                state.put(id, entry)
            }
        }
        entry
    }

    private suspend fun ProducerScope<DownloadProgress>.invoke(
        filter: DFilter,
        password: String,
        exportAttachments: Boolean,
    ): DownloadProgress.Complete {
        val data = createExportData(directDI, filter)
        // Map vault data to the JSON export
        // in the target type.
        val json = jsonExportService.export(
            organizations = data.organizations,
            collections = data.collections,
            folders = data.folders,
            ciphers = data.ciphers,
        )

        // Obtain a list of attachments to
        // download.
        val attachments = if (exportAttachments) {
            createAttachmentList(data.ciphers)
        } else {
            null
        }

        val fileName = kotlin.run {
            val now = Clock.System.now()
            val dt = dateFormatter.formatDateTimeMachine(now)
            "keyguard_export_$dt.zip"
        }
        coroutineScope {
            val eventFlow = EventFlow<Unit>()

            val monitorJob = launch {
                // No need to report the progress is there
                // are no attachments to download.
                attachments
                    ?: return@launch

                eventFlow
                    .onEach {
                        val event = DownloadProgress.Loading(
                            downloaded = attachments.downloaded(),
                            total = attachments.total,
                        )
                        trySend(event)
                    }
                    .collect()
            }

            dirsService.saveToDownloads(fileName) { os ->
                val entriesAttachments = attachments?.attachments.orEmpty()
                    .map { entry ->
                        createDownloadFileZipEntry(
                            entry = entry,
                            onDownloadUpdated = { eventFlow.emit(Unit) },
                        )
                    }
                val entries = listOf(
                    ZipEntry(
                        name = "vault.json",
                        data = ZipEntry.Data.In {
                            json.byteInputStream()
                        },
                    ),
                ) + entriesAttachments
                zipService.zip(
                    outputStream = os,
                    config = ZipConfig(
                        encryption = ZipConfig.Encryption(
                            password = password,
                        ),
                    ),
                    entries = entries,
                )
            }.bind()
            monitorJob.cancelAndJoin()
        }

        return DownloadProgress.Complete(File(".").right())
    }

    private fun createDownloadFileZipEntry(
        entry: AttachmentWithLiveProgress,
        onDownloadUpdated: () -> Unit,
    ): ZipEntry {
        val cipher = entry.cipher
        val attachment = entry.attachment
        val data = ZipEntry.Data.Out {
            val writer = DownloadWriter.StreamWriter(it)
            val request = DownloadAttachmentRequest.ByLocalCipherAttachment(
                localCipherId = cipher.id,
                remoteCipherId = cipher.service.remote?.id,
                attachmentId = attachment.id,
            )
            val meta = downloadAttachmentMetadata(request)
                .bind()
            downloadTask.fileLoader(
                url = meta.url,
                key = meta.encryptionKey,
                writer = writer,
            )
                .onEach { progress ->
                    val downloaded = when (progress) {
                        is DownloadProgress.None -> {
                            // Do nothing.
                            return@onEach
                        }

                        is DownloadProgress.Loading -> {
                            progress.downloaded
                        }

                        is DownloadProgress.Complete -> {
                            entry.total
                        }
                    }
                    if (downloaded != null) {
                        entry.downloaded = downloaded
                        onDownloadUpdated()
                    }
                }
                .last()
        }
        return ZipEntry(
            name = "attachments/${attachment.id}/${attachment.fileName()}",
            data = data,
        )
    }

    private class ExportData(
        val ciphers: List<DSecret>,
        val folders: List<DFolder>,
        val collections: List<DCollection>,
        val organizations: List<DOrganization>,
    )

    private suspend fun createExportData(
        directDI: DirectDI,
        filter: DFilter,
    ): ExportData {
        val ciphers = getCiphersByFilter(directDI, filter)
        val folders = kotlin.run {
            val foldersLocalIds = ciphers
                .asSequence()
                .map { it.folderId }
                .toSet()
            getFolders()
                .map { folders ->
                    folders
                        .filter { it.id in foldersLocalIds }
                }
                .first()
        }
        val collections = kotlin.run {
            val collectionIds = ciphers
                .asSequence()
                .flatMap { it.collectionIds }
                .toSet()
            getCollections()
                .map { collections ->
                    collections
                        .filter { it.id in collectionIds }
                }
                .first()
        }
        val organizations = kotlin.run {
            val organizationIds = ciphers
                .asSequence()
                .map { it.organizationId }
                .toSet()
            getOrganizations()
                .map { organizations ->
                    organizations
                        .filter { it.id in organizationIds }
                }
                .first()
        }
        return ExportData(
            ciphers = ciphers,
            folders = folders,
            collections = collections,
            organizations = organizations,
        )
    }

    private suspend fun getCiphersByFilter(
        directDI: DirectDI,
        filter: DFilter,
    ) = getCiphers()
        .map { ciphers ->
            val predicate = filter.prepare(directDI, ciphers)
            ciphers
                .filter(predicate)
        }
        .first()

    private class AttachmentWithLiveProgress(
        val cipher: DSecret,
        val attachment: DSecret.Attachment,
        @Volatile
        var downloaded: Long,
        val total: Long,
    )

    private class AttachmentList(
        val attachments: List<AttachmentWithLiveProgress>,
        val total: Long,
    ) {
        /**
         * Compute a current number of downloaded
         * bytes. The value is not static.
         */
        fun downloaded() = attachments.sumOf { it.downloaded.coerceAtMost(it.total) }
    }

    private fun createAttachmentList(
        ciphers: List<DSecret>,
    ): AttachmentList {
        val attachments = ciphers
            .flatMap { cipher ->
                cipher
                    .attachments
                    .map { attachment ->
                        AttachmentWithLiveProgress(
                            cipher = cipher,
                            attachment = attachment,
                            downloaded = 0L,
                            total = attachment.fileSize() ?: 0L,
                        )
                    }
            }
        return AttachmentList(
            attachments = attachments,
            total = attachments.sumOf { it.total },
        )
    }
}
