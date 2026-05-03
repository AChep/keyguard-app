package com.artemchep.keyguard.feature.home.vault.add.attachment

import com.artemchep.keyguard.common.model.create.CreateRequest
import com.artemchep.keyguard.common.model.create.attachments
import com.artemchep.keyguard.feature.add.AddStateItem
import com.artemchep.keyguard.feature.add.attachment.AttachmentItemStateConfig
import com.artemchep.keyguard.feature.add.attachment.attachmentStatePopulator
import com.artemchep.keyguard.feature.add.attachment.createAttachmentStateItem
import com.artemchep.keyguard.feature.filepicker.FilePickerResult
import com.artemchep.keyguard.feature.filepicker.humanReadableByteCountSI
import com.artemchep.keyguard.feature.fileupload.isVaultAttachmentFileSizeAllowed
import com.artemchep.keyguard.feature.fileupload.toAttachmentFileMetadata
import com.artemchep.keyguard.feature.home.settings.accounts.model.AccountType
import com.artemchep.keyguard.feature.home.vault.add.Foo2Factory
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import kotlinx.coroutines.flow.flowOf
import kotlin.uuid.Uuid

class SkeletonAttachmentItemFactory : Foo2Factory<AddStateItem.Attachment<*>, SkeletonAttachment> {
    override val type: String = "attachment"

    override fun RememberStateFlowScope.release(key: String) {
        clearPersistedFlow("$key.identity")
        clearPersistedFlow("$key.name")
    }

    override fun RememberStateFlowScope.add(
        key: String,
        initial: SkeletonAttachment?,
    ): AddStateItem.Attachment<CreateRequest> {
        val identitySink = mutablePersistedFlow("$key.identity") {
            val identity = initial?.identity
            requireNotNull(identity)
        }
        val identity = identitySink.value
        val synced = identity is SkeletonAttachment.Remote.Identity

        return AddStateItem.Attachment(
            id = key,
            state = createAttachmentStateItem(
                key = key,
                initialName = initial?.name.orEmpty(),
                initialConfig = AttachmentItemStateConfig(
                    id = "id",
                    size = initial?.size,
                    synced = synced,
                ),
                configFlow = flowOf(
                    AttachmentItemStateConfig(
                        id = "id",
                        size = initial?.size,
                        synced = synced,
                    ),
                ),
                populator = attachmentStatePopulator(
                    identity = identity,
                    populator = CreateRequest::withSkeletonAttachment,
                ),
            ),
        )
    }
}

internal fun CreateRequest.withSkeletonAttachment(
    identity: SkeletonAttachment.Identity,
    name: String,
): CreateRequest = CreateRequest.attachments.modify(this) {
    val model = when (identity) {
        is SkeletonAttachment.Remote.Identity -> {
            CreateRequest.Attachment.Remote(
                id = identity.id,
                name = name,
            )
        }

        is SkeletonAttachment.Local.Identity -> {
            CreateRequest.Attachment.Local(
                id = identity.id,
                uri = identity.uri,
                size = identity.size,
                name = name,
                keyBase64 = identity.keyBase64,
            )
        }
    }
    it.add(model)
}

internal fun FilePickerResult.toSkeletonAttachment(): SkeletonAttachment.Local {
    return requireNotNull(toSkeletonAttachmentOrNull()) {
        "Vault attachment file is too large."
    }
}

internal fun FilePickerResult.toSkeletonAttachmentOrNull(
    accountType: AccountType? = null,
): SkeletonAttachment.Local? {
    if (!isVaultAttachmentFileSizeAllowed(size, accountType)) {
        return null
    }

    val metadata = toAttachmentFileMetadata(
        fallbackName = "File",
    )
    return SkeletonAttachment.Local(
        identity = SkeletonAttachment.Local.Identity(
            id = Uuid.random().toString(),
            uri = metadata.uri,
            size = metadata.size,
        ),
        name = metadata.name,
        size = metadata.size?.let(::humanReadableByteCountSI).orEmpty(),
    )
}
