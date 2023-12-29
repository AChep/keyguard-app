package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.usecase.GetCanWrite
import kotlinx.coroutines.flow.flowOf
import org.kodein.di.DirectDI

class GetCanWriteStub() : GetCanWrite {
    constructor(directDI: DirectDI) : this()

    override fun invoke() = flowOf(false)
}
