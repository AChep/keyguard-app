package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.exception.watchtower.TwoFaDisabledException
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.flatMap
import com.artemchep.keyguard.common.io.ioRaise
import com.artemchep.keyguard.common.io.toIO
import com.artemchep.keyguard.common.service.passkey.PassKeyService
import com.artemchep.keyguard.common.service.passkey.PassKeyServiceInfo
import com.artemchep.keyguard.common.usecase.GetCheckPasskeys
import com.artemchep.keyguard.common.usecase.GetPasskeys
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GetPasskeysImpl(
    private val passKeyService: PassKeyService,
    private val getCheckPasskeys: GetCheckPasskeys,
) : GetPasskeys {
    constructor(directDI: DirectDI) : this(
        passKeyService = directDI.instance(),
        getCheckPasskeys = directDI.instance(),
    )

    override fun invoke(): IO<List<PassKeyServiceInfo>> = getCheckPasskeys()
        .toIO()
        .flatMap { enabled ->
            if (!enabled) {
                val e = TwoFaDisabledException()
                return@flatMap ioRaise(e)
            }

            passKeyService.get()
        }
}
