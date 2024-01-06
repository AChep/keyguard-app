package com.artemchep.keyguard.common.service.placeholder.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.service.placeholder.Placeholder
import com.artemchep.keyguard.common.service.placeholder.util.Parser

class TextReplaceRegexPlaceholder(
) : Placeholder {
    private val parser = Parser(
        name = "t-replace-rx",
        count = 3,
    )

    override fun get(
        key: String,
    ): IO<String?>? {
        val params = parser.parse(key)
            ?: return null
        val regex = params.params.getOrNull(0)
        val replacement = params.params.getOrNull(1)
        if (
            regex == null ||
            replacement == null
        ) {
            return null
        }

        return ioEffect {
            val rg = regex.toRegex()
            rg.replace(params.value, replacement)
        }
    }
}
