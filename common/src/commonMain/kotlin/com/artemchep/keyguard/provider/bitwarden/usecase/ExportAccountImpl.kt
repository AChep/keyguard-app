package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.DFilter
import com.artemchep.keyguard.common.model.DownloadAttachmentRequest
import com.artemchep.keyguard.common.model.fileName
import com.artemchep.keyguard.common.service.dirs.DirsService
import com.artemchep.keyguard.common.service.download.DownloadTask
import com.artemchep.keyguard.common.service.download.DownloadWriter
import com.artemchep.keyguard.common.service.export.JsonExportService
import com.artemchep.keyguard.common.service.zip.ZipConfig
import com.artemchep.keyguard.common.service.zip.ZipEntry
import com.artemchep.keyguard.common.service.zip.ZipService
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.common.usecase.DownloadAttachmentMetadata
import com.artemchep.keyguard.common.usecase.ExportAccount
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetCollections
import com.artemchep.keyguard.common.usecase.GetFolders
import com.artemchep.keyguard.common.usecase.GetOrganizations
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.map
import kotlin.time.Clock
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class ExportAccountImpl(
    private val directDI: DirectDI,
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
) : ExportAccount {
    companion object {
        private const val TAG = "ExportAccount.bitwarden"
    }

    constructor(directDI: DirectDI) : this(
        directDI = directDI,
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
    )

    override fun invoke(
        filter: DFilter,
        password: String,
        attachments: Boolean,
    ) = ioEffect {
        val ciphers = getCiphersByFilter(filter)
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

        // Map vault data to the JSON export
        // in the target type.
        val json = jsonExportService.export(
            organizations = organizations,
            collections = collections,
            folders = folders,
            ciphers = ciphers,
        )

        val fileName = kotlin.run {
            val now = Clock.System.now()
            val dt = dateFormatter.formatDateTimeMachine(now)
            "keyguard_export_$dt.zip"
        }
        dirsService.saveToDownloads(fileName) { os ->
            val entriesAttachments = ciphers.flatMap { cipher ->
                cipher.attachments
                    .map { attachment ->
                        ZipEntry(
                            name = "attachments/${attachment.id}/${attachment.fileName()}",
                            data = ZipEntry.Data.Out {
                                val writer = DownloadWriter.StreamWriter(it)
                                val request = DownloadAttachmentRequest.ByLocalCipherAttachment(
                                    localCipherId = cipher.id,
                                    remoteCipherId = cipher.service.remote?.id,
                                    attachmentId = attachment.id,
                                )
                                val data = downloadAttachmentMetadata(request)
                                    .bind()
                                downloadTask.fileLoader(
                                    url = data.url,
                                    key = data.encryptionKey,
                                    writer = writer,
                                ).last().also {
                                    println(it)
                                }
                            },
                        )
                    }
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
    }

    private suspend fun getCiphersByFilter(filter: DFilter) = getCiphers()
        .map { ciphers ->
            val predicate = filter.prepare(directDI, ciphers)
            ciphers
                .filter(predicate)
        }
        .first()

}
