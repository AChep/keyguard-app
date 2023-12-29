package com.artemchep.keyguard.common.model

import arrow.optics.optics
import com.artemchep.keyguard.feature.confirmation.organization.FolderInfo

@optics
data class FolderOwnership2(
    val accountId: String,
    val folder: FolderInfo,
) {
    companion object;
}
