package com.artemchep.keyguard.di

import com.artemchep.keyguard.common.model.CipherFilterContext
import com.artemchep.keyguard.common.service.Files
import com.artemchep.keyguard.common.service.gpgkeyserver.GpgKeyserverStateEvaluator
import com.artemchep.keyguard.common.service.hibp.breaches.all.BreachesLocalDataSource
import com.artemchep.keyguard.common.service.hibp.breaches.all.BreachesRemoteDataSource
import com.artemchep.keyguard.common.service.hibp.breaches.all.BreachesRepository
import com.artemchep.keyguard.common.service.hibp.breaches.all.impl.BreachesLocalDataSourceImpl
import com.artemchep.keyguard.common.service.hibp.breaches.all.impl.BreachesRemoteDataSourceImpl
import com.artemchep.keyguard.common.service.hibp.breaches.all.impl.BreachesRepositoryImpl
import com.artemchep.keyguard.common.service.hibp.passwords.PasswordPwnageDataSourceLocal
import com.artemchep.keyguard.common.service.hibp.passwords.PasswordPwnageDataSourceRemote
import com.artemchep.keyguard.common.service.hibp.passwords.PasswordPwnageRepository
import com.artemchep.keyguard.common.service.hibp.passwords.impl.PasswordPwnageDataSourceLocalImpl
import com.artemchep.keyguard.common.service.hibp.passwords.impl.PasswordPwnageDataSourceRemoteImpl
import com.artemchep.keyguard.common.service.hibp.passwords.impl.PasswordPwnageRepositoryImpl
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStoreFactory
import com.artemchep.keyguard.common.usecase.CheckPasswordLeak
import com.artemchep.keyguard.common.usecase.CheckPasswordSetLeak
import com.artemchep.keyguard.common.usecase.CheckUsernameLeak
import com.artemchep.keyguard.common.usecase.CipherBreachCheck
import com.artemchep.keyguard.common.usecase.GetBreaches
import com.artemchep.keyguard.common.usecase.GetBreachesLatestDate
import com.artemchep.keyguard.common.usecase.GetWatchtowerAlerts
import com.artemchep.keyguard.common.usecase.GetWatchtowerUnreadAlerts
import com.artemchep.keyguard.common.usecase.GetWatchtowerUnreadCount
import com.artemchep.keyguard.common.usecase.MarkAllWatchtowerAlertAsNotRead
import com.artemchep.keyguard.common.usecase.MarkAllWatchtowerAlertAsRead
import com.artemchep.keyguard.common.usecase.MarkWatchtowerAlertAsRead
import com.artemchep.keyguard.common.usecase.MarkWatchtowerAlertsAsRead
import com.artemchep.keyguard.common.usecase.PatchWatchtowerAlertCipher
import com.artemchep.keyguard.common.usecase.ResetAllWatchtowerAlert
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.common.usecase.impl.GetBreachesImpl
import com.artemchep.keyguard.common.usecase.impl.GetBreachesLatestDateImpl
import com.artemchep.keyguard.common.usecase.impl.GpgWatchtowerPolicy
import com.artemchep.keyguard.common.usecase.impl.WatchtowerBroadUris
import com.artemchep.keyguard.common.usecase.impl.WatchtowerDuplicateUris
import com.artemchep.keyguard.common.usecase.impl.WatchtowerExpiring
import com.artemchep.keyguard.common.usecase.impl.WatchtowerGpgKeyPublishing
import com.artemchep.keyguard.common.usecase.impl.WatchtowerGpgKeyUnusable
import com.artemchep.keyguard.common.usecase.impl.WatchtowerInactivePasskey
import com.artemchep.keyguard.common.usecase.impl.WatchtowerInactiveTfa
import com.artemchep.keyguard.common.usecase.impl.WatchtowerIncomplete
import com.artemchep.keyguard.common.usecase.impl.WatchtowerPasswordPwned
import com.artemchep.keyguard.common.usecase.impl.WatchtowerPasswordStrength
import com.artemchep.keyguard.common.usecase.impl.WatchtowerSshKeyStrength
import com.artemchep.keyguard.common.usecase.impl.WatchtowerUnsecureWebsite
import com.artemchep.keyguard.common.usecase.impl.WatchtowerWeakGpgKey
import com.artemchep.keyguard.common.usecase.impl.WatchtowerWebsitePwned
import com.artemchep.keyguard.provider.bitwarden.usecase.CheckPasswordLeakImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.CheckPasswordSetLeakImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.CheckUsernameLeakImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.CipherBreachCheckImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.GetWatchtowerAlertsImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.GetWatchtowerUnreadAlertsImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.GetWatchtowerUnreadCountImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.MarkAllWatchtowerAlertAsNotReadImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.MarkAllWatchtowerAlertAsReadImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.MarkWatchtowerAlertAsReadImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.MarkWatchtowerAlertsAsReadImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.PatchWatchtowerAlertCipherImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.ResetAllWatchtowerAlertImpl
import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.plugin.module.dsl.scoped

