package com.artemchep.keyguard.copy

import com.artemchep.keyguard.build.BuildKonfig
import com.artemchep.keyguard.common.usecase.GetAppBuildRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class GetAppBuildRefImpl : GetAppBuildRef {

    override fun invoke(): Flow<String> = flowOf(BuildKonfig.buildRef)
}
