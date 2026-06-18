package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.service.backup.BackupRunService
import com.artemchep.keyguard.common.service.backup.BackupStatus
import com.artemchep.keyguard.common.usecase.RunBackupNow
import org.kodein.di.DirectDI
import org.kodein.di.instance

class RunBackupNowImpl(
    private val backupRunService: BackupRunService,
) : RunBackupNow {
    constructor(
        directDI: DirectDI,
    ) : this(
        backupRunService = directDI.instance(),
    )

    override fun invoke(): IO<BackupStatus> = ioEffect {
        backupRunService.runManual()
    }
}
