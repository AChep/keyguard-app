package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.usecase.ResolvePlaceholders

// Here's Keepass docs for inspiration:
// https://keepass.info/help/base/placeholders.html
class ResolvePlaceholdersImpl : ResolvePlaceholders {
    override fun invoke(
        source: String,
    ): IO<String> {
        TODO("Not yet implemented")
    }
}
