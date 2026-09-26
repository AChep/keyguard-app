package com.artemchep.keyguard.di

import com.artemchep.keyguard.common.AppWorker
import com.artemchep.keyguard.common.AppWorkerIm
import com.artemchep.keyguard.common.TemporaryArtifactMaintenance
import com.artemchep.keyguard.common.TemporaryArtifactMaintenanceImpl
import com.artemchep.keyguard.common.TemporaryArtifactRoot
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.platformTemporaryArtifactRoots
import com.artemchep.keyguard.common.service.Files
import com.artemchep.keyguard.common.service.app.parser.AndroidAppFDroidParser
import com.artemchep.keyguard.common.service.app.parser.AndroidAppGooglePlayParser
import com.artemchep.keyguard.common.service.app.parser.IosAppAppStoreParser
import com.artemchep.keyguard.common.service.credentialexchange.CxfImportService
import com.artemchep.keyguard.common.service.credentialexchange.impl.CxfImportServiceImpl
import com.artemchep.keyguard.common.service.deeplink.DeeplinkService
import com.artemchep.keyguard.common.service.deeplink.impl.DeeplinkServiceImpl
import com.artemchep.keyguard.common.service.download.CacheDirProvider
import com.artemchep.keyguard.common.service.extract.LinkInfoExtractorRegistry
import com.artemchep.keyguard.common.service.extract.PlatformLinkInfoExtractorRegistry
import com.artemchep.keyguard.common.service.extract.impl.LinkInfoExtractorExecute
import com.artemchep.keyguard.common.service.extract.impl.LinkInfoPlatformExtractor
import com.artemchep.keyguard.common.service.gpmprivapps.PrivilegedAppsService
import com.artemchep.keyguard.common.service.gpmprivapps.impl.PrivilegedAppsServiceImpl
import com.artemchep.keyguard.common.service.justdeleteme.JustDeleteMeService
import com.artemchep.keyguard.common.service.justdeleteme.impl.JustDeleteMeServiceImpl
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStoreFactory
import com.artemchep.keyguard.common.service.localizationcontributors.LocalizationContributorsService
import com.artemchep.keyguard.common.service.localizationcontributors.impl.LocalizationContributorsServiceImpl
import com.artemchep.keyguard.common.service.passkey.PassKeyService
import com.artemchep.keyguard.common.service.passkey.impl.PassKeyServiceImpl
import com.artemchep.keyguard.common.service.pendinghistory.PendingUsageHistoryQueue
import com.artemchep.keyguard.common.service.pendinghistory.PendingUsageHistoryQueueImpl
import com.artemchep.keyguard.common.service.review.ReviewLog
import com.artemchep.keyguard.common.service.review.impl.ReviewLogImpl
import com.artemchep.keyguard.common.service.similarity.SimilarityService
import com.artemchep.keyguard.common.service.similarity.impl.SimilarityServiceImpl
import com.artemchep.keyguard.common.service.staging.StagingSpoolFactory
import com.artemchep.keyguard.common.service.staging.StagingSpoolObserver
import com.artemchep.keyguard.common.service.tld.TldService
import com.artemchep.keyguard.common.service.tld.impl.TldServiceImpl
import com.artemchep.keyguard.common.service.twofa.TwoFaService
import com.artemchep.keyguard.common.service.twofa.impl.TwoFaServiceImpl
import com.artemchep.keyguard.common.shouldLaunchPendingUsageHistoryFlush
import com.artemchep.keyguard.common.usecase.BlockedUrlCheck
import com.artemchep.keyguard.common.usecase.CipherUnsecureUrlCheck
import com.artemchep.keyguard.common.usecase.CipherUrlBroadCheck
import com.artemchep.keyguard.common.usecase.CipherUrlCheck
import com.artemchep.keyguard.common.usecase.CipherUrlDuplicateCheck
import com.artemchep.keyguard.common.usecase.DeviceEncryptionKeyUseCase
import com.artemchep.keyguard.common.usecase.DeviceIdUseCase
import com.artemchep.keyguard.common.usecase.DismissNotificationsByChannel
import com.artemchep.keyguard.common.usecase.PasskeyTargetCheck
import com.artemchep.keyguard.common.usecase.RequestAppReview
import com.artemchep.keyguard.common.usecase.SearchGpgPublicKey
import com.artemchep.keyguard.common.usecase.ShowNotification
import com.artemchep.keyguard.common.usecase.UnlockUseCase
import com.artemchep.keyguard.common.usecase.UpdateVersionLog
import com.artemchep.keyguard.common.usecase.WatchtowerSyncer
import com.artemchep.keyguard.common.usecase.impl.DismissNotificationsByChannelImpl
import com.artemchep.keyguard.common.usecase.impl.PasskeyTargetCheckImpl
import com.artemchep.keyguard.common.usecase.impl.RequestAppReviewImpl
import com.artemchep.keyguard.common.usecase.impl.SearchGpgPublicKeyImpl
import com.artemchep.keyguard.common.usecase.impl.ShowNotificationImpl
import com.artemchep.keyguard.common.usecase.impl.UnlockUseCaseImpl
import com.artemchep.keyguard.common.usecase.impl.UpdateVersionLogImpl
import com.artemchep.keyguard.common.usecase.impl.WatchtowerSyncerImpl
import com.artemchep.keyguard.crypto.privateTemporaryStorageDirectory
import com.artemchep.keyguard.crypto.staging.DefaultStagingSpoolFactory
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.provider.bitwarden.usecase.BlockedUrlCheckImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.CipherUnsecureUrlCheckImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.CipherUrlBroadCheckImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.CipherUrlCheckImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.CipherUrlDuplicateCheckImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.RequestEmailTfa
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.RequestEmailTfaImpl
import com.artemchep.keyguard.util.io.artifact.sweepTemporaryArtifacts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

