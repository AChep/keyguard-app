package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.usecase.GetCanWrite
import com.artemchep.keyguard.common.usecase.GetPurchased
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GetCanWriteImpl(
    private val getPurchased: GetPurchased,
) : GetCanWrite {
    constructor(directDI: DirectDI) : this(
        getPurchased = directDI.instance(),
    )

    override fun invoke() = getPurchased()
}
