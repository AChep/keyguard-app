package com.artemchep.keyguard.common.model

import com.artemchep.keyguard.feature.home.vault.search.sort.AlphabeticalSort
import kotlin.time.Instant

data class DCollection(
    val id: String,
    val externalId: String?,
    val organizationId: String?,
    val accountId: String,
    val revisionDate: Instant,
    val name: String,
    val readOnly: Boolean,
    val hidePasswords: Boolean,
) : HasAccountId, Comparable<DCollection> {
    companion object;

    override fun accountId(): String = accountId

    override fun compareTo(other: DCollection): Int {
        return AlphabeticalSort.compareStr(name, other.name)
    }
}
