package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.service.backup.BackupConfig
import com.artemchep.keyguard.common.service.backup.BackupObjectStoreFactory
import com.artemchep.keyguard.common.service.backup.BackupObjectStoreTestResult
import com.artemchep.keyguard.common.usecase.TestBackupLocation
import org.kodein.di.DirectDI
import org.kodein.di.instance

class TestBackupLocationImpl(
    private val backupObjectStoreFactory: BackupObjectStoreFactory,
) : TestBackupLocation {
    constructor(
        directDI: DirectDI,
    ) : this(
        backupObjectStoreFactory = directDI.instance(),
    )

    override fun invoke(
        config: BackupConfig,
    ): IO<BackupObjectStoreTestResult> = ioEffect {
        backupObjectStoreFactory.test(config.store)
    }
}
