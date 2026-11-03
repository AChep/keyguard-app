package com.artemchep.keyguard.common.service.export.impl

import arrow.core.left
import arrow.core.right
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.throwIfFatalOrCancellation
import com.artemchep.keyguard.common.model.DFilter
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.DownloadAttachmentRequest
import com.artemchep.keyguard.common.model.fileName
import com.artemchep.keyguard.common.model.fileSize
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.dirs.DirsService
import com.artemchep.keyguard.common.service.download.DownloadAttachmentSourceLoader
import com.artemchep.keyguard.common.service.download.DownloadProgress
import com.artemchep.keyguard.common.service.download.DownloadWriter
import com.artemchep.keyguard.common.service.download.awaitCompleteResult
import com.artemchep.keyguard.common.service.export.ExportManager
import com.artemchep.keyguard.common.service.export.ExportVaultData
import com.artemchep.keyguard.common.service.export.ExportVaultDataService
import com.artemchep.keyguard.common.service.export.KdbxAttachmentLoader
import com.artemchep.keyguard.common.service.export.KdbxExportService
import com.artemchep.keyguard.common.service.export.model.ExportFormat
import com.artemchep.keyguard.common.service.export.model.ExportRequest
import com.artemchep.keyguard.common.service.session.VaultSessionLocker
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.common.usecase.DownloadAttachmentMetadata
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.common.util.flow.EventFlow
import com.artemchep.keyguard.util.io.toSource
import com.artemchep.keyguard.util.zip.ZipConfig
import com.artemchep.keyguard.util.zip.ZipEntry
import com.artemchep.keyguard.util.zip.ZipService
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Clock
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.io.Sink

