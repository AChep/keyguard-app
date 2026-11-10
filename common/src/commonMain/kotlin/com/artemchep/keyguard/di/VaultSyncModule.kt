package com.artemchep.keyguard.di

import com.artemchep.keyguard.common.service.exposedaccount.ExposedAccountSyncer
import com.artemchep.keyguard.common.service.exposedaccount.impl.ExposedAccountSyncerImpl
import com.artemchep.keyguard.common.service.gpgagent.GpgPublicKeySyncer
import com.artemchep.keyguard.common.service.gpgagent.impl.GpgPublicKeySyncerImpl
import com.artemchep.keyguard.common.service.sshagent.SshAgentPublicKeySyncer
import com.artemchep.keyguard.common.service.sshagent.bookmarks.SshBookmarksSource
import com.artemchep.keyguard.common.service.sshagent.bookmarks.impl.SshBookmarksSourceImpl
import com.artemchep.keyguard.common.service.sshagent.impl.SshAgentPublicKeySyncerImpl
import com.artemchep.keyguard.common.service.urlblock.UrlBlockExposedSyncer
import com.artemchep.keyguard.common.service.urlblock.impl.UrlBlockExposedSyncerImpl
import com.artemchep.keyguard.common.usecase.GetSshAgentCiphers
import com.artemchep.keyguard.common.usecase.SupervisorRead
import com.artemchep.keyguard.common.usecase.SyncAll
import com.artemchep.keyguard.common.usecase.SyncById
import com.artemchep.keyguard.common.usecase.Watchdog
import com.artemchep.keyguard.common.usecase.WatchdogImpl
import com.artemchep.keyguard.common.usecase.impl.GetSshAgentCiphersImpl
import com.artemchep.keyguard.core.store.DatabaseSyncer
import com.artemchep.keyguard.feature.p2psync.crypto.P2pCrypto
import com.artemchep.keyguard.provider.bitwarden.sync.v2.bitwarden.SyncByBitwardenTokenV2Impl
import com.artemchep.keyguard.provider.bitwarden.usecase.SyncAllImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.SyncByIdImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.SyncByBitwardenToken
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.SyncByKeePassToken
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.SyncByKeePassTokenImpl
import com.artemchep.keyguard.common.service.appsync.AppSyncRecorder
import com.artemchep.keyguard.provider.keyguard.KeyguardAuthorityKeys
import com.artemchep.keyguard.provider.keyguard.KeyguardRosterAlertsImpl
import com.artemchep.keyguard.provider.keyguard.P2pGroupHosts
import com.artemchep.keyguard.provider.keyguard.QueueP2pSync
import com.artemchep.keyguard.provider.keyguard.applink.AppLinkComponents
import com.artemchep.keyguard.provider.keyguard.applink.AppLinkLauncher
import com.artemchep.keyguard.provider.keyguard.applink.AppLinkService
import com.artemchep.keyguard.provider.keyguard.applink.AppLinkSync
import com.artemchep.keyguard.provider.keyguard.applink.AppLinkWorker
import com.artemchep.keyguard.provider.keyguard.applink.AppLinkDeviceSettings
import com.artemchep.keyguard.provider.keyguard.applink.AppSyncSwitches
import com.artemchep.keyguard.provider.keyguard.crypto.NativeP2pCrypto
import com.artemchep.keyguard.provider.keyguard.enroll.KeyguardEnrollmentCoordinator
import com.artemchep.keyguard.provider.keyguard.session.KeyguardSessionLauncher
import com.artemchep.keyguard.provider.keyguard.session.KeyguardSessionRegistry
import com.artemchep.keyguard.provider.keyguard.sync.KeyguardSyncExchange
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.SyncByToken
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.SyncByTokenImpl
import org.koin.dsl.bind
import org.koin.dsl.binds
import org.koin.dsl.module
import org.koin.dsl.onClose
import org.koin.plugin.module.dsl.scoped

