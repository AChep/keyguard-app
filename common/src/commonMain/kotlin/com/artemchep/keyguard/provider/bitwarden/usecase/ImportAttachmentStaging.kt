package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.KEEPASS_FILE_UPLOAD_MAX_BYTES
import com.artemchep.keyguard.common.model.create.CreateRequest
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.imports.ImportAttachmentSource
import com.artemchep.keyguard.common.service.imports.ImportPlan
import com.artemchep.keyguard.common.service.imports.impl.mapImportTooLarge
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.util.useAndClear
import com.artemchep.keyguard.provider.bitwarden.crypto.makeCipherAttachmentCryptoKeyMaterial
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadCoordinator
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadFile
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadTarget
import com.artemchep.keyguard.provider.bitwarden.upload.deleteBestEffort
import com.artemchep.keyguard.util.io.limitedTo
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.io.Source

/**
 * Encrypts the attachments of an import into the pending uploads of
 * [accountId] while its source streams them, so that the import never writes
 * their plaintext to a file.
 *
 * The files belong to the import until it hands them to the vault in a
 * create request; [discardOnFailure] deletes those of a commit that never got
 * there.
 */
internal class ImportAttachmentStaging(
    private val accountId: AccountId,
    private val pendingUploadCoordinator: PendingUploadCoordinator,
    private val cryptoGenerator: CryptoGenerator,
    private val base64Service: Base64Service,
    /** The vault does not keep a larger file; a hostile archive could inflate one without end. */
    private val maxAttachmentBytes: Long = KEEPASS_FILE_UPLOAD_MAX_BYTES,
) {
    // Written from the several reads of a backup at once.
    private val stagedSink = MutableStateFlow(persistentListOf<PendingUploadFile>())

    /**
     * Stages every attachment of the [plan], answering them per item, in the
     * order of the items and of their attachments.
     */
    suspend fun stage(
        plan: ImportPlan,
        source: ImportAttachmentSource,
    ): List<List<CreateRequest.Attachment.Staged>> {
        val slotsByRef = plan.items
            .flatMapIndexed { itemIndex, item ->
                item.attachments.mapIndexed { index, attachment ->
                    Slot(itemIndex, index, attachment)
                }
            }
            .groupBy { it.attachment.ref }
        // Every planned attachment needs a file of its own, as the vault
        // deletes it once uploaded, and an archive streams each entry only
        // once. A ref planned for several items — an item shared with two of
        // the exported accounts — is read once more per extra copy.
        val rounds = slotsByRef.values.maxOfOrNull { it.size } ?: 0
        val stagedBySlot = mutableMapOf<Slot, CreateRequest.Attachment.Staged>()
        repeat(rounds) { round ->
            val slotByRef = slotsByRef
                .mapNotNull { (ref, slots) ->
                    slots.getOrNull(round)?.let { ref to it }
                }
                .toMap()
            val files = source.extract(slotByRef.keys) { ref, file ->
                stage(slotByRef.getValue(ref).attachment, file)
            }
            slotByRef.forEach { (ref, slot) ->
                stagedBySlot[slot] = files.getValue(ref)
            }
        }
        return plan.items.mapIndexed { itemIndex, item ->
            item.attachments.mapIndexed { index, attachment ->
                stagedBySlot.getValue(Slot(itemIndex, index, attachment))
            }
        }
    }

    private suspend fun stage(
        attachment: ImportPlan.Attachment,
        source: Source,
    ): CreateRequest.Attachment.Staged {
        val id = cryptoGenerator.uuid()
        val key = cryptoGenerator.makeCipherAttachmentCryptoKeyMaterial()
        val keyBase64 = base64Service.encodeToString(key)
        val file = key.useAndClear { borrowedKey ->
            // The size an archive lists for an entry is the archive's
            // word, not a bound.
            mapImportTooLarge {
                pendingUploadCoordinator.stage(
                    target = PendingUploadTarget.NewCipherAttachment(
                        accountId = accountId.id,
                        attachmentId = id,
                    ),
                    source = source.limitedTo(maxAttachmentBytes),
                    fileKey = borrowedKey,
                )
            }
        }
        stagedSink.update { it.adding(file) }
        return CreateRequest.Attachment.Staged(
            id = id,
            name = attachment.name,
            keyBase64 = keyBase64,
            file = file,
        )
    }

    /**
     * Runs [block], deleting every file staged so far if it fails — also when
     * it is cancelled, e.g. by the vault locking. Only for the part of a
     * commit before it hands the files to the vault: from then on, they are
     * the vault's.
     */
    suspend fun <T> discardOnFailure(
        block: suspend () -> T,
    ): T {
        var success = false
        try {
            return block().also { success = true }
        } finally {
            if (!success) {
                withContext(NonCancellable) {
                    val files = stagedSink.getAndUpdate { persistentListOf() }
                    pendingUploadCoordinator.deleteBestEffort(files)
                }
            }
        }
    }

    private data class Slot(
        val itemIndex: Int,
        val attachmentIndex: Int,
        val attachment: ImportPlan.Attachment,
    )
}
