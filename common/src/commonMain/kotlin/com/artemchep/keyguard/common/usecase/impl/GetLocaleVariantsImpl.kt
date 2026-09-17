package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.build.LocaleConfig
import com.artemchep.keyguard.common.usecase.GetLocaleVariants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class GetLocaleVariantsImpl : GetLocaleVariants {
    private val variants = listOf<String?>(null) + LocaleConfig.locales

    override fun invoke(): Flow<List<String?>> = flowOf(variants)
}
