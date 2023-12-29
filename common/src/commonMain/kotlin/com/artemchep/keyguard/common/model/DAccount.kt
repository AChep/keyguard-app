package com.artemchep.keyguard.common.model

data class DAccount(
    val id: AccountId,
    val username: String,
    val host: String,
    val url: String,
    val faviconServer: (String) -> String,
) : HasAccountId, Comparable<DAccount> {
    private val comparator = compareBy<DAccount>(
        { host },
        { username },
        { url },
        { id.id },
    )

    override fun compareTo(other: DAccount): Int = comparator.compare(this, other)

    override fun accountId(): String = id.id
}
