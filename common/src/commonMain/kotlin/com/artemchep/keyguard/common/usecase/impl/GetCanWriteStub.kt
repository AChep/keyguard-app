package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.usecase.GetCanWrite
import kotlinx.coroutines.flow.flowOf

class GetCanWriteStub : GetCanWrite {
    override fun invoke() = flowOf(false)
}
