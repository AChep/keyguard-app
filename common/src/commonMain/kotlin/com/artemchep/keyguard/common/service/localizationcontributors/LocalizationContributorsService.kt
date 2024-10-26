package com.artemchep.keyguard.common.service.localizationcontributors

import com.artemchep.keyguard.common.io.IO

interface LocalizationContributorsService {
    fun get(): IO<List<LocalizationContributor>>
}