internal class ApplicationIntegrationsModule {
    val module = module {
        single<StagingSpoolObserver> {
            StagingSpoolObserver.NoOp
        }

        single<StagingSpoolFactory> {
            DefaultStagingSpoolFactory(
                observer = get(),
            )
        }

        single<UnlockUseCaseImpl>() bind UnlockUseCase::class

        single<SearchGpgPublicKeyImpl>() bind SearchGpgPublicKey::class

        single<PasskeyTargetCheckImpl>() bind PasskeyTargetCheck::class

        single<BlockedUrlCheckImpl>() bind BlockedUrlCheck::class

        single<CipherUrlCheckImpl>() bind CipherUrlCheck::class

        single<CipherUrlDuplicateCheckImpl>() bind CipherUrlDuplicateCheck::class

        single<CipherUrlBroadCheckImpl>() bind CipherUrlBroadCheck::class

        single<UpdateVersionLogImpl>() bind UpdateVersionLog::class

        single<ShowNotificationImpl>() bind ShowNotification::class

        single<DismissNotificationsByChannelImpl>() bind DismissNotificationsByChannel::class

        single<TemporaryArtifactMaintenance> {
            TemporaryArtifactMaintenanceImpl(
                roots = buildList {
                    add(
                        TemporaryArtifactRoot(
                            label = "private-temporary",
                            provideDirectory = {
                                withContext(Dispatchers.IO) {
                                    privateTemporaryStorageDirectory()
                                }
                            },
                        ),
                    )
                    getOrNull<CacheDirProvider>()?.let { cacheDirProvider ->
                        add(
                            TemporaryArtifactRoot(
                                label = "cache",
                                provideDirectory = cacheDirProvider::get,
                            ),
                        )
                    }
                    addAll(platformTemporaryArtifactRoots())
                },
                sweeper = { directory, olderThan ->
                    withContext(Dispatchers.IO) {
                        sweepTemporaryArtifacts(
                            directory = directory,
                            olderThan = olderThan,
                        )
                    }
                },
                logRepository = get(),
            )
        }

        single<AppWorker>(qualifier = named(AppWorker.Feature.SYNC)) {
            AppWorkerIm(
                getVaultSession = get(),
                appWorkerSessionAccess = get(),
                pendingUsageHistorySessionAccess = get(),
                updateVersionLog = get(),
                temporaryArtifactMaintenance = get(),
                pendingUsageHistoryEnabled = shouldLaunchPendingUsageHistoryFlush(CurrentPlatform),
            )
        }

        single<WatchtowerSyncerImpl>() bind WatchtowerSyncer::class

        single<RequestAppReviewImpl>() bind RequestAppReview::class

        single<ReviewLog> {
            ReviewLogImpl(
                store = get<KeyValueStoreFactory>().get(Files.REVIEW),
            )
        }

        single<DeeplinkServiceImpl>() bind DeeplinkService::class

        single<CipherUnsecureUrlCheckImpl>() bind CipherUnsecureUrlCheck::class

        single<AndroidAppGooglePlayParser> {
            AndroidAppGooglePlayParser(
                httpClient = get(qualifier = named("curl")),
                json = get(),
            )
        }

        single<AndroidAppFDroidParser> {
            AndroidAppFDroidParser(
                httpClient = get(qualifier = named("curl")),
            )
        }

        single<IosAppAppStoreParser> {
            IosAppAppStoreParser(
                httpClient = get(qualifier = named("curl")),
                json = get(),
            )
        }

        single<CxfImportService> {
            CxfImportServiceImpl(
                passkeyCrypto = get(),
                sshKeyImportService = get(),
            )
        }

        single<TwoFaServiceImpl>() bind TwoFaService::class

        single<PassKeyServiceImpl>() bind PassKeyService::class

        single<JustDeleteMeServiceImpl>() bind JustDeleteMeService::class

        single<LocalizationContributorsServiceImpl>() bind LocalizationContributorsService::class

        single<PrivilegedAppsServiceImpl>() bind PrivilegedAppsService::class

        single<TldServiceImpl>() bind TldService::class

        single<LinkInfoPlatformExtractor>()

        single<LinkInfoExtractorExecute>()

        single<LinkInfoExtractorRegistry> {
            LinkInfoExtractorRegistry(
                listOf(get<LinkInfoPlatformExtractor>(), get<LinkInfoExtractorExecute>()) +
                get<PlatformLinkInfoExtractorRegistry>().values,
            )
        }

        single<SimilarityServiceImpl>() bind SimilarityService::class

        single<RequestEmailTfaImpl>() bind RequestEmailTfa::class

        single<DeviceIdUseCase>()

        single<DeviceEncryptionKeyUseCase>()

        single<PendingUsageHistoryQueue> {
            PendingUsageHistoryQueueImpl(
                databaseManager = get(),
                settingsRepository = get(),
                json = get(),
                dispatcher = databaseDispatcher(),
                logRepository = get(),
            )
        }
    }
}
