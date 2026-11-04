package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.service.backup.BackupRunService
import com.artemchep.keyguard.common.service.backup.BackupStatus
import com.artemchep.keyguard.common.usecase.UpgradeBackupEncryption

class UpgradeBackupEncryptionImpl(
    private val backupRunService: BackupRunService,
) : UpgradeBackupEncryption {
    override fun invoke(): IO<BackupStatus> = ioEffect {
        backupRunService.upgradeEncryption()
    }
}
