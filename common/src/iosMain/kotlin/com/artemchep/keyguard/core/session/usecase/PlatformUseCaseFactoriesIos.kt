package com.artemchep.keyguard.core.session.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.DownloadAttachmentRequest
import com.artemchep.keyguard.common.model.DownloadAttachmentRequestData
import com.artemchep.keyguard.common.usecase.DownloadAttachmentMetadata
import com.artemchep.keyguard.common.usecase.GetAttachmentPreview
import com.artemchep.keyguard.core.store.bitwarden.KeePassToken
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.AddKeePassAccount
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.AddKeePassAccountParams
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.SyncByKeePassToken
import org.kodein.di.DirectDI

actual fun createDownloadAttachmentMetadata(
    directDI: DirectDI,
): DownloadAttachmentMetadata = UnsupportedDownloadAttachmentMetadata

actual fun createGetAttachmentPreview(
    directDI: DirectDI,
): GetAttachmentPreview = UnsupportedGetAttachmentPreview

actual fun createAddKeePassAccount(
    directDI: DirectDI,
): AddKeePassAccount = UnsupportedAddKeePassAccount

actual fun createSyncByKeePassToken(
    directDI: DirectDI,
): SyncByKeePassToken = UnsupportedSyncByKeePassToken

private object UnsupportedDownloadAttachmentMetadata : DownloadAttachmentMetadata {
    override fun invoke(request: DownloadAttachmentRequest): IO<DownloadAttachmentRequestData> =
        unsupportedIo("Attachment metadata resolution")
}

private object UnsupportedGetAttachmentPreview : GetAttachmentPreview {
    override fun invoke(request: com.artemchep.keyguard.common.model.AttachmentPreviewRequest) =
        unsupportedIo<com.artemchep.keyguard.common.model.AttachmentPreviewPayload>("Attachment preview")
}

private object UnsupportedAddKeePassAccount : AddKeePassAccount {
    override fun invoke(params: AddKeePassAccountParams): IO<AccountId> =
        unsupportedIo("KeePass account import")
}

private object UnsupportedSyncByKeePassToken : SyncByKeePassToken {
    override fun invoke(p1: KeePassToken): IO<Unit> =
        unsupportedIo("KeePass sync")
}

private fun <T> unsupportedIo(feature: String): IO<T> = ioEffect {
    throw UnsupportedOperationException("$feature is not supported on this platform.")
}
