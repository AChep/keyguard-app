package com.artemchep.keyguard.common.model

import com.artemchep.keyguard.ui.icons.AccentColors
import kotlinx.datetime.Instant

data class DOrganization(
    val id: String,
    val accountId: String,
    val revisionDate: Instant,
    val keyBase64: String,
    val name: String,
    val accentColor: AccentColors,
    val selfHost: Boolean,
) : HasAccountId, Comparable<DOrganization> {
    companion object;

    override fun accountId(): String = accountId

    override fun compareTo(other: DOrganization): Int =
        name.compareTo(other.name, ignoreCase = true)
}
