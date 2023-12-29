package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.model.AppFont
import com.artemchep.keyguard.common.usecase.GetFontVariants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.kodein.di.DirectDI

class GetFontVariantsImpl() : GetFontVariants {
    constructor(directDI: DirectDI) : this()

    private val variants = mutableListOf<AppFont?>().apply {
        this += null
        this += AppFont.entries
    }

    override fun invoke(): Flow<List<AppFont?>> = flowOf(variants)
}
