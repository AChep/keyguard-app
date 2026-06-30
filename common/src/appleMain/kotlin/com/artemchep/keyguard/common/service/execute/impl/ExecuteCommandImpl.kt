package com.artemchep.keyguard.common.service.execute.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.service.execute.ExecuteCommand
import org.kodein.di.DirectDI

class ExecuteCommandImpl(
) : ExecuteCommand {
    override val interpreter: String? get() = null

    constructor(
        directDI: DirectDI,
    ) : this(
    )

    override fun invoke(command: String): IO<Unit> = ioEffect {
        throw UnsupportedOperationException("Command execution is not supported on iOS.")
    }
}
