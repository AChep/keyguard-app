package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.backup.BackupConfigRepository
import com.artemchep.keyguard.common.service.backup.BackupStatus
import com.artemchep.keyguard.common.usecase.MarkBackupAsDirty
import org.kodein.di.DirectDI
import org.kodein.di.instance

class MarkBackupAsDirtyImpl(
    private val backupConfigRepository: BackupConfigRepository,
) : MarkBackupAsDirty {
    constructor(directDI: DirectDI) : this(
        backupConfigRepository = directDI.instance(),
    )

    override fun invoke(): IO<BackupStatus> = backupConfigRepository
        .markDirty()
}
