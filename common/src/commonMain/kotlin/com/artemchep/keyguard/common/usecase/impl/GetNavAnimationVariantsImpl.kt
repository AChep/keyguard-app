package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.model.NavAnimation
import com.artemchep.keyguard.common.usecase.GetNavAnimationVariants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.kodein.di.DirectDI

class GetNavAnimationVariantsImpl() : GetNavAnimationVariants {
    constructor(directDI: DirectDI) : this()

    private val variants = NavAnimation.entries

    override fun invoke(): Flow<List<NavAnimation>> = flowOf(variants)
}
