package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.backup.BackupConfig
import com.artemchep.keyguard.common.service.backup.BackupObjectStoreTestResult

interface TestBackupLocation : (BackupConfig) -> IO<BackupObjectStoreTestResult>
