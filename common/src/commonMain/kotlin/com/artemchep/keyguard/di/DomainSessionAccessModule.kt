package com.artemchep.keyguard.di

import com.artemchep.keyguard.common.service.session.AccountSessionAccess
import com.artemchep.keyguard.common.service.session.AppWorkerSessionAccess
import com.artemchep.keyguard.common.service.session.AppWorkerSessionDependencies
import com.artemchep.keyguard.common.service.session.AttachmentSessionAccess
import com.artemchep.keyguard.common.service.session.BackupConfigSessionAccess
import com.artemchep.keyguard.common.service.session.BackupRunnerSessionAccess
import com.artemchep.keyguard.common.service.session.GpgAgentSessionAccess
import com.artemchep.keyguard.common.service.session.GpgAgentSessionDependencies
import com.artemchep.keyguard.common.service.session.PendingUsageHistorySessionAccess
import com.artemchep.keyguard.common.service.session.SshAgentSessionAccess
import com.artemchep.keyguard.common.service.session.SshAgentSessionDependencies
import com.artemchep.keyguard.common.service.session.VaultLicenseSessionAccess
import com.artemchep.keyguard.common.service.session.WatchtowerSessionAccess
import com.artemchep.keyguard.common.service.session.WatchtowerSessionWorkers
import com.artemchep.keyguard.common.usecase.impl.WatchtowerBroadUris
import com.artemchep.keyguard.common.usecase.impl.WatchtowerClient
import com.artemchep.keyguard.common.usecase.impl.WatchtowerDuplicateUris
import com.artemchep.keyguard.common.usecase.impl.WatchtowerExpiring
import com.artemchep.keyguard.common.usecase.impl.WatchtowerGpgKeyPublishing
import com.artemchep.keyguard.common.usecase.impl.WatchtowerGpgKeyUnusable
import com.artemchep.keyguard.common.usecase.impl.WatchtowerInactivePasskey
import com.artemchep.keyguard.common.usecase.impl.WatchtowerInactiveTfa
import com.artemchep.keyguard.common.usecase.impl.WatchtowerIncomplete
import com.artemchep.keyguard.common.usecase.impl.WatchtowerNotifications
import com.artemchep.keyguard.common.usecase.impl.WatchtowerPasswordPwned
import com.artemchep.keyguard.common.usecase.impl.WatchtowerPasswordStrength
import com.artemchep.keyguard.common.usecase.impl.WatchtowerSshKeyStrength
import com.artemchep.keyguard.common.usecase.impl.WatchtowerUnsecureWebsite
import com.artemchep.keyguard.common.usecase.impl.WatchtowerWeakGpgKey
import com.artemchep.keyguard.common.usecase.impl.WatchtowerWebsitePwned
import kotlinx.coroutines.Dispatchers
import org.koin.dsl.module

/** Adapts the active vault scope to the narrow dependency contracts used by services. */
class DomainSessionAccessModule {
    val module = module {
        single<AppWorkerSessionAccess> {
            AppWorkerSessionAccess { key ->
                key.session.resolve {
                    AppWorkerSessionDependencies(
                        notificationsWorker = get(),
                        exposedAccountSyncer = get(),
                        sshAgentPublicKeySyncer = get(),
                        gpgPublicKeySyncer = get(),
                        gpgKeyserverRefreshWorker = get(),
                        licenseSyncer = get(),
                    )
                }
            }
        }
        single<PendingUsageHistorySessionAccess> {
            PendingUsageHistorySessionAccess { key -> key.session.resolve { get() } }
        }
        single<AccountSessionAccess> {
            AccountSessionAccess { key -> key.session.resolve { get() } }
        }
        single<VaultLicenseSessionAccess> {
            VaultLicenseSessionAccess { key -> key.session.resolve { get() } }
        }
        single<AttachmentSessionAccess> {
            AttachmentSessionAccess { key -> key.session.resolve { get() } }
        }
        single<BackupConfigSessionAccess> {
            BackupConfigSessionAccess { key -> key.session.resolve { get() } }
        }
        single<BackupRunnerSessionAccess> {
            BackupRunnerSessionAccess { key -> key.session.resolve { get() } }
        }
        single<SshAgentSessionAccess> {
            SshAgentSessionAccess { key ->
                key.session.resolve {
                    SshAgentSessionDependencies(
                        getCiphers = get(),
                        addSshUsageHistory = getOrNull(),
                        filterContext = get(),
                    )
                }
            }
        }
        single<GpgAgentSessionAccess> {
            GpgAgentSessionAccess { key ->
                key.session.resolve {
                    GpgAgentSessionDependencies(
                        getCiphers = get(),
                        addGpgUsageHistory = getOrNull(),
                        metadataResolver = getOrNull(),
                        filterContext = get(),
                    )
                }
            }
        }
        single<WatchtowerSessionAccess> {
            WatchtowerSessionAccess { key ->
                key.session.resolve {
                    val client = WatchtowerClient(
                        getBreaches = get(),
                        getCipherSnapshots = get(),
                        databaseManager = get(),
                        logRepository = get(),
                        syncSupervisor = get(),
                        list = listOf(
                            get<WatchtowerInactivePasskey>(),
                            get<WatchtowerInactiveTfa>(),
                            get<WatchtowerDuplicateUris>(),
                            get<WatchtowerBroadUris>(),
                            get<WatchtowerPasswordStrength>(),
                            get<WatchtowerSshKeyStrength>(),
                            get<WatchtowerGpgKeyUnusable>(),
                            get<WatchtowerWeakGpgKey>(),
                            get<WatchtowerGpgKeyPublishing>(),
                            get<WatchtowerPasswordPwned>(),
                            get<WatchtowerWebsitePwned>(),
                            get<WatchtowerIncomplete>(),
                            get<WatchtowerExpiring>(),
                            get<WatchtowerUnsecureWebsite>(),
                        ),
                        defaultDispatcher = Dispatchers.Default.limitedParallelism(1),
                        dbDispatcher = databaseDispatcher(),
                    )
                    val notifications = WatchtowerNotifications(
                        context = get(),
                        getWatchtowerUnreadAlerts = get(),
                        getProfiles = get(),
                        showNotification = get(),
                        cryptoGenerator = get(),
                    )
                    WatchtowerSessionWorkers(client::launch, notifications::launch)
                }
            }
        }
    }
}
