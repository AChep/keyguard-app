package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.service.dirs.DirsService
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.common.usecase.GpgKeyPrivateExport
import com.artemchep.keyguard.util.io.writeText

class GpgKeyPrivateExportImpl(
    private val dirsService: DirsService,
    private val dateFormatter: DateFormatter,
) : GpgKeyPrivateExport {
    override fun invoke(
        request: GpgKeyPrivateExport.Request,
    ): IO<String?> = ioEffect {
        val fileName = buildString {
            append("gpg_")
            append(request.fingerprint.gpgFileSafeSuffix())
            append('_')
            append(dateFormatter.gpgExportDateSuffix())
            append(".private.asc")
        }
        dirsService.saveToDownloads(fileName) { os ->
            os.writeText(request.privateKeyArmored)
        }.bind()
    }
}
