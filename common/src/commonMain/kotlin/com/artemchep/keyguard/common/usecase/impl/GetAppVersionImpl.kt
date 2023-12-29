package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.usecase.GetAppBuildType
import com.artemchep.keyguard.common.usecase.GetAppVersion
import com.artemchep.keyguard.common.usecase.GetAppVersionCode
import com.artemchep.keyguard.common.usecase.GetAppVersionName
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GetAppVersionImpl(
    private val getAppBuildType: GetAppBuildType,
    private val getAppVersionCode: GetAppVersionCode,
    private val getAppVersionName: GetAppVersionName,
) : GetAppVersion {
    constructor(directDI: DirectDI) : this(
        getAppBuildType = directDI.instance(),
        getAppVersionCode = directDI.instance(),
        getAppVersionName = directDI.instance(),
    )

    override fun invoke(): Flow<String> = combine(
        getAppBuildType(),
        getAppVersionCode(),
        getAppVersionName(),
    ) { buildType, versionCode, versionName ->
        "$versionName-$versionCode.$buildType"
    }
}
