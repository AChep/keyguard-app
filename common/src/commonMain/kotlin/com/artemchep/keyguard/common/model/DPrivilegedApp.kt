package com.artemchep.keyguard.common.model

import com.artemchep.keyguard.feature.home.vault.search.sort.AlphabeticalSort
import kotlin.time.Instant

data class DPrivilegedApp(
    val id: String? = null,
    val name: String?,
    val packageName: String,
    val certFingerprintSha256: String,
    val createdDate: Instant?,
    val source: Source,
) : Comparable<DPrivilegedApp> {
    enum class Source {
        USER,
        APP,
    }

    override fun compareTo(other: DPrivilegedApp): Int {
        return AlphabeticalSort.compareStr(packageName, other.packageName)
    }
}
