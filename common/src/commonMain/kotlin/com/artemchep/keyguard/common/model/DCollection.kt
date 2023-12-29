package com.artemchep.keyguard.common.model

import kotlinx.datetime.Instant

data class DCollection(
    val id: String,
    val organizationId: String?,
    val accountId: String,
    val revisionDate: Instant,
    val name: String,
    val readOnly: Boolean,
    val hidePasswords: Boolean,
) : HasAccountId, Comparable<DCollection> {
    companion object;

    override fun accountId(): String = accountId

    override fun compareTo(other: DCollection): Int =
        name.compareTo(other.name, ignoreCase = true)
}
