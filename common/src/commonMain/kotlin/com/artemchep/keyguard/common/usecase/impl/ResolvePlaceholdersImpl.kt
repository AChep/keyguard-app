package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.usecase.ResolvePlaceholders
import org.kodein.di.DirectDI

// Here's Keepass docs for inspiration:
// https://keepass.info/help/base/placeholders.html
class ResolvePlaceholdersImpl(
) : ResolvePlaceholders {
    constructor(directDI: DirectDI) : this()

    override fun invoke(
        source: String,
    ): IO<String> {
        TODO("Not yet implemented")
    }
}
