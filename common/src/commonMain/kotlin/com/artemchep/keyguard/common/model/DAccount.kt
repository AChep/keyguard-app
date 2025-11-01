package com.artemchep.keyguard.common.model

import com.artemchep.keyguard.feature.home.settings.accounts.model.AccountType

data class DAccount(
    val id: AccountId,
    /**
     * Username or `null` if the
     * account is single-user.
     */
    val username: String?,
    val host: String,
    /**
     * URL to the web vault, if it does exist,
     * otherwise `null`.
     */
    val webVaultUrl: String?,
    /**
     * URL to the local vault, if it does exist,
     * otherwise `null`.
     */
    val localVaultUrl: String?,
    val type: AccountType,
    val faviconServer: ((String) -> String)?,
) : HasAccountId, Comparable<DAccount> {
    private val comparator = compareBy<DAccount>(
        { host },
        { username },
        { webVaultUrl },
        { localVaultUrl },
        { id.id },
    )

    override fun compareTo(other: DAccount): Int = comparator.compare(this, other)

    override fun accountId(): String = id.id
}
