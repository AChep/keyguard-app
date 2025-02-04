package com.artemchep.keyguard.common.usecase.impl

import arrow.core.Either
import arrow.core.compose
import arrow.core.getOrElse
import arrow.core.partially1
import com.artemchep.keyguard.common.exception.crypto.BiometricKeyDecryptException
import com.artemchep.keyguard.common.exception.crypto.BiometricKeyEncryptException
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.dispatchOn
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.io.flatMap
import com.artemchep.keyguard.common.io.flatTap
import com.artemchep.keyguard.common.io.flatten
import com.artemchep.keyguard.common.io.handleErrorTap
import com.artemchep.keyguard.common.io.handleErrorWith
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.ioRaise
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.model.AuthResult
import com.artemchep.keyguard.common.model.BiometricPurpose
import com.artemchep.keyguard.common.model.BiometricStatus
import com.artemchep.keyguard.common.model.DKey
import com.artemchep.keyguard.common.model.Fingerprint
import com.artemchep.keyguard.common.model.FingerprintBiometric
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.model.MasterPassword
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.model.VaultState
import com.artemchep.keyguard.common.service.vault.FingerprintReadWriteRepository
import com.artemchep.keyguard.common.service.vault.SessionMetadataReadWriteRepository
import com.artemchep.keyguard.common.usecase.AuthConfirmMasterKeyUseCase
import com.artemchep.keyguard.common.usecase.AuthGenerateMasterKeyUseCase
import com.artemchep.keyguard.common.usecase.BiometricKeyDecryptUseCase
import com.artemchep.keyguard.common.usecase.BiometricKeyEncryptUseCase
import com.artemchep.keyguard.common.usecase.BiometricStatusUseCase
import com.artemchep.keyguard.common.usecase.DisableBiometric
import com.artemchep.keyguard.common.usecase.GetBiometricRemainingDuration
import com.artemchep.keyguard.common.usecase.GetBiometricRequireConfirmation
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.common.usecase.PutVaultSession
import com.artemchep.keyguard.common.usecase.UnlockUseCase
import com.artemchep.keyguard.common.util.catch
import com.artemchep.keyguard.common.util.memoize
import com.artemchep.keyguard.core.session.usecase.createSubDi
import com.artemchep.keyguard.core.store.DatabaseManager
import com.artemchep.keyguard.feature.crashlytics.crashlyticsTap
import com.artemchep.keyguard.platform.LeBiometricCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.take
import kotlinx.datetime.Clock
import org.kodein.di.Copy
import org.kodein.di.DI
import org.kodein.di.DirectDI
import org.kodein.di.instance
import org.kodein.di.subDI
import java.security.KeyException

