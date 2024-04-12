package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.exception.watchtower.TwoFaDisabledException
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.flatMap
import com.artemchep.keyguard.common.io.ioRaise
import com.artemchep.keyguard.common.io.toIO
import com.artemchep.keyguard.common.service.twofa.TwoFaService
import com.artemchep.keyguard.common.service.twofa.TwoFaServiceInfo
import com.artemchep.keyguard.common.usecase.GetCheckTwoFA
import com.artemchep.keyguard.common.usecase.GetTwoFa
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GetTwoFaImpl(
    private val twoFaService: TwoFaService,
    private val getCheckTwoFA: GetCheckTwoFA,
) : GetTwoFa {
    constructor(directDI: DirectDI) : this(
        twoFaService = directDI.instance(),
        getCheckTwoFA = directDI.instance(),
    )

    override fun invoke(): IO<List<TwoFaServiceInfo>> = getCheckTwoFA()
        .toIO()
        .flatMap { enabled ->
            if (!enabled) {
                val e = TwoFaDisabledException()
                return@flatMap ioRaise(e)
            }

            twoFaService.get()
        }
}
