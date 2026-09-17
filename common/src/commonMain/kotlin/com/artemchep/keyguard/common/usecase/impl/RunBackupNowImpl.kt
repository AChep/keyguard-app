package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.service.backup.BackupRunService
import com.artemchep.keyguard.common.service.backup.BackupStatus
import com.artemchep.keyguard.common.usecase.RunBackupNow

class RunBackupNowImpl(
    private val backupRunService: BackupRunService,
) : RunBackupNow {
    override fun invoke(): IO<BackupStatus> = ioEffect {
        backupRunService.runManual()
    }
}
