package com.artemchep.keyguard.apple.settings

import com.artemchep.keyguard.appleKoinModules
import com.artemchep.keyguard.common.service.backup.AppleFolderBackupObjectStoreFactory
import com.artemchep.keyguard.common.service.backup.BackupLocalObjectStoreFactoryTag
import com.artemchep.keyguard.common.service.backup.BackupObjectStoreFactory
import com.artemchep.keyguard.common.service.backup.BackupRepository
import com.artemchep.keyguard.common.service.backup.BackupRepositoryZipImpl
import com.artemchep.keyguard.common.service.backup.BackupRunService
import com.artemchep.keyguard.common.service.backup.BackupSchedulerWorker
import com.artemchep.keyguard.common.service.backup.SelectableBackupObjectStoreFactory
import com.artemchep.keyguard.common.usecase.RunBackupNow
import com.artemchep.keyguard.common.usecase.TestBackupLocation
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import org.koin.core.qualifier.named
import org.koin.dsl.koinApplication

class AppleBackupModuleTest {
    @Test
    fun nativeAppResolvesBackupEntryPoints() {
        val koin = koinApplication { modules(appleKoinModules()) }.koin
        try {
            assertIs<AppleFolderBackupObjectStoreFactory>(
                koin.get<BackupObjectStoreFactory>(named(BackupLocalObjectStoreFactoryTag)),
            )
            assertIs<SelectableBackupObjectStoreFactory>(koin.get<BackupObjectStoreFactory>())
            assertIs<BackupRepositoryZipImpl>(koin.get<BackupRepository>())
            assertNotNull(koin.get<BackupRunService>())
            assertNotNull(koin.get<RunBackupNow>())
            assertNotNull(koin.get<TestBackupLocation>())
            assertNotNull(koin.get<BackupSchedulerWorker>())
        } finally {
            koin.close()
        }
    }
}