open class ExportManagerBase(
    private val windowCoroutineScope: WindowCoroutineScope,
    private val cryptoGenerator: CryptoGenerator,
    private val exportVaultDataService: ExportVaultDataService,
    private val kdbxExportService: KdbxExportService,
    private val dirsService: DirsService,
    private val zipService: ZipService,
    private val dateFormatter: DateFormatter,
    private val downloadSourceLoader: DownloadAttachmentSourceLoader,
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

    override fun getProgressFlowByExportId(
        exportId: String,
    ): Flow<Flow<DownloadProgress>?> = sink
        .map { state ->
            state.values
                .firstOrNull { it.id == exportId }?.flow
        }

    override fun cancel(exportId: String) {
        val entry = sink.value.values
                .firstOrNull { it.id == exportId }
        entry?.scope?.cancel()
    }

    override suspend fun queue(
        request: ExportRequest,
    ): ExportManager.QueueResult {
        val entry = invoke2(
            filter = request.filter,
            password = request.password,
            exportAttachments = request.attachments,
            format = request.format,
        )
        onLaunch(entry.id)
        return ExportManager.QueueResult(
            exportId = entry.id,
            flow = entry.flow,
        )
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun invoke2(
        filter: DFilter,
        password: String,
        exportAttachments: Boolean,
        format: ExportFormat,
    ) = kotlin.run {
        val id = cryptoGenerator.uuid()

        val sharedScope = CoroutineScope(
            windowCoroutineScope.coroutineContext +
                SupervisorJob(windowCoroutineScope.coroutineContext[Job]) +
                Dispatchers.Default,
        )
        val progress = MutableStateFlow<DownloadProgress>(DownloadProgress.Loading())
        val entry = PoolEntry(
            id = id,
            scope = sharedScope,
            flow = progress.transformWhile { status ->
                emit(status)
                status is DownloadProgress.Loading
            },
        )
        // Register before starting, and retain the terminal value for late subscribers.
        sink.update { it.put(id, entry) }
        // UNDISPATCHED enters try/finally even if the window scope is already cancelled,
        // so cancellation completes progress and removes the registered entry.
        sharedScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                coroutineScope {
                    val keepAlive = launch { vaultSessionLocker.keepAlive() }
                    try {
                        channelFlow {
                            send(invoke(filter, password, exportAttachments, format))
                        }.collect { progress.value = it }
                    } finally {
                        keepAlive.cancel()
                    }
                }
            } catch (e: Throwable) {
                progress.value = DownloadProgress.Complete(e.left())
                e.throwIfFatalOrCancellation()
            } finally {
                sink.update { it.remove(id) }
                sharedScope.cancel()
            }
        }
        entry
    }

    private suspend fun ProducerScope<DownloadProgress>.invoke(
        filter: DFilter,
        password: String,
        exportAttachments: Boolean,
        format: ExportFormat,
    ): DownloadProgress.Complete {
        val data = exportVaultDataService.create(filter)

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
            "keyguard_export_$dt.${format.extension}"
        }
        val fileUri = coroutineScope {
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

            val onDownloadUpdated: () -> Unit = { eventFlow.emit(Unit) }
            val uri = dirsService.saveToDownloads(fileName) { os ->
                when (format) {
                    ExportFormat.Zip -> writeZip(
                        sink = os,
                        data = data,
                        password = password,
                        attachments = attachments,
                        onDownloadUpdated = onDownloadUpdated,
                    )

                    ExportFormat.Kdbx -> writeKdbx(
                        sink = os,
                        data = data,
                        password = password,
                        attachments = attachments,
                        onDownloadUpdated = onDownloadUpdated,
                    )
                }
            }.bind()
            monitorJob.cancelAndJoin()

            // Return the URI to the file
            uri
        }

        return DownloadProgress.Complete(fileUri.right())
    }

    private suspend fun writeZip(
        sink: Sink,
        data: ExportVaultData,
        password: String,
        attachments: AttachmentList?,
        onDownloadUpdated: () -> Unit,
    ) {
        // Map vault data to the JSON export
        // in the target type.
        val json = exportVaultDataService.exportJson(data)

        val entriesAttachments = attachments?.attachments.orEmpty()
            .map { entry ->
                createDownloadFileZipEntry(
                    entry = entry,
                    onDownloadUpdated = onDownloadUpdated,
                )
            }
        val entries = listOf(
            ZipEntry(
                name = "vault.json",
                data = ZipEntry.Data.In {
                    json.toSource()
                },
            ),
        ) + entriesAttachments
        zipService.zip(
            outputStream = sink,
            config = ZipConfig(
                encryption = ZipConfig.Encryption(
                    password = password,
                ),
            ),
            entries = entries,
        )
    }

    private suspend fun writeKdbx(
        sink: Sink,
        data: ExportVaultData,
        password: String,
        attachments: AttachmentList?,
        onDownloadUpdated: () -> Unit,
    ) {
        val loadAttachment = attachments
            ?.let { list ->
                val entries = list.attachments
                    .associateBy { it.cipher.id to it.attachment.id }
                KdbxAttachmentLoader { cipher, attachment, output ->
                    val entry = entries.getValue(cipher.id to attachment.id)
                    downloadAttachment(
                        entry = entry,
                        sink = output,
                        onDownloadUpdated = onDownloadUpdated,
                    )
                }
            }
        kdbxExportService.export(
            sink = sink,
            password = password,
            data = data,
            loadAttachment = loadAttachment,
        )
    }

    private fun createDownloadFileZipEntry(
        entry: AttachmentWithLiveProgress,
        onDownloadUpdated: () -> Unit,
    ): ZipEntry {
        val attachment = entry.attachment
        val data = ZipEntry.Data.Out { sink ->
            downloadAttachment(
                entry = entry,
                sink = sink,
                onDownloadUpdated = onDownloadUpdated,
            )
        }
        return ZipEntry(
            name = "attachments/${attachment.id}/${attachment.fileName()}",
            data = data,
        )
    }

    @OptIn(ExperimentalAtomicApi::class)
    private suspend fun downloadAttachment(
        entry: AttachmentWithLiveProgress,
        sink: Sink,
        onDownloadUpdated: () -> Unit,
    ) {
        val cipher = entry.cipher
        val attachment = entry.attachment
        val writer = DownloadWriter.SinkWriter(sink)
        val request = DownloadAttachmentRequest.ByLocalCipherAttachment(
            localCipherId = cipher.id,
            remoteCipherId = cipher.service.remote?.id,
            attachmentId = attachment.id,
        )
        val meta = downloadAttachmentMetadata(request)
            .bind()

        val fileLoaderFlow = downloadSourceLoader.fileLoader(
            request = meta,
            writer = writer,
        )
        val result = fileLoaderFlow
            .onEach { progress ->
                val downloaded = when (progress) {
                    is DownloadProgress.Loading -> {
                        progress.downloaded
                    }

                    DownloadProgress.None,
                    is DownloadProgress.Complete -> null
                }
                if (downloaded != null) {
                    entry.downloaded.store(downloaded)
                    onDownloadUpdated()
                }
            }
            .awaitCompleteResult()
        result.fold(
            ifLeft = { error ->
                throw error
            },
            ifRight = {
                entry.downloaded.store(entry.total)
                onDownloadUpdated()
            },
        )
    }

    @OptIn(ExperimentalAtomicApi::class)
    private class AttachmentWithLiveProgress(
        val cipher: DSecret,
        val attachment: DSecret.Attachment,
        val downloaded: AtomicLong,
        val total: Long,
    )

    @OptIn(ExperimentalAtomicApi::class)
    private class AttachmentList(
        val attachments: List<AttachmentWithLiveProgress>,
        val total: Long,
    ) {
        /**
         * Compute a current number of downloaded
         * bytes. The value is not static.
         */
        fun downloaded() = attachments.sumOf { it.downloaded.load().coerceAtMost(it.total) }
    }

    @OptIn(ExperimentalAtomicApi::class)
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
                            downloaded = AtomicLong(0L),
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
