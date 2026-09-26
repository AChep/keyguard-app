package com.artemchep.keyguard.di

import com.artemchep.keyguard.common.service.Files
import com.artemchep.keyguard.common.service.gpgagent.GpgPublicKeyRepository
import com.artemchep.keyguard.common.service.gpgagent.impl.GpgPublicKeyRepositoryImpl
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStoreFactory
import com.artemchep.keyguard.common.service.notification.NotificationFingerprintRepository
import com.artemchep.keyguard.common.service.notification.impl.NotificationFingerprintRepositoryImpl
import com.artemchep.keyguard.common.service.session.VaultSessionLocker
import com.artemchep.keyguard.common.service.sshagent.SshAgentPublicKeyRepository
import com.artemchep.keyguard.common.service.sshagent.impl.SshAgentPublicKeyRepositoryImpl
import com.artemchep.keyguard.common.service.vault.FingerprintReadRepository
import com.artemchep.keyguard.common.service.vault.FingerprintReadWriteRepository
import com.artemchep.keyguard.common.service.vault.SessionMetadataReadRepository
import com.artemchep.keyguard.common.service.vault.SessionMetadataReadWriteRepository
import com.artemchep.keyguard.common.service.vault.SessionReadRepository
import com.artemchep.keyguard.common.service.vault.SessionReadWriteRepository
import com.artemchep.keyguard.common.service.vault.impl.FingerprintRepositoryImpl
import com.artemchep.keyguard.common.service.vault.impl.KeyRepositoryImpl
import com.artemchep.keyguard.common.service.vault.impl.SessionMetadataRepositoryImpl
import com.artemchep.keyguard.common.service.vault.impl.SessionRepositoryImpl
import com.artemchep.keyguard.common.usecase.AuthConfirmMasterKeyUseCase
import com.artemchep.keyguard.common.usecase.AuthGenerateMasterKeyUseCase
import com.artemchep.keyguard.common.usecase.BiometricKeyDecryptUseCase
import com.artemchep.keyguard.common.usecase.BiometricKeyEncryptUseCase
import com.artemchep.keyguard.common.usecase.ClearVaultSession
import com.artemchep.keyguard.common.usecase.ConfirmAccessByYubiKeyUseCase
import com.artemchep.keyguard.common.usecase.DisableBiometric
import com.artemchep.keyguard.common.usecase.DisableYubiKeyUnlock
import com.artemchep.keyguard.common.usecase.EnableBiometric
import com.artemchep.keyguard.common.usecase.EnableYubiKeyUnlock
import com.artemchep.keyguard.common.usecase.GenerateMasterHashUseCase
import com.artemchep.keyguard.common.usecase.GenerateMasterKeyUseCase
import com.artemchep.keyguard.common.usecase.GenerateMasterSaltUseCase
import com.artemchep.keyguard.common.usecase.GetBiometricRemainingDuration
import com.artemchep.keyguard.common.usecase.GetBiometricRequireConfirmation
import com.artemchep.keyguard.common.usecase.GetBiometricTimeout
import com.artemchep.keyguard.common.usecase.GetBiometricTimeoutVariants
import com.artemchep.keyguard.common.usecase.GetVaultLockAfterReboot
import com.artemchep.keyguard.common.usecase.GetVaultLockAfterScreenOff
import com.artemchep.keyguard.common.usecase.GetVaultLockAfterTimeout
import com.artemchep.keyguard.common.usecase.GetVaultLockAfterTimeoutVariants
import com.artemchep.keyguard.common.usecase.GetVaultPersist
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.common.usecase.PutBiometricRequireConfirmation
import com.artemchep.keyguard.common.usecase.PutBiometricTimeout
import com.artemchep.keyguard.common.usecase.PutVaultLockAfterReboot
import com.artemchep.keyguard.common.usecase.PutVaultLockAfterScreenOff
import com.artemchep.keyguard.common.usecase.PutVaultLockAfterTimeout
import com.artemchep.keyguard.common.usecase.PutVaultPersist
import com.artemchep.keyguard.common.usecase.PutVaultSession
import com.artemchep.keyguard.common.usecase.impl.AuthConfirmMasterKeyUseCaseImpl
import com.artemchep.keyguard.common.usecase.impl.AuthGenerateMasterKeyUseCaseImpl
import com.artemchep.keyguard.common.usecase.impl.BiometricKeyDecryptUseCaseImpl
import com.artemchep.keyguard.common.usecase.impl.BiometricKeyEncryptUseCaseImpl
import com.artemchep.keyguard.common.usecase.impl.ClearVaultSessionImpl
import com.artemchep.keyguard.common.usecase.impl.ConfirmAccessByYubiKeyUseCaseImpl
import com.artemchep.keyguard.common.usecase.impl.DisableBiometricImpl
import com.artemchep.keyguard.common.usecase.impl.DisableYubiKeyUnlockImpl
import com.artemchep.keyguard.common.usecase.impl.EnableBiometricImpl
import com.artemchep.keyguard.common.usecase.impl.EnableYubiKeyUnlockImpl
import com.artemchep.keyguard.common.usecase.impl.GenerateMasterHashUseCaseImpl
import com.artemchep.keyguard.common.usecase.impl.GenerateMasterKeyUseCaseImpl
import com.artemchep.keyguard.common.usecase.impl.GenerateMasterSaltUseCaseImpl
import com.artemchep.keyguard.common.usecase.impl.GetBiometricRemainingDurationImpl
import com.artemchep.keyguard.common.usecase.impl.GetBiometricRequireConfirmationImpl
import com.artemchep.keyguard.common.usecase.impl.GetBiometricTimeoutImpl
import com.artemchep.keyguard.common.usecase.impl.GetBiometricTimeoutVariantsImpl
import com.artemchep.keyguard.common.usecase.impl.GetVaultLockAfterRebootImpl
import com.artemchep.keyguard.common.usecase.impl.GetVaultLockAfterScreenOffImpl
import com.artemchep.keyguard.common.usecase.impl.GetVaultLockAfterTimeoutImpl
import com.artemchep.keyguard.common.usecase.impl.GetVaultLockAfterTimeoutVariantsImpl
import com.artemchep.keyguard.common.usecase.impl.GetVaultPersistImpl
import com.artemchep.keyguard.common.usecase.impl.GetVaultSessionImpl
import com.artemchep.keyguard.common.usecase.impl.PutBiometricRequireConfirmationImpl
import com.artemchep.keyguard.common.usecase.impl.PutBiometricTimeoutImpl
import com.artemchep.keyguard.common.usecase.impl.PutVaultLockAfterRebootImpl
import com.artemchep.keyguard.common.usecase.impl.PutVaultLockAfterScreenOffImpl
import com.artemchep.keyguard.common.usecase.impl.PutVaultLockAfterTimeoutImpl
import com.artemchep.keyguard.common.usecase.impl.PutVaultPersistImpl
import com.artemchep.keyguard.common.usecase.impl.PutVaultSessionImpl
import kotlinx.coroutines.GlobalScope
import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.plugin.module.dsl.factory
import org.koin.plugin.module.dsl.single

