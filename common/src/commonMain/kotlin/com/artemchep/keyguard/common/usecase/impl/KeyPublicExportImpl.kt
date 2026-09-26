package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.KeyPair
import com.artemchep.keyguard.common.service.dirs.DirsService
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.common.usecase.KeyPublicExport
import com.artemchep.keyguard.util.io.writeText
import kotlin.time.Clock

class KeyPublicExportImpl(
    private val dirsService: DirsService,
    private val dateFormatter: DateFormatter,
) : KeyPublicExport {
    override fun invoke(
        parameter: KeyPair.KeyParameter,
    ): IO<String?> = ioEffect {
        val filePrefix = "id_${parameter.type.key}"
        val fileName = kotlin.run {
            val now = Clock.System.now()
            val dt = dateFormatter.formatDateTimeMachine(now)
            "${filePrefix}_$dt.pub"
        }
        dirsService.saveToDownloads(fileName) { os ->
            os.writeText(parameter.ssh)
        }.bind()
    }
}
