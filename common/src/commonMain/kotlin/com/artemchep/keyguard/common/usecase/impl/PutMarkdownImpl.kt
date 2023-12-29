package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutMarkdown
import org.kodein.di.DirectDI
import org.kodein.di.instance

class PutMarkdownImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutMarkdown {
    constructor(directDI: DirectDI) : this(
        settingsReadWriteRepository = directDI.instance(),
    )

    override fun invoke(markdown: Boolean): IO<Unit> = settingsReadWriteRepository
        .setMarkdown(markdown)
}
