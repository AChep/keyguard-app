package com.artemchep.keyguard.copy

import com.artemchep.keyguard.build.BuildKonfig
import com.artemchep.keyguard.common.usecase.GetAppBuildRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.kodein.di.DirectDI

class GetAppBuildRefImpl(
) : GetAppBuildRef {
    constructor(directDI: DirectDI) : this(
    )

    override fun invoke(): Flow<String> = flowOf(BuildKonfig.buildRef)
}
