package com.artemchep.keyguard.di

import com.artemchep.keyguard.android.downloader.journal.BarcodeUsageHistoryRepository
import com.artemchep.keyguard.android.downloader.journal.BarcodeUsageHistoryRepositoryImpl
import com.artemchep.keyguard.android.downloader.journal.CipherHistoryOpenedRepository
import com.artemchep.keyguard.android.downloader.journal.CipherHistoryOpenedRepositoryImpl
import com.artemchep.keyguard.android.downloader.journal.GeneratorHistoryRepository
import com.artemchep.keyguard.android.downloader.journal.GeneratorHistoryRepositoryImpl
import com.artemchep.keyguard.android.downloader.journal.GpgUsageHistoryRepository
import com.artemchep.keyguard.android.downloader.journal.GpgUsageHistoryRepositoryImpl
import com.artemchep.keyguard.android.downloader.journal.SshUsageHistoryRepository
import com.artemchep.keyguard.android.downloader.journal.SshUsageHistoryRepositoryImpl
import com.artemchep.keyguard.common.service.pendinghistory.PendingUsageHistoryFlushRunner
import com.artemchep.keyguard.common.service.pendinghistory.PendingUsageHistoryFlushRunnerImpl
import com.artemchep.keyguard.common.service.pendinghistory.PendingUsageHistoryFlusher
import com.artemchep.keyguard.common.service.relays.repo.GeneratorEmailRelayRepository
import com.artemchep.keyguard.common.service.relays.repo.GeneratorEmailRelayRepositoryImpl
import com.artemchep.keyguard.common.service.wordlist.repo.GeneratorWordlistRepository
import com.artemchep.keyguard.common.service.wordlist.repo.GeneratorWordlistWordRepository
import com.artemchep.keyguard.common.service.wordlist.repo.impl.GeneratorWordlistRepositoryImpl
import com.artemchep.keyguard.common.service.wordlist.repo.impl.GeneratorWordlistWordRepositoryImpl
import com.artemchep.keyguard.common.usecase.AddCipherOpenedHistory
import com.artemchep.keyguard.common.usecase.AddCipherUsedAutofillHistory
import com.artemchep.keyguard.common.usecase.AddCipherUsedPasskeyHistory
import com.artemchep.keyguard.common.usecase.AddEmailRelay
import com.artemchep.keyguard.common.usecase.AddGeneratorHistory
import com.artemchep.keyguard.common.usecase.AddGpgUsageHistory
import com.artemchep.keyguard.common.usecase.AddSshUsageHistory
import com.artemchep.keyguard.common.usecase.AddWordlist
import com.artemchep.keyguard.common.usecase.CipherRemovePasswordHistory
import com.artemchep.keyguard.common.usecase.CipherRemovePasswordHistoryById
import com.artemchep.keyguard.common.usecase.EditWordlist
import com.artemchep.keyguard.common.usecase.GetBarcodeUsageHistory
import com.artemchep.keyguard.common.usecase.GetCipherOpenedHistory
import com.artemchep.keyguard.common.usecase.GetEmailRelays
import com.artemchep.keyguard.common.usecase.GetGeneratorHistory
import com.artemchep.keyguard.common.usecase.GetGpgUsageHistory
import com.artemchep.keyguard.common.usecase.GetGpgUsageHistoryCount
import com.artemchep.keyguard.common.usecase.GetSshUsageHistory
import com.artemchep.keyguard.common.usecase.GetSshUsageHistoryCount
import com.artemchep.keyguard.common.usecase.GetWordlistPrimitive
import com.artemchep.keyguard.common.usecase.GetWordlists
import com.artemchep.keyguard.common.usecase.PutBarcodeUsageHistory
import com.artemchep.keyguard.common.usecase.RemoveEmailRelayById
import com.artemchep.keyguard.common.usecase.RemoveGeneratorHistory
import com.artemchep.keyguard.common.usecase.RemoveGeneratorHistoryById
import com.artemchep.keyguard.common.usecase.RemoveGpgUsageHistory
import com.artemchep.keyguard.common.usecase.RemoveSshUsageHistory
import com.artemchep.keyguard.common.usecase.RemoveWordlistById
import com.artemchep.keyguard.common.usecase.impl.AddEmailRelayImpl
import com.artemchep.keyguard.common.usecase.impl.AddGeneratorHistoryImpl
import com.artemchep.keyguard.common.usecase.impl.AddWordlistImpl
import com.artemchep.keyguard.common.usecase.impl.EditWordlistImpl
import com.artemchep.keyguard.common.usecase.impl.GetBarcodeUsageHistoryImpl
import com.artemchep.keyguard.common.usecase.impl.GetGeneratorHistoryImpl
import com.artemchep.keyguard.common.usecase.impl.PutBarcodeUsageHistoryImpl
import com.artemchep.keyguard.common.usecase.impl.RemoveGeneratorHistoryByIdImpl
import com.artemchep.keyguard.common.usecase.impl.RemoveGeneratorHistoryImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.AddCipherOpenedHistoryImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.AddCipherUsedAutofillHistoryImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.AddCipherUsedPasskeyHistoryImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.AddGpgUsageHistoryImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.AddSshUsageHistoryImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.CipherRemovePasswordHistoryByIdImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.CipherRemovePasswordHistoryImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.GetCipherOpenedHistoryImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.GetEmailRelaysImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.GetGpgUsageHistoryCountImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.GetGpgUsageHistoryImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.GetSshUsageHistoryCountImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.GetSshUsageHistoryImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.GetWordlistPrimitiveImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.GetWordlistsImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.RemoveEmailRelayByIdImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.RemoveGpgUsageHistoryImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.RemoveSshUsageHistoryImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.RemoveWordlistByIdImpl
import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.plugin.module.dsl.scoped

