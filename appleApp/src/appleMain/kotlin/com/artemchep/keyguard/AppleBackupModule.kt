package com.artemchep.keyguard

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.service.backup.AppleFolderBackupObjectStoreFactory
import com.artemchep.keyguard.common.service.backup.BackupConfigRepository
import com.artemchep.keyguard.common.service.backup.BackupLocalObjectStoreFactoryTag
import com.artemchep.keyguard.common.service.backup.BackupObjectStoreFactory
import com.artemchep.keyguard.common.service.backup.SelectableBackupObjectStoreFactory
import com.artemchep.keyguard.common.service.backup.S3BackupObjectStoreFactory
import com.artemchep.keyguard.common.service.backup.WebDavBackupObjectStoreFactory
import com.artemchep.keyguard.common.service.vault.SessionReadRepository
import com.artemchep.keyguard.di.resolve
import kotlinx.coroutines.flow.first
import org.koin.core.qualifier.named
import org.koin.dsl.module

/** Storage is global; vault data and persisted permissions remain session-scoped. */
internal class AppleBackupModule {
    val module = module {
        single<BackupObjectStoreFactory>(qualifier = named(BackupLocalObjectStoreFactoryTag)) {
            val sessions: SessionReadRepository = get()
            AppleFolderBackupObjectStoreFactory { expected, updated ->
                val session = sessions.get().first() as? MasterSession.Key
                session?.session?.resolve { get<BackupConfigRepository>() }
                    ?.refreshLocalAccess(expected, updated)?.bind()
            }
        }
        single<BackupObjectStoreFactory> {
            SelectableBackupObjectStoreFactory(
                localFactory = get(qualifier = named(BackupLocalObjectStoreFactoryTag)),
                webDavFactory = WebDavBackupObjectStoreFactory(
                    webDavClientFactory = get(),
                ),
                s3Factory = get<S3BackupObjectStoreFactory>(),
            )
        }
    }
}
