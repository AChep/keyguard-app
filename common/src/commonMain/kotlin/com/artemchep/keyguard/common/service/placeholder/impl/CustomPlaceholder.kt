package com.artemchep.keyguard.common.service.placeholder.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.placeholder.Placeholder
import com.artemchep.keyguard.common.service.placeholder.PlaceholderScope
import org.kodein.di.DirectDI

class CustomPlaceholder(
    private val cipher: DSecret,
) : Placeholder {
    override fun get(
        key: String,
    ): IO<String?>? = when {
        // Custom strings can be referenced using {S:Name}.
        // For example, if you have a custom string named "eMail",
        // you can use the placeholder {S:email}.
        key.startsWith("s:", ignoreCase = true) -> ioEffect {
            val name = key.substringAfter(':')
            val field = cipher.fields
                .firstOrNull { field ->
                    field.name
                        .equals(name, ignoreCase = true)
                }
            field?.value
        }
        // unknown
        else -> null
    }

    class Factory(
    ) : Placeholder.Factory {
        constructor(
            directDI: DirectDI,
        ) : this(
        )

        override fun createOrNull(
            scope: PlaceholderScope,
        ) = CustomPlaceholder(
            cipher = scope.cipher,
        )
    }
}
