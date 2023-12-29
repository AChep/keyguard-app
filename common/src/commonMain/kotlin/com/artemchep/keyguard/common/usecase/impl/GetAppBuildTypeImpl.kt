package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.build.BuildKonfig
import com.artemchep.keyguard.common.usecase.GetAppBuildType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.kodein.di.DirectDI

class GetAppBuildTypeImpl(
) : GetAppBuildType {
    constructor(directDI: DirectDI) : this(
    )

    override fun invoke(): Flow<String> = flowOf(BuildKonfig.buildType.lowercase())
}
