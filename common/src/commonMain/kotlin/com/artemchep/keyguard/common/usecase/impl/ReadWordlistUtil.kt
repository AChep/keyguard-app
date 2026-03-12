package com.artemchep.keyguard.common.usecase.impl

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

object ReadWordlistUtil {
    fun Sequence<String>.parseAsWordlist(): ImmutableList<String> = filter {
        it.isNotBlank() &&
                !it.startsWith('#') &&
                !it.startsWith(';') &&
                !it.startsWith('-') &&
                !it.startsWith('/')
    }.toImmutableList()

    context(String)
    fun parseAsWordlist(): ImmutableList<String> = lineSequence()
        .parseAsWordlist()
}
