package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.KeyPair
import com.artemchep.keyguard.common.service.dirs.DirsService
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.common.usecase.KeyPairExport
import com.artemchep.keyguard.util.io.writeText
import com.artemchep.keyguard.util.zip.ZipConfig
import com.artemchep.keyguard.util.zip.ZipEntry
import com.artemchep.keyguard.util.zip.ZipService
import kotlin.time.Clock

class KeyPairExportImpl(
    private val dirsService: DirsService,
    private val zipService: ZipService,
    private val dateFormatter: DateFormatter,
) : KeyPairExport {
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
                    data = ZipEntry.Data.Out { sink ->
                        sink.writeText(keyPair.publicKey.ssh)
                    },
                ),
                ZipEntry(
                    name = filePrefix,
                    data = ZipEntry.Data.Out { sink ->
                        sink.writeText(keyPair.privateKey.ssh)
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
