package com.artemchep.keyguard.feature.confirmation.organization

import com.artemchep.keyguard.ui.SimpleNote

data class AccountChangeWarning(
    val sourceAccountIds: Set<String>,
    val note: SimpleNote,
    val blocksConfirmation: Boolean = false,
) {
    fun appliesTo(accountId: String?): Boolean =
        accountId != null && sourceAccountIds.any { it != accountId }
}
