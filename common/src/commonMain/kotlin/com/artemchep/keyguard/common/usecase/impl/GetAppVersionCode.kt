package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.build.BuildKonfig
import com.artemchep.keyguard.common.usecase.GetAppVersionCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class GetAppVersionCodeImpl : GetAppVersionCode {
    override fun invoke(): Flow<Int> = flowOf(BuildKonfig.versionCode)
}
