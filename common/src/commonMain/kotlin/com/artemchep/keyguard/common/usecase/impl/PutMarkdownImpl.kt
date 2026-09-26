package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutMarkdown

class PutMarkdownImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutMarkdown {
    override fun invoke(markdown: Boolean): IO<Unit> = settingsReadWriteRepository
        .setMarkdown(markdown)
}
