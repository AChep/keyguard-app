package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.usecase.AddCipher
import com.artemchep.keyguard.common.usecase.AddFolder
import com.artemchep.keyguard.common.usecase.CommitImportPlan
import com.artemchep.keyguard.common.usecase.CommitImportPlanRequest
import com.artemchep.keyguard.common.usecase.CommitImportPlanResult
import com.artemchep.keyguard.common.usecase.ResolveFolderHierarchyMode
import com.artemchep.keyguard.common.usecase.createCiphers
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadCoordinator
import kotlinx.collections.immutable.toPersistentList

/**
 * Creates the planned folders and items in the target account: folders one
 * batch per hierarchy level (parents resolve before children), then every
 * item in a single [createCiphers] transaction. Items the source had archived
 * or trashed are created archived or trashed in that same write.
 *
 * A plan with folders and no items is a legitimate commit — the review offers
 * it — and [createCiphers] answers an empty request list without touching the
 * vault.
 */
class CommitImportPlanImpl(
    private val resolveFolderHierarchyMode: ResolveFolderHierarchyMode,
    private val addFolder: AddFolder,
    private val addCipher: AddCipher,
    private val cryptoGenerator: CryptoGenerator,
    private val pendingUploadCoordinator: PendingUploadCoordinator,
    private val base64Service: Base64Service,
) : CommitImportPlan {
    override fun invoke(
        request: CommitImportPlanRequest,
    ): IO<CommitImportPlanResult> = ioEffect {
        val plan = request.plan
        val folderHierarchyMode = resolveFolderHierarchyMode(request.accountId)
            .bind()
        val staging = ImportAttachmentStaging(
            accountId = request.accountId,
            pendingUploadCoordinator = pendingUploadCoordinator,
            cryptoGenerator = cryptoGenerator,
            base64Service = base64Service,
        )
        val (requests, folderCount) = staging.discardOnFailure {
            // Staged before anything is written, so a file that can no longer be
            // read fails the import without leaving folders behind. The files
            // are encrypted as they are read, never written out in plaintext.
            val stagedAttachments = if (plan.items.all { it.attachments.isEmpty() }) {
                plan.items.map { emptyList() }
            } else {
                val source = checkNotNull(request.attachments) {
                    "The source of a plan with attachments is no longer open."
                }
                staging.stage(plan, source)
            }
            val folderIdByKey = createPlannedFolders(
                folders = plan.folders,
                accountId = request.accountId,
                addFolder = addFolder,
                hierarchyMode = folderHierarchyMode,
            )
            val requests = plan.toCreateRequests(
                accountId = request.accountId,
                folderIdByKey = folderIdByKey,
                untitledTitle = request.untitledTitle,
            ).zip(stagedAttachments) { createRequest, staged ->
                if (staged.isEmpty()) {
                    return@zip createRequest
                }
                createRequest.copy(
                    attachments = staged.toPersistentList(),
                )
            }
            requests to folderIdByKey.size
        }
        // From here on the files are the vault's: it deletes them if writing the
        // items fails, and must keep them if it wrote the items and the call
        // fails after all, e.g. when cancelled. Whatever a failure before the
        // write leaves behind is ciphertext under a key nobody saved, which the
        // sweep of a sync removes once it is a day old.
        addCipher.createCiphers(
            requests = requests,
            cryptoGenerator = cryptoGenerator,
        ).bind()
        CommitImportPlanResult(
            itemCount = requests.size,
            folderCount = folderCount,
        )
    }
}
