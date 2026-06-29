package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.usecase.GetAppBuildRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

object GetAppBuildRefIos : GetAppBuildRef {
    override fun invoke(): Flow<String> = flowOf("unknown")
}
