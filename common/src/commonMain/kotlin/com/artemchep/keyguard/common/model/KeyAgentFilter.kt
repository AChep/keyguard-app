package com.artemchep.keyguard.common.model

import org.kodein.di.DirectDI

/**
 * A user-configured filter narrowing which vault ciphers a key
 * agent/provider surface is allowed to expose.
 */
interface KeyAgentFilter {
    val isActive: Boolean

    fun toDFilter(): DFilter
}

/**
 * Applies this filter to [items], keeping only the entries whose
 * cipher passes; an inactive filter keeps everything. All agent
 * surfaces must share this pipeline so they expose the same key set.
 */
suspend fun <T> KeyAgentFilter.filterCiphers(
    directDI: DirectDI,
    items: List<T>,
    cipherOf: (T) -> DSecret,
): List<T> {
    if (!isActive) {
        return items
    }

    val predicate = toDFilter()
        .prepare(
            directDI = directDI,
            ciphers = items.map(cipherOf),
        )
    return items
        .filter { predicate(cipherOf(it)) }
}

suspend fun KeyAgentFilter.filterCiphers(
    directDI: DirectDI,
    ciphers: List<DSecret>,
): List<DSecret> = filterCiphers(
    directDI = directDI,
    items = ciphers,
    cipherOf = { it },
)
