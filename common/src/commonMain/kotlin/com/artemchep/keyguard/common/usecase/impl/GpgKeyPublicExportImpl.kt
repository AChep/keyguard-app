package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.service.dirs.DirsService
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.common.usecase.GpgKeyPublicExport
import com.artemchep.keyguard.util.io.writeText
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GpgKeyPublicExportImpl(
    private val dirsService: DirsService,
    private val dateFormatter: DateFormatter,
) : GpgKeyPublicExport {
    constructor(
        directDI: DirectDI,
    ) : this(
        dirsService = directDI.instance(),
        dateFormatter = directDI.instance(),
    )

    override fun invoke(
        request: GpgKeyPublicExport.Request,
    ): IO<String?> = ioEffect {
        val fileName = "gpg_${request.fingerprint.gpgFileSafeSuffix()}_${dateFormatter.gpgExportDateSuffix()}.public.asc"
        dirsService.saveToDownloads(fileName) { os ->
            os.writeText(request.publicKeyArmored)
        }.bind()
    }
}
