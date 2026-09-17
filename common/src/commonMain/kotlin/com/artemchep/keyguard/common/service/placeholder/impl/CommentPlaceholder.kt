package com.artemchep.keyguard.common.service.placeholder.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.service.placeholder.Placeholder
import com.artemchep.keyguard.common.service.placeholder.PlaceholderScope

class CommentPlaceholder(
) : Placeholder {
    override fun get(
        key: String,
    ): IO<String?>? = when {
        // Comment; is removed.
        key.startsWith("c:") ->
            null.let(::io)
        // unknown
        else -> null
    }

    class Factory : Placeholder.Factory {
        override fun createOrNull(
            scope: PlaceholderScope,
        ) = CommentPlaceholder()
    }
}
