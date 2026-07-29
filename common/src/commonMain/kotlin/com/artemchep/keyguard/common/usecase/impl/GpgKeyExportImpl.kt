package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.GeneratedGpgKey
import com.artemchep.keyguard.common.service.dirs.DirsService
import com.artemchep.keyguard.common.service.zip.ZipConfig
import com.artemchep.keyguard.common.service.zip.ZipEntry
import com.artemchep.keyguard.common.service.zip.ZipService
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.common.usecase.GpgKeyExport
import com.artemchep.keyguard.util.io.writeText
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GpgKeyExportImpl(
    private val dirsService: DirsService,
    private val zipService: ZipService,
    private val dateFormatter: DateFormatter,
) : GpgKeyExport {
    constructor(
        directDI: DirectDI,
    ) : this(
        dirsService = directDI.instance(),
        zipService = directDI.instance(),
        dateFormatter = directDI.instance(),
    )

    override fun invoke(
        key: GeneratedGpgKey,
    ): IO<String?> = ioEffect {
        val filePrefix = "gpg_${key.fingerprint.gpgFileSafeSuffix()}"
        val fileName = "${filePrefix}_${dateFormatter.gpgExportDateSuffix()}.zip"
        dirsService.saveToDownloads(fileName) { os ->
            val entries = listOf(
                ZipEntry(
                    name = "$filePrefix.public.asc",
                    data = ZipEntry.Data.Out { sink ->
                        sink.writeText(key.publicKeyArmored)
                    },
                ),
                ZipEntry(
                    name = "$filePrefix.private.asc",
                    data = ZipEntry.Data.Out { sink ->
                        sink.writeText(key.privateKeyArmored)
                    },
                ),
            )
            zipService.zip(
                outputStream = os,
                config = ZipConfig(
                    encryption = null,
                ),
                entries = entries,
            )
        }.bind()
    }
}
