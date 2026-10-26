package com.artemchep.keyguard.common.service.export.impl

import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.KeePassDatabaseWriter
import app.keemobile.kotpass.database.modifiers.modifyContent
import app.keemobile.kotpass.database.modifiers.modifyGroup
import app.keemobile.kotpass.database.modifiers.withRecycleBin
import app.keemobile.kotpass.database.openWriter
import app.keemobile.kotpass.models.BinaryData
import app.keemobile.kotpass.models.BinaryReference
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.Group
import com.artemchep.keyguard.common.exception.KeePassAttachmentTooLargeException
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.KEEPASS_FILE_UPLOAD_MAX_BYTES
import com.artemchep.keyguard.common.model.fileName
import com.artemchep.keyguard.common.model.fileSize
import com.artemchep.keyguard.common.service.export.ExportVaultData
import com.artemchep.keyguard.common.service.export.KdbxAttachmentLoader
import com.artemchep.keyguard.common.service.export.KdbxExportService
import com.artemchep.keyguard.common.service.keepass.createKeePassCredentials
import com.artemchep.keyguard.common.service.keepass.createKeePassDatabase
import com.artemchep.keyguard.common.service.keepass.keePassCipherProviders
import com.artemchep.keyguard.common.service.keepass.KEEPASS_ATTACHMENT_MEMORY_BYTES
import com.artemchep.keyguard.common.service.keepass.toPassphraseOrNull
import com.artemchep.keyguard.common.service.staging.SpoolLimits
import com.artemchep.keyguard.common.service.staging.StagingPurpose
import com.artemchep.keyguard.common.service.staging.StagingSpoolFactory
import com.artemchep.keyguard.common.usecase.GetFolderTreeById
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.feature.fileupload.isKeePassUploadFileSizeAllowed
import com.artemchep.keyguard.provider.bitwarden.repository.BitwardenCipherRepository
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.codec.KeePassCipherCodec
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.codec.KeePassFolderCodec
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.codec.withPasswordHistory
import com.artemchep.keyguard.util.io.spool.buildSnapshot
import kotlinx.coroutines.flow.first
import kotlinx.io.Sink
import okio.ByteString
import kotlin.uuid.Uuid

