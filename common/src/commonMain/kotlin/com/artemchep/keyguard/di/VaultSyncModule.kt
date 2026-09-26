package com.artemchep.keyguard.di

import com.artemchep.keyguard.common.service.exposedaccount.ExposedAccountSyncer
import com.artemchep.keyguard.common.service.exposedaccount.impl.ExposedAccountSyncerImpl
import com.artemchep.keyguard.common.service.gpgagent.GpgPublicKeySyncer
import com.artemchep.keyguard.common.service.gpgagent.impl.GpgPublicKeySyncerImpl
import com.artemchep.keyguard.common.service.sshagent.SshAgentPublicKeySyncer
import com.artemchep.keyguard.common.service.sshagent.impl.SshAgentPublicKeySyncerImpl
import com.artemchep.keyguard.common.service.webdav.KtorWebDavClientFactory
import com.artemchep.keyguard.common.usecase.SupervisorRead
import com.artemchep.keyguard.common.usecase.SyncAll
import com.artemchep.keyguard.common.usecase.SyncById
import com.artemchep.keyguard.common.usecase.Watchdog
import com.artemchep.keyguard.common.usecase.WatchdogImpl
import com.artemchep.keyguard.core.store.DatabaseSyncer
import com.artemchep.keyguard.provider.bitwarden.sync.v2.bitwarden.SyncByBitwardenTokenV2Impl
import com.artemchep.keyguard.provider.bitwarden.usecase.SyncAllImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.SyncByIdImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.SyncByBitwardenToken
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.SyncByKeePassToken
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.SyncByKeePassTokenImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.SyncByToken
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.SyncByTokenImpl
import org.koin.dsl.bind
import org.koin.dsl.module
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

            scoped<SshAgentPublicKeySyncerImpl> {
                SshAgentPublicKeySyncerImpl(
                    filterContext = get(),
                    getCiphers = get(),
                    getSshAgent = get(),
                    getSshAgentFilter = get(),
                    getSshAgentDisplayKeyNames = get(),
                    sshAgentPublicKeyRepository = get(),
                    cryptoGenerator = get(),
                    base64Service = get(),
                    logRepository = get(),
                )
            } bind SshAgentPublicKeySyncer::class

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
                    httpClient = get(),
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
                    webDavClientFactory = KtorWebDavClientFactory(
                        httpClient = get(),
                    ),
                    watchdog = get(),
                )
            }

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
