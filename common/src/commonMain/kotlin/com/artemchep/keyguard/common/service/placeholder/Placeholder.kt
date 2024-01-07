package com.artemchep.keyguard.common.service.placeholder

import com.artemchep.keyguard.common.io.IO

// See:
// https://keepass.info/help/base/placeholders.html
interface Placeholder {
    operator fun get(key: String): IO<String?>?

    interface Factory {
        fun createOrNull(
            scope: PlaceholderScope,
        ): Placeholder?
    }
}

fun List<Placeholder.Factory>.create(
    scope: PlaceholderScope,
): List<Placeholder> = this
    .mapNotNull { placeholder ->
        placeholder.createOrNull(scope)
    }
