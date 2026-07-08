package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.GeneratedGpgKey
import com.artemchep.keyguard.common.service.dirs.DirsService
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.common.usecase.GpgKeyPrivateExport
import com.artemchep.keyguard.util.foundation.io.writeText
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GpgKeyPrivateExportImpl(
    private val dirsService: DirsService,
    private val dateFormatter: DateFormatter,
) : GpgKeyPrivateExport {
    constructor(
        directDI: DirectDI,
    ) : this(
        dirsService = directDI.instance(),
        dateFormatter = directDI.instance(),
    )

    override fun invoke(
        parameter: GeneratedGpgKey,
    ): IO<String?> = ioEffect {
        val fileName = "gpg_${parameter.fingerprint.gpgFileSafeSuffix()}_${dateFormatter.gpgExportDateSuffix()}.private.asc"
        dirsService.saveToDownloads(fileName) { os ->
            os.writeText(parameter.privateKeyArmored)
        }.bind()
    }
}
