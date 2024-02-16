package com.artemchep.keyguard.common.usecase.impl

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

object ReadWordlistUtil {
    context(String)
    fun parseAsWordlist(): ImmutableList<String> = lineSequence()
        .filter {
            it.isNotBlank() &&
                    !it.startsWith('#') &&
                    !it.startsWith(';') &&
                    !it.startsWith('-') &&
                    !it.startsWith('/')
        }
        .toImmutableList()
}
