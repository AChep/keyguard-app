package com.artemchep.keyguard.common.model

interface HasAccountId {
    fun accountId(): String
}

fun <T : HasAccountId> List<T>.firstOrNull(accountId: AccountId) = this
    .firstOrNull { it.accountId() == accountId.id }
