package com.artemchep.keyguard.common.model

data class CheckUsernameLeakRequest(
    val accountId: AccountId,
    val username: String,
)
