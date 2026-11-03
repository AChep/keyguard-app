package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.model.isActive
import com.artemchep.keyguard.common.usecase.GetCanWrite
import com.artemchep.keyguard.common.usecase.GetPurchased
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class GetCanWriteImpl(
    private val getPurchased: GetPurchased,
) : GetCanWrite {
    override fun invoke() = getPurchased()
        .map { it.isActive }
        .distinctUntilChanged()
}
