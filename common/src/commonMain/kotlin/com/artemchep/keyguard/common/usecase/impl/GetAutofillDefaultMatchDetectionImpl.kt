package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetAutofillDefaultMatchDetection
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GetAutofillDefaultMatchDetectionImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetAutofillDefaultMatchDetection {
    private val sharedFlow = settingsReadRepository.getAutofillDefaultMatchDetection()
        .map { key -> findOrDefault(key) }
        .distinctUntilChanged()
        .shareIn(
            scope = GlobalScope,
            started = SharingStarted.WhileSubscribed(5000L),
            replay = 1,
        )

    constructor(directDI: DirectDI) : this(
        settingsReadRepository = directDI.instance(),
    )

    override fun invoke() = sharedFlow

    private fun findOrDefault(key: String) = findOrNull(key)
        ?: DSecret.Uri.MatchType.default

    private fun findOrNull(key: String) = DSecret.Uri.MatchType.entries
        .firstOrNull { it.name.equals(key, ignoreCase = true) }
}
