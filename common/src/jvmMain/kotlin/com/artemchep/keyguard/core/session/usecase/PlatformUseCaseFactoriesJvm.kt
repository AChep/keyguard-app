package com.artemchep.keyguard.core.session.usecase

import com.artemchep.keyguard.common.usecase.DownloadAttachmentMetadata
import com.artemchep.keyguard.common.usecase.GetAttachmentPreview
import com.artemchep.keyguard.common.usecase.impl.DownloadAttachmentMetadataImpl2
import com.artemchep.keyguard.common.usecase.impl.GetAttachmentPreviewImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.AddKeePassAccount
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.AddKeePassAccountImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.SyncByKeePassToken
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.SyncByKeePassTokenImpl
import org.kodein.di.DirectDI

actual fun createDownloadAttachmentMetadata(
    directDI: DirectDI,
): DownloadAttachmentMetadata = DownloadAttachmentMetadataImpl2(directDI)

actual fun createGetAttachmentPreview(
    directDI: DirectDI,
): GetAttachmentPreview = GetAttachmentPreviewImpl(directDI)

actual fun createAddKeePassAccount(
    directDI: DirectDI,
): AddKeePassAccount = AddKeePassAccountImpl(directDI)

actual fun createSyncByKeePassToken(
    directDI: DirectDI,
): SyncByKeePassToken = SyncByKeePassTokenImpl(directDI)
