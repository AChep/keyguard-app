package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.usecase.GetLocaleVariants
import com.artemchep.keyguard.build.LocaleConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.kodein.di.DirectDI

class GetLocaleVariantsImpl() : GetLocaleVariants {
    constructor(directDI: DirectDI) : this()

    private val variants = listOf<String?>(null) + LocaleConfig.locales

    override fun invoke(): Flow<List<String?>> = flowOf(variants)
}
