package com.artemchep.keyguard.di

import com.artemchep.keyguard.common.service.filter.repo.CipherFilterRepository
import com.artemchep.keyguard.common.service.filter.repo.impl.CipherFilterRepositoryImpl
import com.artemchep.keyguard.common.service.gpgkeyserver.GpgKeyserverStateRepository
import com.artemchep.keyguard.common.service.gpgkeyserver.impl.GpgKeyserverStateRepositoryImpl
import com.artemchep.keyguard.common.service.gpmprivapps.AppPrivilegedAppRepository
import com.artemchep.keyguard.common.service.gpmprivapps.AppPrivilegedAppRepositoryImpl
import com.artemchep.keyguard.common.service.gpmprivapps.UserPrivilegedAppRepository
import com.artemchep.keyguard.common.service.gpmprivapps.UserPrivilegedAppRepositoryImpl
import com.artemchep.keyguard.common.service.hibp.HibpRepository
import com.artemchep.keyguard.common.service.hibp.impl.HibpRepositoryImpl
import com.artemchep.keyguard.common.service.settings.VaultSettingsReadRepository
import com.artemchep.keyguard.common.service.settings.VaultSettingsReadWriteRepository
import com.artemchep.keyguard.common.service.settings.impl.VaultSettingsRepositoryImpl
import com.artemchep.keyguard.common.service.urlblock.UrlBlockRepository
import com.artemchep.keyguard.common.service.urlblock.UrlBlockRepositoryImpl
import com.artemchep.keyguard.common.service.urloverride.UrlOverrideRepository
import com.artemchep.keyguard.common.service.urloverride.UrlOverrideRepositoryImpl
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipherRepositoryImpl
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCollectionRepositoryImpl
import com.artemchep.keyguard.core.store.bitwarden.BitwardenEquivalentDomainRepositoryImpl
import com.artemchep.keyguard.core.store.bitwarden.BitwardenFolderRepositoryImpl
import com.artemchep.keyguard.core.store.bitwarden.BitwardenMetaRepositoryImpl
import com.artemchep.keyguard.core.store.bitwarden.BitwardenOrganizationRepositoryImpl
import com.artemchep.keyguard.core.store.bitwarden.BitwardenProfileRepositoryImpl
import com.artemchep.keyguard.core.store.bitwarden.BitwardenSendRepositoryImpl
import com.artemchep.keyguard.core.store.bitwarden.BitwardenTokenRepositoryImpl
import com.artemchep.keyguard.core.store.bitwarden.ServiceTokenRepositoryImpl
import com.artemchep.keyguard.provider.bitwarden.repository.BitwardenCipherRepository
import com.artemchep.keyguard.provider.bitwarden.repository.BitwardenCollectionRepository
import com.artemchep.keyguard.provider.bitwarden.repository.BitwardenDomainRepository
import com.artemchep.keyguard.provider.bitwarden.repository.BitwardenFolderRepository
import com.artemchep.keyguard.provider.bitwarden.repository.BitwardenMetaRepository
import com.artemchep.keyguard.provider.bitwarden.repository.BitwardenOrganizationRepository
import com.artemchep.keyguard.provider.bitwarden.repository.BitwardenProfileRepository
import com.artemchep.keyguard.provider.bitwarden.repository.BitwardenSendRepository
import com.artemchep.keyguard.provider.bitwarden.repository.BitwardenTokenRepository
import com.artemchep.keyguard.provider.bitwarden.repository.ServiceTokenRepository
import com.artemchep.keyguard.provider.bitwarden.usecase.util.ModifyDatabase
import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.plugin.module.dsl.scoped

internal class VaultPersistenceModule {
    val module = module {
        scope<VaultSessionScope> {
            scoped<VaultSettingsRepositoryImpl>()

            scoped<VaultSettingsReadRepository> {
                get<VaultSettingsRepositoryImpl>()
            }

            scoped<VaultSettingsReadWriteRepository> {
                get<VaultSettingsRepositoryImpl>()
            }

            scoped<HibpRepositoryImpl>() bind HibpRepository::class

            scoped<ModifyDatabase>()

            scoped<GpgKeyserverStateRepository> {
                GpgKeyserverStateRepositoryImpl(
                    databaseManager = get(),
                    dispatcher = databaseDispatcher(),
                )
            }

            scoped<CipherFilterRepository> {
                CipherFilterRepositoryImpl(
                    context = get(),
                    databaseManager = get(),
                    json = get(),
                    dispatcher = databaseDispatcher(),
                )
            }

            scoped<UrlOverrideRepository> {
                UrlOverrideRepositoryImpl(
                    databaseManager = get(),
                    dispatcher = databaseDispatcher(),
                )
            }

            scoped<UserPrivilegedAppRepository> {
                UserPrivilegedAppRepositoryImpl(
                    databaseManager = get(),
                    dispatcher = databaseDispatcher(),
                )
            }

            scoped<AppPrivilegedAppRepository> {
                AppPrivilegedAppRepositoryImpl(
                    privilegedAppsService = get(),
                    json = get(),
                    dispatcher = databaseDispatcher(),
                )
            }

            scoped<UrlBlockRepository> {
                UrlBlockRepositoryImpl(
                    vaultDatabaseManager = get(),
                    exposedDatabaseManager = get(),
                    dispatcher = databaseDispatcher(),
                )
            }

            scoped<BitwardenTokenRepository> {
                BitwardenTokenRepositoryImpl(
                    databaseManager = get(),
                    dispatcher = databaseDispatcher(),
                )
            }

            scoped<ServiceTokenRepository> {
                ServiceTokenRepositoryImpl(
                    databaseManager = get(),
                    dispatcher = databaseDispatcher(),
                )
            }

            scoped<BitwardenSendRepository> {
                BitwardenSendRepositoryImpl(
                    databaseManager = get(),
                    dispatcher = databaseDispatcher(),
                )
            }

            scoped<BitwardenCipherRepository> {
                BitwardenCipherRepositoryImpl(
                    databaseManager = get(),
                    dispatcher = databaseDispatcher(),
                )
            }

            scoped<BitwardenCollectionRepository> {
                BitwardenCollectionRepositoryImpl(
                    databaseManager = get(),
                    dispatcher = databaseDispatcher(),
                )
            }

            scoped<BitwardenOrganizationRepository> {
                BitwardenOrganizationRepositoryImpl(
                    databaseManager = get(),
                    dispatcher = databaseDispatcher(),
                )
            }

            scoped<BitwardenDomainRepository> {
                BitwardenEquivalentDomainRepositoryImpl(
                    databaseManager = get(),
                    dispatcher = databaseDispatcher(),
                )
            }

            scoped<BitwardenFolderRepository> {
                BitwardenFolderRepositoryImpl(
                    databaseManager = get(),
                    dispatcher = databaseDispatcher(),
                )
            }

            scoped<BitwardenProfileRepository> {
                BitwardenProfileRepositoryImpl(
                    databaseManager = get(),
                    dispatcher = databaseDispatcher(),
                )
            }

            scoped<BitwardenMetaRepository> {
                BitwardenMetaRepositoryImpl(
                    databaseManager = get(),
                    dispatcher = databaseDispatcher(),
                )
            }
        }
    }
}