class KdbxExportServiceImpl internal constructor(
    private val cipherRepository: BitwardenCipherRepository,
    private val getFolderTreeById: GetFolderTreeById,
    private val cipherCodec: KeePassCipherCodec,
    private val stagingSpoolFactory: StagingSpoolFactory,
) : KdbxExportService {
    private val folderCodec = KeePassFolderCodec()

    /**
     * KeePass apps merge databases by the UUIDs of entries and groups,
     * so an item of a KeePass account keeps the UUID it has in its
     * database. Other items use their remote or local ids. The same
     * item may come from two accounts, so a UUID is never reused.
     */
    private class KeePassUuids {
        private val used = mutableSetOf<Uuid>()

        fun next(vararg ids: String?): Uuid {
            val uuid = ids
                .firstNotNullOfOrNull { id ->
                    id
                        ?.let { runCatchingNonFatal { Uuid.parse(it) }.getOrNull() }
                        ?.takeIf { it !in used }
                }
                ?: Uuid.random()
            used += uuid
            return uuid
        }
    }

    private class GroupNode(
        val group: Group,
    ) {
        val groups = mutableListOf<GroupNode>()
        val entries = mutableListOf<Entry>()

        fun toGroup(): Group = group.copy(
            groups = group.groups + groups.map { it.toGroup() },
            entries = group.entries + entries,
        )
    }

    override suspend fun export(
        sink: Sink,
        password: String,
        data: ExportVaultData,
        loadAttachment: KdbxAttachmentLoader?,
    ) {
        if (loadAttachment != null) {
            requireAttachmentSizesAllowed(data)
        }

        val credentials = createKeePassCredentials(
            passphrase = password.toPassphraseOrNull(),
            keyData = null,
        )
        val database = createKeePassDatabase(credentials)

        val root = GroupNode(database.content.group)
        val groupsByFolderId = mutableMapOf<String, GroupNode>()
        val trashedEntries = mutableListOf<Entry>()
        val uuids = KeePassUuids()
        // The attachments are written into the database as they
        // are loaded, so only one of them is staged at a time.
        database.openWriter(
            sink = sink,
            cipherProviders = keePassCipherProviders,
        ).use { writer ->
            data.ciphers.forEach { secret ->
                // The codec works with the stored cipher, the
                // export data only has the domain model.
                val cipher = cipherRepository.getById(secret.id).bind()
                    ?: return@forEach
                val entry = encodeEntry(
                    cipher = cipher,
                    secret = secret,
                    uuids = uuids,
                    writer = writer,
                    loadAttachment = loadAttachment,
                )
                if (secret.deleted) {
                    trashedEntries += entry
                    return@forEach
                }
                val group = secret.folderId
                    ?.let { folderId ->
                        getOrCreateGroup(
                            folderId = folderId,
                            root = root,
                            groupsByFolderId = groupsByFolderId,
                            uuids = uuids,
                        )
                    }
                    ?: root
                group.entries += entry
            }

            val content = database
                .modifyContent {
                    copy(group = root.toGroup())
                }
                .withTrashedEntries(trashedEntries)
                .content
            writer.finish(content)
        }
    }

    /**
     * Fails before any download when the size of
     * an attachment is already known.
     */
    private fun requireAttachmentSizesAllowed(
        data: ExportVaultData,
    ) {
        val tooLarge = data.ciphers.any { secret ->
            secret.attachments.any { attachment ->
                !isKeePassUploadFileSizeAllowed(attachment.fileSize())
            }
        }
        if (tooLarge) {
            throw KeePassAttachmentTooLargeException()
        }
    }

    private suspend fun encodeEntry(
        cipher: BitwardenCipher,
        secret: DSecret,
        uuids: KeePassUuids,
        writer: KeePassDatabaseWriter,
        loadAttachment: KdbxAttachmentLoader?,
    ): Entry {
        // Attachments are loaded separately, so items of
        // every account type export them the same way.
        val encoded = cipherCodec.encode(
            local = cipher.copy(attachments = emptyList()),
            remote = null,
            existingBinaries = emptyMap(),
        )
        val references = if (loadAttachment != null) {
            val names = mutableSetOf<String>()
            secret.attachments.map { attachment ->
                val hash = writeAttachment(
                    secret = secret,
                    attachment = attachment,
                    writer = writer,
                    loadAttachment = loadAttachment,
                )
                BinaryReference(
                    hash = hash,
                    name = names.addUnique(attachment.fileName()),
                )
            }
        } else {
            emptyList()
        }
        val uuid = uuids.next(
            cipher.service.remote?.id,
            cipher.cipherId,
        )
        return encoded.entry
            .copy(
                uuid = uuid,
                binaries = references,
            )
            .withPasswordHistory(cipher.passwordHistory)
    }

    private suspend fun getOrCreateGroup(
        folderId: String,
        root: GroupNode,
        groupsByFolderId: MutableMap<String, GroupNode>,
        uuids: KeePassUuids,
    ): GroupNode {
        groupsByFolderId[folderId]
            ?.let { return it }

        // The hierarchy goes from the topmost folder down
        // to the folder itself. Items of a missing folder
        // go to the root group.
        val hierarchy = getFolderTreeById(folderId).first()
            ?.hierarchy
            .orEmpty()
        return hierarchy.fold(root) { parent, node ->
            groupsByFolderId.getOrPut(node.folder.id) {
                val uuid = uuids.next(
                    node.folder.service.remote?.id,
                    node.folder.id,
                )
                val group = folderCodec.encodeNew(
                    uuid = uuid,
                    name = node.name,
                    revisionDate = node.folder.revisionDate,
                )
                GroupNode(group)
                    .also { parent.groups += it }
            }
        }
    }

    /**
     * Loads the attachment into the staging spool, which keeps
     * large content encrypted on disk instead of in memory, and
     * then copies it into the database.
     */
    private suspend fun writeAttachment(
        secret: DSecret,
        attachment: DSecret.Attachment,
        writer: KeePassDatabaseWriter,
        loadAttachment: KdbxAttachmentLoader,
    ): ByteString {
        // The database needs the size before the content, the size
        // and hash are computed while the attachment is staged.
        val (snapshot, binary) = BinaryData.Streamed.Builder(memoryProtection = false).use { builder ->
            val staged = stagingSpoolFactory
                .create(
                    purpose = StagingPurpose.KeePassAttachmentPlaintext,
                    limits = SpoolLimits(
                        memoryBytes = KEEPASS_ATTACHMENT_MEMORY_BYTES,
                        maximumBytes = KEEPASS_FILE_UPLOAD_MAX_BYTES,
                    ),
                    // The size of an attachment may be unknown
                    // until it is downloaded.
                    limitExceeded = {
                        KeePassAttachmentTooLargeException()
                    },
                )
                .buildSnapshot { output ->
                    builder.sink(output).use { sink ->
                        loadAttachment.load(secret, attachment, sink)
                    }
                }
            staged to builder.build(openSource = staged::openSource)
        }
        // The writer stores the same content once.
        snapshot.use {
            writer.writeBinary(binary)
        }
        return binary.hash
    }
}

/**
 * KeePass apps keep deleted entries in the recycle bin,
 * so the trashed items go there.
 */
private fun KeePassDatabase.withTrashedEntries(
    trashedEntries: List<Entry>,
): KeePassDatabase {
    if (trashedEntries.isEmpty()) {
        return this
    }

    return withRecycleBin { recycleBinUuid ->
        modifyGroup(recycleBinUuid) {
            copy(entries = entries + trashedEntries)
        }
    }
}

/**
 * KeePass finds the attachments of an entry by name, so
 * a repeated name gets a number: `scan.pdf`, `scan (1).pdf`.
 */
private fun MutableSet<String>.addUnique(fileName: String): String {
    if (add(fileName)) {
        return fileName
    }

    val extensionIndex = fileName.lastIndexOf('.')
        .takeIf { it > 0 }
        ?: fileName.length
    val name = fileName.substring(0, extensionIndex)
    val extension = fileName.substring(extensionIndex)
    return generateSequence(1) { it + 1 }
        .map { n -> "$name ($n)$extension" }
        .first { add(it) }
}
