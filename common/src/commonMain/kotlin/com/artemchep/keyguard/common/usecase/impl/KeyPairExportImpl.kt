package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.KeyPair
import com.artemchep.keyguard.common.service.dirs.DirsService
import com.artemchep.keyguard.common.service.zip.ZipConfig
import com.artemchep.keyguard.common.service.zip.ZipEntry
import com.artemchep.keyguard.common.service.zip.ZipService
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.common.usecase.KeyPairExport
import kotlin.time.Clock
import org.kodein.di.DirectDI
import org.kodein.di.instance

class KeyPairExportImpl(
    private val dirsService: DirsService,
    private val zipService: ZipService,
    private val dateFormatter: DateFormatter,
) : KeyPairExport {
    constructor(
        directDI: DirectDI,
    ) : this(
        dirsService = directDI.instance(),
        zipService = directDI.instance(),
        dateFormatter = directDI.instance(),
    )

    override fun invoke(
        keyPair: KeyPair,
    ): IO<String?> = ioEffect {
        val filePrefix = "id_${keyPair.type.key}"
        val fileName = kotlin.run {
            val now = Clock.System.now()
            val dt = dateFormatter.formatDateTimeMachine(now)
            "${filePrefix}_$dt.zip"
        }
        dirsService.saveToDownloads(fileName) { os ->
            val entries = listOf(
                ZipEntry(
                    name = "$filePrefix.pub",
                    data = ZipEntry.Data.In {
                        keyPair.publicKey.ssh.byteInputStream()
                    },
                ),
                ZipEntry(
                    name = filePrefix,
                    data = ZipEntry.Data.In {
                        keyPair.privateKey.ssh.byteInputStream()
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
