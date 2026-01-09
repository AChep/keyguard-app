package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.model.TotpCode
import com.artemchep.keyguard.common.model.TotpToken
import com.artemchep.keyguard.common.usecase.GetTotpCode
import com.artemchep.keyguard.common.usecase.GetTotpCodeWithOffset
import kotlinx.coroutines.flow.Flow
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GetTotpCodeImpl(
    private val getTotpCodeWithOffset: GetTotpCodeWithOffset,
) : GetTotpCode {
    constructor(directDI: DirectDI) : this(
        getTotpCodeWithOffset = directDI.instance(),
    )

    override fun invoke(
        token: TotpToken,
    ): Flow<TotpCode> = getTotpCodeWithOffset(token, 0)
}
