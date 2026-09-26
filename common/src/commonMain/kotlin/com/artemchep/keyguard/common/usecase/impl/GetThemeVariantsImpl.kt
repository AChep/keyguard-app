package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.model.AppTheme
import com.artemchep.keyguard.common.usecase.GetThemeVariants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class GetThemeVariantsImpl : GetThemeVariants {
    private val variants = mutableListOf<AppTheme?>().apply {
        this += null
        this += AppTheme.entries
    }

    override fun invoke(): Flow<List<AppTheme?>> = flowOf(variants)
}
