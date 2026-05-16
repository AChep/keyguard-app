package com.artemchep.keyguard.core.session.usecase

import com.artemchep.keyguard.common.usecase.DownloadAttachmentMetadata
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.AddKeePassAccount
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.SyncByKeePassToken
import org.kodein.di.DirectDI

expect fun createDownloadAttachmentMetadata(
    directDI: DirectDI,
): DownloadAttachmentMetadata

expect fun createAddKeePassAccount(
    directDI: DirectDI,
): AddKeePassAccount

expect fun createSyncByKeePassToken(
    directDI: DirectDI,
): SyncByKeePassToken
