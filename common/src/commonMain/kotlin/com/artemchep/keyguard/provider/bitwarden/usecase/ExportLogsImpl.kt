package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.service.dirs.DirsService
import com.artemchep.keyguard.common.service.zip.ZipConfig
import com.artemchep.keyguard.common.service.zip.ZipEntry
import com.artemchep.keyguard.common.service.zip.ZipService
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.common.usecase.ExportLogs
import com.artemchep.keyguard.common.usecase.GetInMemoryLogs
import kotlinx.coroutines.flow.first
import kotlinx.datetime.Clock
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class ExportLogsImpl(
    private val dirsService: DirsService,
    private val zipService: ZipService,
    private val dateFormatter: DateFormatter,
    private val getInMemoryLogs: GetInMemoryLogs,
) : ExportLogs {
    companion object {
        private const val TAG = "ExportLogs.bitwarden"
    }

    constructor(directDI: DirectDI) : this(
        dirsService = directDI.instance(),
        zipService = directDI.instance(),
        dateFormatter = directDI.instance(),
        getInMemoryLogs = directDI.instance(),
    )

    override fun invoke(
    ): IO<Unit> = ioEffect {
        val logs = getInMemoryLogs()
            .first()

        // Map log data to the plain text
        val txt = kotlin.run {
            logs
                .joinToString(separator = "\n") { log ->
                    val dateTime = dateFormatter.formatDateTimeMachine(log.createdAt)
                    "$dateTime  ${log.level.letter} ${log.tag} ${log.message}"
                }
        }

        val fileName = kotlin.run {
            val now = Clock.System.now()
            val dt = dateFormatter.formatDateTimeMachine(now)
            "keyguard_logs_$dt.zip"
        }
        dirsService.saveToDownloads(fileName) { os ->
            zipService.zip(
                outputStream = os,
                config = ZipConfig(
                ),
                entries = listOf(
                    ZipEntry(
                        name = "logs.txt",
                        stream = {
                            txt.byteInputStream()
                        },
                    ),
                ),
            )
        }.bind()
    }

}
