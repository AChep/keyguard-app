package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.model.AppColors
import com.artemchep.keyguard.common.usecase.GetColorsVariants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class GetColorsVariantsImpl : GetColorsVariants {
    private val variants = mutableListOf<AppColors?>().apply {
        this += null
        this += AppColors.entries
    }

    override fun invoke(): Flow<List<AppColors?>> = flowOf(variants)
}
