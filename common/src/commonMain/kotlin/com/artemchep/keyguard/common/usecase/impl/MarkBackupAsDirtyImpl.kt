package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.backup.BackupConfigRepository
import com.artemchep.keyguard.common.service.backup.BackupStatus
import com.artemchep.keyguard.common.usecase.MarkBackupAsDirty

class MarkBackupAsDirtyImpl(
    private val backupConfigRepository: BackupConfigRepository,
) : MarkBackupAsDirty {
    override fun invoke(): IO<BackupStatus> = backupConfigRepository
        .markDirty()
}
