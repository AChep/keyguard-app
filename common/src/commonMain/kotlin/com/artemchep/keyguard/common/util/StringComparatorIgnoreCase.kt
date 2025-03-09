package com.artemchep.keyguard.common.util

import com.artemchep.keyguard.feature.home.vault.search.sort.AlphabeticalSort

class StringComparatorIgnoreCase<T>(
    private val descending: Boolean = false,
    private val getter: (T) -> String?,
) : Comparator<T> {
    override fun compare(aHolder: T, bHolder: T): Int {
        val a = getter(aHolder)
        val b = getter(bHolder)

        var r = internalCompare(a, b)
        // Reverse the comparison result,
        // if needed.
        if (descending) {
            r *= -1
        }
        return r
    }

    private fun internalCompare(a: String?, b: String?): Int {
        if (a === b) return 0
        if (a == null) return -1
        if (b == null) return 1
        return AlphabeticalSort.compareStr(a, b)
    }
}
