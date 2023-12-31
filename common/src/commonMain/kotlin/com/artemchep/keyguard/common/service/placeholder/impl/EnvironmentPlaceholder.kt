package com.artemchep.keyguard.common.service.placeholder.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.service.placeholder.Placeholder
import com.artemchep.keyguard.common.service.placeholder.PlaceholderScope
import org.kodein.di.DirectDI

class EnvironmentPlaceholder(
) : Placeholder {
    private val regex = "^%(.+)%$".toRegex()

    override fun get(
        key: String,
    ): IO<String?>? = kotlin.run {
        val result = regex.matchEntire(key)
        if (result != null) {
            ioEffect {
                val variable = result.groupValues[1]
                System.getenv(variable)
            }
        } else {
            // unknown
            null
        }
    }

    class Factory(
    ) : Placeholder.Factory {
        constructor(
            directDI: DirectDI,
        ) : this(
        )

        override fun createOrNull(
            scope: PlaceholderScope,
        ) = EnvironmentPlaceholder()
    }
}
