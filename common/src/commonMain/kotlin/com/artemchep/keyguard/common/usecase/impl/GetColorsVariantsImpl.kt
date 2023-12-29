package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.model.AppColors
import com.artemchep.keyguard.common.usecase.GetColorsVariants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.kodein.di.DirectDI

class GetColorsVariantsImpl() : GetColorsVariants {
    constructor(directDI: DirectDI) : this()

    private val variants = mutableListOf<AppColors?>().apply {
        this += null
        this += AppColors.entries
    }

    override fun invoke(): Flow<List<AppColors?>> = flowOf(variants)
}
