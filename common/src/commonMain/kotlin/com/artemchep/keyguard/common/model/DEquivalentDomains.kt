package com.artemchep.keyguard.common.model

data class DEquivalentDomains(
    val id: String,
    val accountId: String,
    // fields
    val global: Boolean,
    val excluded: Boolean,
    val domains: List<String>,
) : HasAccountId {
    companion object;

    override fun accountId(): String = accountId
}
