package com.artemchep.keyguard.common.model

import kotlinx.datetime.Instant

data class DWatchtowerAlert(
    val alertId: String,
    val cipherId: CipherId,
    val accountId: AccountId,
    val type: DWatchtowerAlertType,
    val reportedAt: Instant,
    val read: Boolean,
    val version: String,
) : HasCipherId, HasAccountId {
    override fun cipherId(): String = cipherId.id

    override fun accountId(): String = accountId.id
}
