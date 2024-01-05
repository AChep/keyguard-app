package com.artemchep.keyguard.common.service.placeholder

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.util.simpleFormat2

suspend fun String.placeholderFormat(
    placeholders: List<Placeholder>,
) = this
    .simpleFormat2(
        getter = { key ->
            val p = placeholders
                .firstNotNullOfOrNull { p -> p[key] }
            when (p) {
                // If no substitute value was found then we keep the
                // placeholder intact.
                null -> null
                else -> p.bind().orEmpty()
            }
        },
    )
