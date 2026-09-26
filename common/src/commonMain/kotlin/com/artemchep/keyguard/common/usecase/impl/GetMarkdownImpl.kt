package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetMarkdown
import kotlinx.coroutines.flow.distinctUntilChanged

class GetMarkdownImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetMarkdown {
    private val sharedFlow = settingsReadRepository.getMarkdown()
        .distinctUntilChanged()

    override fun invoke() = sharedFlow
}
