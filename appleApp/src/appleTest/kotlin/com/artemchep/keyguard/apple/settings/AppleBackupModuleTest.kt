package com.artemchep.keyguard.apple.settings

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
import com.artemchep.keyguard.di.withKoin
import com.artemchep.keyguard.platform.withTestAppleStorage
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest
import org.koin.core.qualifier.named

class AppleBackupModuleTest {
    @Test
    fun nativeAppResolvesBackupEntryPoints() = runTest {
        withTestAppleStorage { directories ->
            directories.withKoin { koin ->
                assertIs<AppleFolderBackupObjectStoreFactory>(
                    koin.get<BackupObjectStoreFactory>(named(BackupLocalObjectStoreFactoryTag)),
                )
                assertIs<SelectableBackupObjectStoreFactory>(koin.get<BackupObjectStoreFactory>())
                assertIs<BackupRepositoryZipImpl>(koin.get<BackupRepository>())
                assertNotNull(koin.get<BackupRunService>())
                assertNotNull(koin.get<RunBackupNow>())
                assertNotNull(koin.get<TestBackupLocation>())
                assertNotNull(koin.get<BackupSchedulerWorker>())
            }
        }
    }
}