internal class VaultHistoryModule {
    val module = module {
        scope<VaultSessionScope> {
            scoped<GetEmailRelaysImpl> {
                GetEmailRelaysImpl(
                    generatorEmailRelayRepository = get(),
                )
            } bind GetEmailRelays::class

            scoped<AddEmailRelayImpl>() bind AddEmailRelay::class

            scoped<RemoveEmailRelayByIdImpl>() bind RemoveEmailRelayById::class

            scoped<GetWordlistPrimitiveImpl> {
                GetWordlistPrimitiveImpl(
                    generatorWordlistWordRepository = get(),
                )
            } bind GetWordlistPrimitive::class

            scoped<GetWordlistsImpl> {
                GetWordlistsImpl(
                    generatorWordlistRepository = get(),
                )
            } bind GetWordlists::class

            scoped<AddWordlistImpl>() bind AddWordlist::class

            scoped<EditWordlistImpl>() bind EditWordlist::class

            scoped<RemoveWordlistByIdImpl>() bind RemoveWordlistById::class

            scoped<PendingUsageHistoryFlusher>()

            scoped<PendingUsageHistoryFlushRunner> {
                PendingUsageHistoryFlushRunnerImpl(
                    flush = get<PendingUsageHistoryFlusher>()::flush,
                    logRepository = get(),
                )
            }

            scoped<GetGeneratorHistoryImpl>() bind GetGeneratorHistory::class

            scoped<RemoveGeneratorHistoryImpl>() bind RemoveGeneratorHistory::class

            scoped<RemoveGeneratorHistoryByIdImpl>() bind RemoveGeneratorHistoryById::class

            scoped<AddGeneratorHistoryImpl>() bind AddGeneratorHistory::class

            scoped<GetBarcodeUsageHistoryImpl>() bind GetBarcodeUsageHistory::class

            scoped<PutBarcodeUsageHistoryImpl>() bind PutBarcodeUsageHistory::class

            scoped<AddCipherOpenedHistoryImpl>() bind AddCipherOpenedHistory::class

            scoped<AddCipherUsedAutofillHistoryImpl>() bind AddCipherUsedAutofillHistory::class

            scoped<AddCipherUsedPasskeyHistoryImpl>() bind AddCipherUsedPasskeyHistory::class

            scoped<AddSshUsageHistoryImpl>() bind AddSshUsageHistory::class

            scoped<GetSshUsageHistoryImpl>() bind GetSshUsageHistory::class

            scoped<GetSshUsageHistoryCountImpl>() bind GetSshUsageHistoryCount::class

            scoped<RemoveSshUsageHistoryImpl>() bind RemoveSshUsageHistory::class

            scoped<AddGpgUsageHistoryImpl>() bind AddGpgUsageHistory::class

            scoped<GetGpgUsageHistoryImpl>() bind GetGpgUsageHistory::class

            scoped<GetGpgUsageHistoryCountImpl>() bind GetGpgUsageHistoryCount::class

            scoped<RemoveGpgUsageHistoryImpl>() bind RemoveGpgUsageHistory::class

            scoped<GetCipherOpenedHistoryImpl>() bind GetCipherOpenedHistory::class

            scoped<CipherHistoryOpenedRepository> {
                CipherHistoryOpenedRepositoryImpl(
                    databaseManager = get(),
                    dispatcher = databaseDispatcher(),
                )
            }

            scoped<BarcodeUsageHistoryRepository> {
                BarcodeUsageHistoryRepositoryImpl(
                    databaseManager = get(),
                    dispatcher = databaseDispatcher(),
                )
            }

            scoped<SshUsageHistoryRepository> {
                SshUsageHistoryRepositoryImpl(
                    databaseManager = get(),
                    dispatcher = databaseDispatcher(),
                )
            }

            scoped<GpgUsageHistoryRepository> {
                GpgUsageHistoryRepositoryImpl(
                    databaseManager = get(),
                    dispatcher = databaseDispatcher(),
                )
            }

            scoped<GeneratorHistoryRepository> {
                GeneratorHistoryRepositoryImpl(
                    databaseManager = get(),
                    base64Service = get(),
                    keyPairGenerator = get(),
                    json = get(),
                    dispatcher = databaseDispatcher(),
                )
            }

            scoped<GeneratorEmailRelayRepository> {
                GeneratorEmailRelayRepositoryImpl(
                    databaseManager = get(),
                    json = get(),
                    dispatcher = databaseDispatcher(),
                )
            }

            scoped<GeneratorWordlistRepository> {
                GeneratorWordlistRepositoryImpl(
                    databaseManager = get(),
                    dispatcher = databaseDispatcher(),
                )
            }

            scoped<GeneratorWordlistWordRepository> {
                GeneratorWordlistWordRepositoryImpl(
                    databaseManager = get(),
                    dispatcher = databaseDispatcher(),
                )
            }

            scoped<CipherRemovePasswordHistoryByIdImpl>() bind CipherRemovePasswordHistoryById::class

            scoped<CipherRemovePasswordHistoryImpl>() bind CipherRemovePasswordHistory::class
        }
    }
}
