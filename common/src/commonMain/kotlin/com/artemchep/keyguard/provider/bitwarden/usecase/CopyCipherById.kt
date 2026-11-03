package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.exception.UnsupportedEnvironmentCopyException
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.create.CreateRequest
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.usecase.AddFolder
import com.artemchep.keyguard.common.usecase.AddFolderRequest
import com.artemchep.keyguard.common.usecase.CopyCipherById
import com.artemchep.keyguard.common.usecase.ResolveFolderHierarchyMode
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.CipherSourceDataReconciler
import com.artemchep.keyguard.core.store.bitwarden.attachments
import com.artemchep.keyguard.core.store.bitwarden.name
import com.artemchep.keyguard.feature.confirmation.organization.FolderInfo
import com.artemchep.keyguard.feature.environments.withoutReferences
import com.artemchep.keyguard.provider.bitwarden.mapper.of
import com.artemchep.keyguard.provider.bitwarden.mapper.toDomain
import com.artemchep.keyguard.provider.bitwarden.usecase.util.ModifyCipherById

/**
 * @author Artem Chepurnyi
 */
class CopyCipherByIdImpl(
    private val modifyCipherById: ModifyCipherById,
    private val addFolder: AddFolder,
    private val resolveFolderHierarchyMode: ResolveFolderHierarchyMode,
    private val cryptoGenerator: CryptoGenerator,
) : CopyCipherById {
    companion object {
        private const val TAG = "CopyCipherById.bitwarden"
    }

    override fun invoke(
        cipherIdsToRequests: Map<String, CreateRequest.Ownership2>,
    ): IO<Unit> = ioEffect {
        val r = cipherIdsToRequests
            .mapValues { (key, value) ->
                val folderId = when (val folder = value.folder) {
                    is FolderInfo.None -> null
                    is FolderInfo.New -> {
                        val accountId = AccountId(value.accountId!!)
                        val hierarchyMode = resolveFolderHierarchyMode(accountId)
                            .bind()
                        val rq = listOf(
                            AddFolderRequest(
                                accountId = accountId,
                                name = folder.name,
                                hierarchyMode = hierarchyMode,
                            ),
                        )
                        val rs = addFolder(rq)
                            .bind()
                        rs.first()
                    }

                    is FolderInfo.Id -> folder.id
                }
                CreateRequest.Ownership(
                    accountId = value.accountId,
                    folderId = folderId,
                    organizationId = value.organizationId,
                    collectionIds = value.collectionIds,
                )
            }
        invoke2(r).bind()
    }

    private fun invoke2(
        cipherIdsToRequests: Map<String, CreateRequest.Ownership>,
    ): IO<Unit> = modifyCipherById(
        cipherIdsToRequests
            .keys,
    ) { model ->
        val request = cipherIdsToRequests.getValue(model.cipherId)
        val cipherId = cryptoGenerator.uuid()

        val accountId = request.accountId
        val folderId = request.folderId
        val organizationId = request.organizationId
        val collectionIds = request.collectionIds
        requireNotNull(accountId) { "Cipher must have an account!" }
        require(organizationId == null || collectionIds.isNotEmpty()) {
            "When a cipher belongs to an organization, it must have at " +
                    "least one collection!"
        }

        val changesAccount = accountId != model.data_.accountId
        val changesOwnership = changesAccount ||
                request.organizationId != model.data_.organizationId

        // Provider IDs are scoped to an account. Never reinterpret a source
        // reference as an item in the destination account, even in a batch copy.
        val environment = if (
            changesAccount &&
            (model.data_.type == BitwardenCipher.Type.Environment || model.data_.environment != null)
        ) {
            model.data_.environment?.toDomain().withoutReferences()
                ?.let(BitwardenCipher.Environment::of)
                ?: throw UnsupportedEnvironmentCopyException()
        } else {
            model.data_.environment
        }

        var new = model
        new = new.copy(
            data_ = new.data_.copy(
                cipherId = cipherId,
                accountId = accountId,
                folderId = folderId,
                organizationId = organizationId,
                collectionIds = collectionIds,
                links = if (changesAccount) emptyList() else model.data_.links,
                environment = environment,
            ),
        )
        new = new.copy(
            data_ = new.data_.copy(
                sourceData = CipherSourceDataReconciler.onCopy(
                    source = model.data_,
                    target = new.data_,
                ),
            ),
        )
        new = new.copy(
            cipherId = cipherId,
            accountId = accountId,
            folderId = folderId,
        )
        // We are creating a copy, that means that the new cipher
        // has not been on remote yet.
        new = new.copy(
            data_ = new.data_.copy(
                service = new.data_.service.copy(
                    remote = null,
                    deleted = false,
                ),
            ),
        )
        // TODO: Support copying attachments
        if (changesOwnership) {
            new = new.copy(
                data_ = BitwardenCipher.attachments.set(new.data_, emptyList()),
            )
        }
        // TODO: Check if the name collides
        // If we copy to the same account, then add a prefix to
        // the name.
        if (!changesOwnership) {
            new = new.copy(
                data_ = BitwardenCipher.name.modify(new.data_) { oldName ->
                    "Copy of $oldName"
                },
            )
        }
        new
    }.map { Unit }
}