internal class VaultSyncModule {
    val module = module {
        scope<VaultSessionScope> {
            scoped<ExposedAccountSyncerImpl> {
                ExposedAccountSyncerImpl(
                    getProfiles = get(),
                    exposedAccountRepository = get(),
                    logRepository = get(),
                )
            } bind ExposedAccountSyncer::class

            scoped<GetSshAgentCiphersImpl> {
                GetSshAgentCiphersImpl(
                    filterContext = get(),
                    getCiphers = get(),
                    getSshAgentFilter = get(),
                    windowCoroutineScope = get(),
                )
            } bind GetSshAgentCiphers::class

            scoped<SshAgentPublicKeySyncerImpl> {
                SshAgentPublicKeySyncerImpl(
                    getSshAgentCiphers = get(),
                    getSshAgent = get(),
                    getSshAgentDisplayKeyNames = get(),
                    sshAgentPublicKeyRepository = get(),
                    cryptoGenerator = get(),
                    base64Service = get(),
                    logRepository = get(),
                )
            } bind SshAgentPublicKeySyncer::class

            scoped<UrlBlockExposedSyncerImpl> {
                UrlBlockExposedSyncerImpl(
                    urlBlockRepository = get(),
                    urlBlockRepositoryExposed = get(),
                    logRepository = get(),
                )
            } bind UrlBlockExposedSyncer::class

            scoped<SshBookmarksSourceImpl> {
                SshBookmarksSourceImpl(
                    getCiphers = get(),
                    getSshAgentCiphers = get(),
                    sshCertificateParser = get(),
                    cryptoGenerator = get(),
                    base64Service = get(),
                )
            } bind SshBookmarksSource::class

            scoped<GpgPublicKeySyncer> {
                GpgPublicKeySyncerImpl(
                    filterContext = get(),
                    getCiphers = get(),
                    getGpgAgent = get(),
                    getGpgAgentFilter = get(),
                    getGpgAgentDisplayKeyNames = get(),
                    gpgPublicKeyRepository = get(),
                    logRepository = get(),
                    gpgKeyMetadataResolver = get(),
                )
            }

            scoped<SyncAllImpl>() bind SyncAll::class

            scoped<SyncByIdImpl>() bind SyncById::class

            scoped<SyncByTokenImpl> {
                SyncByTokenImpl(
                    syncByBitwardenToken = get(),
                    syncByKeePassToken = get(),
                    pendingUploadGarbageCollector = get(),
                )
            } bind SyncByToken::class

            scoped<SyncByBitwardenToken> {
                SyncByBitwardenTokenV2Impl(
                    logRepository = get(),
                    cipherEncryptor = get(),
                    cryptoGenerator = get(),
                    base64Service = get(),
                    getPasswordStrength = get(),
                    gpgKeyMetadataResolver = getOrNull(),
                    json = get(),
                    httpClientProvider = get(),
                    db = get(),
                    dbSyncer = get(),
                    pendingUploadCoordinator = get(),
                    watchdog = get(),
                    markBackupAsDirty = get(),
                    gpgCertificateMaterialReconciler = get(),
                )
            }

            scoped<SyncByKeePassToken> {
                SyncByKeePassTokenImpl(
                    logRepository = get(),
                    cryptoGenerator = get(),
                    base32Service = get(),
                    base64Service = get(),
                    fileService = get(),
                    getPasswordStrength = get(),
                    gpgCertificateMaterialReconciler = get(),
                    gpgKeyMetadataResolver = getOrNull(),
                    json = get(),
                    db = get(),
                    pendingUploadCoordinator = get(),
                    stagingSpoolFactory = get(),
                    webDavClientFactory = get(),
                    s3ClientFactory = get(),
                    watchdog = get(),
                )
            }

            scoped<NativeP2pCrypto>() bind P2pCrypto::class

            scoped<KeyguardAuthorityKeys> {
                KeyguardAuthorityKeys(
                    cryptoGenerator = get(),
                    cipherEncryptor = get(),
                )
            }

            // Linked apps, wired once.
            scoped<AppLinkComponents> {
                AppLinkComponents(
                    db = get(),
                    crypto = get(),
                    cryptoGenerator = get(),
                    authorityKeys = get(),
                    settings = get(),
                    getPurchased = get(),
                    roomConnector = get(),
                    compressor = get(),
                    connectivityService = get(),
                    logRepository = get(),
                    rosterAlerts = KeyguardRosterAlertsImpl(
                        crypto = get(),
                        context = get(),
                        showNotification = get(),
                    ),
                    relayMailboxes = get(),
                )
            } onClose { it?.close() }

            scoped<AppSyncSwitches> {
                get<AppLinkComponents>().switches
            }

            scoped<AppLinkDeviceSettings> {
                get<AppLinkComponents>().deviceSettings
            }

            scoped<AppSyncRecorder> {
                get<AppLinkComponents>().recorder
            }

            scoped<AppLinkService> {
                get<AppLinkComponents>().service
            }

            scoped<P2pGroupHosts> {
                get<AppLinkComponents>().hosts
            } bind QueueP2pSync::class

            scoped<AppLinkSync> {
                get<AppLinkComponents>().sync
            } bind AppLinkLauncher::class

            // The one owner of the connections of the sync groups.
            scoped<KeyguardSessionRegistry> {
                get<AppLinkComponents>().registry
            } binds arrayOf(
                KeyguardSessionLauncher::class,
                KeyguardSyncExchange::class,
            )

            scoped<KeyguardEnrollmentCoordinator> {
                get<AppLinkComponents>().enrollment
            }

            scoped<AppLinkWorker>()

            scoped<WatchdogImpl>()

            scoped<Watchdog> {
                get<WatchdogImpl>()
            }

            scoped<SupervisorRead> {
                get<WatchdogImpl>()
            }

            scoped<DatabaseSyncer> {
                DatabaseSyncer(
                    cryptoGenerator = get(),
                )
            }
        }
    }
}
