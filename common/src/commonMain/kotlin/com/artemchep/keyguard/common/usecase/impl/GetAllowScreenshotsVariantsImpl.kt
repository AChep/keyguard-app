package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.model.AllowScreenshots
import com.artemchep.keyguard.common.usecase.GetAllowScreenshotsVariants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class GetAllowScreenshotsVariantsImpl : GetAllowScreenshotsVariants {
    override fun invoke(): Flow<List<AllowScreenshots>> = flowOf(AllowScreenshots.entries)
}
