package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.usecase.GetAppBuildDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

object GetAppBuildDateIos : GetAppBuildDate {
    override fun invoke(): Flow<String> = flowOf("unknown")
}