internal class ApplicationAuthenticationModule {
    val module = module {
        single<AuthConfirmMasterKeyUseCaseImpl>() bind AuthConfirmMasterKeyUseCase::class

        single<AuthGenerateMasterKeyUseCaseImpl>() bind AuthGenerateMasterKeyUseCase::class

        single<BiometricKeyDecryptUseCaseImpl>() bind BiometricKeyDecryptUseCase::class

        single<BiometricKeyEncryptUseCaseImpl>() bind BiometricKeyEncryptUseCase::class

        single<GetBiometricRemainingDurationImpl>() bind GetBiometricRemainingDuration::class

        single<DisableBiometricImpl>() bind DisableBiometric::class

        single<EnableBiometricImpl>() bind EnableBiometric::class

        single<DisableYubiKeyUnlockImpl>() bind DisableYubiKeyUnlock::class

        single<EnableYubiKeyUnlockImpl>() bind EnableYubiKeyUnlock::class

        single<GenerateMasterHashUseCaseImpl>() bind GenerateMasterHashUseCase::class

        single<GenerateMasterKeyUseCaseImpl>() bind GenerateMasterKeyUseCase::class

        single<GenerateMasterSaltUseCaseImpl>() bind GenerateMasterSaltUseCase::class

        single<ConfirmAccessByYubiKeyUseCaseImpl>() bind ConfirmAccessByYubiKeyUseCase::class

        single<GetVaultLockAfterTimeoutImpl>() bind GetVaultLockAfterTimeout::class

        single<GetVaultLockAfterTimeoutVariantsImpl>() bind GetVaultLockAfterTimeoutVariants::class

        single<GetBiometricTimeoutImpl>() bind GetBiometricTimeout::class

        single<GetBiometricTimeoutVariantsImpl>() bind GetBiometricTimeoutVariants::class

        single<PutBiometricTimeoutImpl>() bind PutBiometricTimeout::class

        single<GetBiometricRequireConfirmationImpl>() bind GetBiometricRequireConfirmation::class

        single<PutBiometricRequireConfirmationImpl>() bind PutBiometricRequireConfirmation::class

        single<VaultSessionLocker> {
            VaultSessionLocker(
                getVaultLockAfterTimeout = get(),
                clearVaultSession = get(),
                scope = GlobalScope,
            )
        }

        single<PutVaultSessionImpl>() bind PutVaultSession::class

        single<ClearVaultSessionImpl>() bind ClearVaultSession::class

        single<GetVaultLockAfterScreenOffImpl>() bind GetVaultLockAfterScreenOff::class

        single<GetVaultLockAfterRebootImpl>() bind GetVaultLockAfterReboot::class

        single<PutVaultLockAfterRebootImpl>() bind PutVaultLockAfterReboot::class

        single<PutVaultLockAfterScreenOffImpl>() bind PutVaultLockAfterScreenOff::class

        single<PutVaultLockAfterTimeoutImpl>() bind PutVaultLockAfterTimeout::class

        single<GetVaultSessionImpl>() bind GetVaultSession::class

        single<GetVaultPersistImpl>() bind GetVaultPersist::class

        single<PutVaultPersistImpl>() bind PutVaultPersist::class

        single<NotificationFingerprintRepository> {
            NotificationFingerprintRepositoryImpl(
                store = get<KeyValueStoreFactory>().get(Files.NOTIFICATIONS),
                json = get(),
            )
        }

        single {
            FingerprintRepositoryImpl(
                store = get<KeyValueStoreFactory>().get(Files.FINGERPRINT),
                json = get(),
                base64Service = get(),
            )
        }

        factory<FingerprintReadRepository> {
            get<FingerprintRepositoryImpl>()
        }

        factory<FingerprintReadWriteRepository> {
            get<FingerprintRepositoryImpl>()
        }

        single {
            KeyRepositoryImpl(
                store = get<KeyValueStoreFactory>().get(Files.KEY),
                json = get(),
                base64Service = get(),
            )
        }

        single<SessionRepositoryImpl>()

        factory<SessionReadRepository> {
            get<SessionRepositoryImpl>()
        }

        factory<SessionReadWriteRepository> {
            get<SessionRepositoryImpl>()
        }

        single {
            SessionMetadataRepositoryImpl(
                store = get<KeyValueStoreFactory>().get(Files.SESSION_METADATA),
            )
        }

        factory<SessionMetadataReadRepository> {
            get<SessionMetadataRepositoryImpl>()
        }

        factory<SessionMetadataReadWriteRepository> {
            get<SessionMetadataRepositoryImpl>()
        }

        single<SshAgentPublicKeyRepository> {
            SshAgentPublicKeyRepositoryImpl(
                exposedDatabaseManager = get(),
                cryptoGenerator = get(),
                base64Service = get(),
                dispatcher = databaseDispatcher(),
            )
        }

        single<GpgPublicKeyRepository> {
            GpgPublicKeyRepositoryImpl(
                exposedDatabaseManager = get(),
                dispatcher = databaseDispatcher(),
            )
        }
    }
}