internal class VaultWatchtowerModule {
    val module = module {
        scope<VaultSessionScope> {
            scoped<CipherFilterContext>()

            scoped<GetWatchtowerAlertsImpl>() bind GetWatchtowerAlerts::class

            scoped<GetWatchtowerUnreadAlertsImpl>() bind GetWatchtowerUnreadAlerts::class

            scoped<GetWatchtowerUnreadCountImpl>() bind GetWatchtowerUnreadCount::class

            scoped<MarkAllWatchtowerAlertAsNotReadImpl>() bind MarkAllWatchtowerAlertAsNotRead::class

            scoped<MarkAllWatchtowerAlertAsReadImpl>() bind MarkAllWatchtowerAlertAsRead::class

            scoped<MarkWatchtowerAlertAsReadImpl>() bind MarkWatchtowerAlertAsRead::class

            scoped<MarkWatchtowerAlertsAsReadImpl>() bind MarkWatchtowerAlertsAsRead::class

            scoped<WatchtowerInactivePasskey>()

            scoped<WatchtowerInactiveTfa>()

            scoped<WatchtowerDuplicateUris>()

            scoped<WatchtowerBroadUris>()

            scoped<WatchtowerPasswordStrength>()

            scoped<WatchtowerSshKeyStrength>()

            scoped<GpgWatchtowerPolicy>()

            scoped<WatchtowerGpgKeyUnusable>()

            scoped<WatchtowerWeakGpgKey>()

            scoped<WatchtowerGpgKeyPublishing> {
                WatchtowerGpgKeyPublishing(
                    keyserverStateRepository = get(),
                    getCiphers = get(),
                    evaluator = GpgKeyserverStateEvaluator(reconciler = get(), resolver = get()),
                    scope = get<WindowCoroutineScope>(),
                )
            }

            scoped<WatchtowerPasswordPwned>()

            scoped<WatchtowerWebsitePwned>()

            scoped<WatchtowerIncomplete>()

            scoped<WatchtowerExpiring>()

            scoped<WatchtowerUnsecureWebsite>()

            scoped<CipherBreachCheckImpl>() bind CipherBreachCheck::class

            scoped<CheckPasswordLeakImpl>() bind CheckPasswordLeak::class

            scoped<CheckPasswordSetLeakImpl>() bind CheckPasswordSetLeak::class

            scoped<CheckUsernameLeakImpl>() bind CheckUsernameLeak::class

            scoped<PasswordPwnageDataSourceLocal> {
                PasswordPwnageDataSourceLocalImpl(
                    databaseManager = get(),
                    dispatcher = databaseDispatcher(),
                )
            }

            scoped<PasswordPwnageDataSourceRemoteImpl>() bind PasswordPwnageDataSourceRemote::class

            scoped<PasswordPwnageRepositoryImpl>() bind PasswordPwnageRepository::class

            scoped<BreachesRemoteDataSourceImpl>() bind BreachesRemoteDataSource::class

            scoped<BreachesLocalDataSource> {
                BreachesLocalDataSourceImpl(
                    store = get<KeyValueStoreFactory>().get(Files.BREACHES),
                    json = get(),
                    passwordPwnageDataSourceLocal = get(),
                )
            }

            scoped<BreachesRepositoryImpl>() bind BreachesRepository::class

            scoped<GetBreachesImpl>() bind GetBreaches::class

            scoped<GetBreachesLatestDateImpl>() bind GetBreachesLatestDate::class

            scoped<PatchWatchtowerAlertCipherImpl>() bind PatchWatchtowerAlertCipher::class

            scoped<ResetAllWatchtowerAlertImpl>() bind ResetAllWatchtowerAlert::class
        }
    }
}
