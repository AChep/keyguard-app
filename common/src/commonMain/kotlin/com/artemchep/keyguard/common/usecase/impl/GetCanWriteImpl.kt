package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.usecase.GetCanWrite
import com.artemchep.keyguard.common.usecase.GetPurchased

class GetCanWriteImpl(
    private val getPurchased: GetPurchased,
) : GetCanWrite {
    override fun invoke() = getPurchased()
}
