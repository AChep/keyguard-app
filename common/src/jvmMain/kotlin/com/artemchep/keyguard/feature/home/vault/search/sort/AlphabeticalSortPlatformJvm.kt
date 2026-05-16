package com.artemchep.keyguard.feature.home.vault.search.sort

import java.text.Collator

private val collatorThreadLocal = ThreadLocal.withInitial {
    Collator.getInstance().apply {
        strength = Collator.PRIMARY
        decomposition = Collator.CANONICAL_DECOMPOSITION
    }
}

internal actual fun compareAlphabetically(a: String, b: String): Int =
    collatorThreadLocal.get().compare(a, b)