class UnlockUseCaseImpl(
    private val di: DI,
    private val biometricStatusUseCase: BiometricStatusUseCase,
    private val getVaultSession: GetVaultSession,
    private val putVaultSession: PutVaultSession,
    private val disableBiometric: DisableBiometric,
    private val keyReadWriteRepository: FingerprintReadWriteRepository,
    private val sessionMetadataReadWriteRepository: SessionMetadataReadWriteRepository,
    private val getBiometricRequireConfirmation: GetBiometricRequireConfirmation,
    private val getBiometricRemainingDuration: GetBiometricRemainingDuration,
    private val biometricKeyEncryptUseCase: BiometricKeyEncryptUseCase,
    private val decryptBiometricKeyUseCase: BiometricKeyDecryptUseCase,
    private val authConfirmMasterKeyUseCase: AuthConfirmMasterKeyUseCase,
    private val authGenerateMasterKeyUseCase: AuthGenerateMasterKeyUseCase,
) : UnlockUseCase {
    private val generateMasterKey = authGenerateMasterKeyUseCase()
        .compose { password: String ->
            MasterPassword.of(password)
        }

    private val sharedFlow = combine(
        keyReadWriteRepository.get(),
        getBiometricStatusFlow(),
        getVaultSession(),
    ) { persistableUserTokens, biometric, session ->
        when {
            persistableUserTokens == null && session is MasterSession.Empty ->
                createCreateVaultState(
                    biometric = biometric.forCreate,
                )

            persistableUserTokens == null && session is MasterSession.Key -> {
                // FIXME: If you get stuck in this state,
                //  then you can consider it a critical error that
                //  is not recoverable.
                VaultState.Loading
            }

            persistableUserTokens != null && session is MasterSession.Empty ->
                createUnlockVaultState(
                    tokens = persistableUserTokens,
                    biometric = biometric.forUnlock,
                    lockReason = session.reason,
                )

            persistableUserTokens != null && session is MasterSession.Key ->
                createMainVaultState(
                    tokens = persistableUserTokens,
                    biometric = biometric.forCreate,
                    masterKey = session.masterKey,
                    di = session.di,
                )

            else -> error("Unreachable statement")
        }
    }
        .distinctUntilChanged()
        .shareIn(GlobalScope, SharingStarted.WhileSubscribed(10000L), replay = 1)

    constructor(directDI: DirectDI) : this(
        di = directDI.di,
        biometricStatusUseCase = directDI.instance(),
        getVaultSession = directDI.instance(),
        putVaultSession = directDI.instance(),
        disableBiometric = directDI.instance(),
        keyReadWriteRepository = directDI.instance(),
        sessionMetadataReadWriteRepository = directDI.instance(),
        getBiometricRequireConfirmation = directDI.instance(),
        getBiometricRemainingDuration = directDI.instance(),
        biometricKeyEncryptUseCase = directDI.instance(),
        decryptBiometricKeyUseCase = directDI.instance(),
        authConfirmMasterKeyUseCase = directDI.instance(),
        authGenerateMasterKeyUseCase = directDI.instance(),
    )

    override fun invoke() = sharedFlow

    private class ComplexBiometricStatus(
        val forUnlock: BiometricStatus,
        val forCreate: BiometricStatus,
    )

    private fun getBiometricStatusFlow() = biometricStatusUseCase()
        .flatMapLatest { biometric ->
            val forUnlockFlow = when (biometric) {
                is BiometricStatus.Available ->
                    getBiometricRemainingDuration()
                        .map { it.isPositive() }
                        .take(1) // We don't want the option to suddenly disappear
                        .map { valid ->
                            biometric.takeIf { valid } ?: BiometricStatus.Unavailable
                        }

                is BiometricStatus.Unavailable -> flowOf(biometric)
            }
            forUnlockFlow
                .map { forUnlock ->
                    ComplexBiometricStatus(
                        forUnlock = forUnlock,
                        forCreate = biometric,
                    )
                }
        }

    private suspend fun createCreateVaultState(
        biometric: BiometricStatus,
    ): VaultState {
        return VaultState.Create(
            createWithMasterPassword = VaultState.Create.WithPassword(
                getCreateIo = { password ->
                    generateMasterKey(password)
                        .flatMap { result ->
                            create(
                                result = result,
                                biometricTokens = null,
                            )
                        }
                        .flatTap {
                            writeLastPasswordUseTimestamp()
                        }
                        .dispatchOn(Dispatchers.Default)
                },
            ),
            createWithMasterPasswordAndBiometric = if (biometric is BiometricStatus.Available) {
                val getCipherForEncryption = biometric
                    .createCipher
                    .partially1(BiometricPurpose.Encrypt)
                    .let { block ->
                        // lambda
                        suspend { Either.catch(block) }
                    }
                    // Save the cipher for further use by
                    // the code in the block. This allows us to not
                    // return the cipher from the action.
                    .memoize()
                val requireConfirmation = getBiometricRequireConfirmation()
                    .first()
                VaultState.Create.WithBiometric(
                    getCipher = getCipherForEncryption,
                    getCreateIo = { password ->
                        generateMasterKey(password)
                            .flatMap { result ->
                                val masterKey = result.key
                                val cipherIo = ioEffect {
                                    getCipherForEncryption()
                                        .getOrElse { e ->
                                            throw e
                                        }
                                }
                                biometricKeyEncryptUseCase(cipherIo, masterKey)
                                    .handleErrorWith { e ->
                                        val newException = BiometricKeyEncryptException(e)
                                        ioRaise(newException)
                                    }
                                    .effectMap { encryptedMasterKey ->
                                        val cipher = getCipherForEncryption()
                                            .getOrElse { e ->
                                                throw e
                                            }
                                        create(
                                            result = result,
                                            biometricTokens = FingerprintBiometric(
                                                iv = cipher.iv,
                                                encryptedMasterKey = encryptedMasterKey,
                                            ),
                                        )
                                    }
                                    .flatten()
                            }
                            .flatTap {
                                writeLastPasswordUseTimestamp()
                            }
                            .dispatchOn(Dispatchers.Default)
                    },
                    requireConfirmation = requireConfirmation,
                )
            } else {
                null
            },
        )
    }

    private suspend fun createUnlockVaultState(
        tokens: Fingerprint,
        biometric: BiometricStatus,
        lockReason: String?,
    ): VaultState {
        val unlockMasterKey = authConfirmMasterKeyUseCase(tokens.master.salt, tokens.master.hash)
            .compose { password: String ->
                MasterPassword.of(password)
            }
        return VaultState.Unlock(
            unlockWithMasterPassword = VaultState.Unlock.WithPassword(
                getCreateIo = { password ->
                    unlockMasterKey(password)
                        // Try to unlock the vault using generated
                        // master key.
                        .map { it.key }
                        .flatMap(::unlock)
                        .flatTap {
                            writeLastPasswordUseTimestamp()
                        }
                        .dispatchOn(Dispatchers.Default)
                },
            ),
            unlockWithBiometric = if (biometric is BiometricStatus.Available && tokens.biometric != null) {
                val getCipherForDecryption = biometric
                    .createCipher
                    .partially1(BiometricPurpose.Decrypt(DKey(tokens.biometric.iv)))
                    // Handle key invalidation
                    .createCipherOrDisableBiometrics()
                    .let { block ->
                        // lambda
                        suspend { Either.catch(block) }
                    }
                    // Save the cipher for further use by
                    // the code in the block. This allows us to not
                    // return the cipher from the action.
                    .memoize()
                val requireConfirmation = getBiometricRequireConfirmation()
                    .first()
                VaultState.Unlock.WithBiometric(
                    getCipher = getCipherForDecryption,
                    getCreateIo = {
                        val encryptedMasterKey = tokens.biometric.encryptedMasterKey
                        val cipherIo = ioEffect {
                            getCipherForDecryption()
                                .getOrElse { e ->
                                    throw e
                                }
                        }
                        decryptBiometricKeyUseCase(cipherIo, encryptedMasterKey)
                            .handleErrorWith { e ->
                                val newException = BiometricKeyDecryptException(e)
                                ioRaise(newException)
                            }
                            // Try to unlock the vault using decrypted
                            // master key.
                            .flatMap(::unlock)
                            .dispatchOn(Dispatchers.Default)
                    },
                    requireConfirmation = requireConfirmation,
                )
            } else {
                null
            },
            lockReason = lockReason,
        )
    }

    private fun createMainVaultState(
        tokens: Fingerprint,
        biometric: BiometricStatus,
        masterKey: MasterKey,
        di: DI,
    ): VaultState {
        val databaseManager by di.instance<DatabaseManager>()
        val unlockMasterKey = authConfirmMasterKeyUseCase(tokens.master.salt, tokens.master.hash)
            .compose { password: String ->
                MasterPassword.of(password)
            }
        val getCreateIo: (String, String) -> IO<Unit> = { currentPassword, newPassword ->
            unlockMasterKey(currentPassword) // verify current password is valid
                .flatMap {
                    generateMasterKey(newPassword)
                }
                .flatMap { result ->
                    val newMasterKey = result.key
                    databaseManager
                        .changePassword(newMasterKey)
                        .map {
                            result
                        }
                }
                .flatMap { result ->
                    create(
                        result = result,
                        biometricTokens = null,
                    )
                }
                .flatTap {
                    writeLastPasswordUseTimestamp()
                }
                .handleErrorTap {
                    it.printStackTrace()
                }
                .dispatchOn(Dispatchers.Default)
        }
        val changePassword = VaultState.Main.ChangePassword(
            key = tokens.master,
            withMasterPassword = VaultState.Main.ChangePassword.WithPassword(
                getCreateIo = getCreateIo,
            ),
            withMasterPasswordAndBiometric = if (biometric is BiometricStatus.Available) {
                val getCipherForEncryption = biometric
                    .createCipher
                    .partially1(BiometricPurpose.Encrypt)
                    // Handle key invalidation
                    .createCipherOrDisableBiometrics()
                    .let { block ->
                        // lambda
                        suspend { Either.catch(block) }
                    }
                    // Save the cipher for further use by
                    // the code in the block. This allows us to not
                    // return the cipher from the action.
                    .memoize()
                VaultState.Main.ChangePassword.WithBiometric(
                    getCipher = getCipherForEncryption,
                    getCreateIo = { currentPassword, newPassword ->
                        unlockMasterKey(currentPassword) // verify current password is valid
                            .flatMap {
                                generateMasterKey(newPassword)
                            }
                            .flatMap { result ->
                                val newMasterKey = result.key
                                databaseManager
                                    .changePassword(newMasterKey)
                                    .map {
                                        result
                                    }
                            }
                            .flatMap { result ->
                                val newMasterKey = result.key
                                val cipherIo = ioEffect {
                                    getCipherForEncryption()
                                        .getOrElse { e ->
                                            throw e
                                        }
                                }
                                biometricKeyEncryptUseCase(cipherIo, newMasterKey)
                                    .handleErrorWith { e ->
                                        val newException = BiometricKeyEncryptException(e)
                                        ioRaise(newException)
                                    }
                                    .effectMap { encryptedNewMasterKey ->
                                        val cipher = getCipherForEncryption()
                                            .getOrElse { e ->
                                                throw e
                                            }
                                        create(
                                            result = result,
                                            biometricTokens = FingerprintBiometric(
                                                iv = cipher.iv,
                                                encryptedMasterKey = encryptedNewMasterKey,
                                            ),
                                        )
                                    }
                                    .flatten()
                            }
                            .flatTap {
                                writeLastPasswordUseTimestamp()
                            }
                            .dispatchOn(Dispatchers.Default)
                    },
                )
            } else {
                null
            },
        )
        return VaultState.Main(
            masterKey = masterKey,
            changePassword = changePassword,
            di = di,
        )
    }

    private fun (suspend () -> LeBiometricCipher).createCipherOrDisableBiometrics() = this
        .let { createCipher ->
            // Lambda.
            suspend {
                try {
                    createCipher()
                } catch (e: KeyException) {
                    // If the key is not valid, then we want to disable the
                    // biometrics for now.
                    disableBiometric()
                        .crashlyticsTap()
                        .attempt()
                        .bind()
                    throw e
                }
            }
        }

    /**
     * Save the current vault tokens to the persistent
     * storage.
     */
    private fun create(
        result: AuthResult,
        biometricTokens: FingerprintBiometric? = null,
    ): IO<Unit> {
        val token = Fingerprint(
            master = result.token,
            biometric = biometricTokens,
        )
        return keyReadWriteRepository.put(token)
            .flatMap {
                unlock(
                    masterKey = result.key,
                )
            }
    }

    private fun unlock(
        masterKey: MasterKey,
    ): IO<Unit> = ioEffect {
        val moduleDi = DI.Module("lalala") {
            createSubDi(
                masterKey = masterKey,
            )
        }
        val subDi =
            subDI(di, false, Copy.None) {
                import(moduleDi)
            }
        MasterSession.Key(
            masterKey = masterKey,
            di = subDi,
            origin = MasterSession.Key.Authenticated,
            createdAt = Clock.System.now(),
        )
    }.flatMap(putVaultSession)

    private fun writeLastPasswordUseTimestamp(): IO<Unit> = sessionMetadataReadWriteRepository
        .setLastPasswordUseTimestamp(instant = Clock.System.now())
}
