package com.artemchep.keyguard.common.usecase.impl

import arrow.core.Either
import arrow.core.compose
import arrow.core.getOrElse
import arrow.core.partially1
import com.artemchep.keyguard.provider.keyguard.KeyguardAuthorityKeys
import com.artemchep.keyguard.provider.keyguard.KeyguardAuthorityResealer
import com.artemchep.keyguard.common.exception.MasterPasswordChangedException
import com.artemchep.keyguard.common.exception.PasswordMismatchException
import com.artemchep.keyguard.common.exception.UnsupportedMasterKdfVersionException
import com.artemchep.keyguard.common.exception.YubiKeyUnlockDecryptException
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
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.ioRaise
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.model.AuthResult
import com.artemchep.keyguard.common.model.BiometricAuthException
import com.artemchep.keyguard.common.model.BiometricBindingException
import com.artemchep.keyguard.common.model.BiometricPurpose
import com.artemchep.keyguard.common.model.BiometricStatus
import com.artemchep.keyguard.common.model.DKey
import com.artemchep.keyguard.util.fido2.FIDO2_INPUT_LENGTH
import com.artemchep.keyguard.util.foundation.constantTimeEquals
import com.artemchep.keyguard.common.model.Fingerprint
import com.artemchep.keyguard.common.model.FingerprintBiometric
import com.artemchep.keyguard.common.model.MasterKdf
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.model.MasterPassword
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.model.VaultState
import com.artemchep.keyguard.common.model.YUBIKEY_UNLOCK_HKDF_INFO
import com.artemchep.keyguard.common.service.biometrics.BiometricKeyRepository
import com.artemchep.keyguard.common.service.crypto.CipherEncryptor
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStoreUnavailableException
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.vault.FingerprintReadWriteRepository
import com.artemchep.keyguard.common.service.vault.SessionMetadataReadWriteRepository
import com.artemchep.keyguard.common.service.vault.VaultDatabaseSessionAccess
import com.artemchep.keyguard.common.service.vault.VaultSession
import com.artemchep.keyguard.common.service.vault.VaultSessionFactory
import com.artemchep.keyguard.common.usecase.AuthConfirmMasterKeyUseCase
import com.artemchep.keyguard.common.usecase.AuthGenerateMasterKeyUseCase
import com.artemchep.keyguard.common.usecase.BiometricKeyDecryptUseCase
import com.artemchep.keyguard.common.usecase.BiometricKeyEncryptUseCase
import com.artemchep.keyguard.common.usecase.BiometricStatusUseCase
import com.artemchep.keyguard.common.usecase.DisableBiometric
import com.artemchep.keyguard.common.usecase.GenerateMasterHashUseCase
import com.artemchep.keyguard.common.usecase.GetBiometricRemainingDuration
import com.artemchep.keyguard.common.usecase.GetBiometricRequireConfirmation
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.common.usecase.PutVaultSession
import com.artemchep.keyguard.common.usecase.UnlockUseCase
import com.artemchep.keyguard.common.usecase.YubiKeyUnlockAvailability
import com.artemchep.keyguard.common.util.catch
import com.artemchep.keyguard.common.util.flow.measureTimeTillFirstEvent
import com.artemchep.keyguard.common.util.memoize
import com.artemchep.keyguard.feature.crashlytics.crashlyticsTap
import com.artemchep.keyguard.platform.LeBiometricCipher
import com.artemchep.keyguard.provider.bitwarden.crypto.SymmetricCryptoKey2
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.math.pow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combineTransform
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// Creating, unlocking and changing the password of the vault share the fingerprint flow.
@Suppress("TooManyFunctions")
class UnlockUseCaseImpl(
    private val sessionFactory: VaultSessionFactory,
    private val vaultDatabaseSessionAccess: VaultDatabaseSessionAccess,
    private val biometricStatusUseCase: BiometricStatusUseCase,
    private val biometricKeyRepository: BiometricKeyRepository,
    private val getVaultSession: GetVaultSession,
    private val putVaultSession: PutVaultSession,
    private val disableBiometric: DisableBiometric,
    private val logRepository: LogRepository,
    private val keyReadWriteRepository: FingerprintReadWriteRepository,
    private val sessionMetadataReadWriteRepository: SessionMetadataReadWriteRepository,
    private val getBiometricRequireConfirmation: GetBiometricRequireConfirmation,
    private val getBiometricRemainingDuration: GetBiometricRemainingDuration,
    private val biometricKeyEncryptUseCase: BiometricKeyEncryptUseCase,
    private val decryptBiometricKeyUseCase: BiometricKeyDecryptUseCase,
    private val authConfirmMasterKeyUseCase: AuthConfirmMasterKeyUseCase,
    private val generateMasterHashUseCase: GenerateMasterHashUseCase,
    private val authGenerateMasterKeyUseCase: AuthGenerateMasterKeyUseCase,
    private val cryptoGenerator: CryptoGenerator,
    private val cipherEncryptor: CipherEncryptor,
    private val yubiKeyUnlockAvailability: YubiKeyUnlockAvailability,
    private val fido2UnlockAvailability: com.artemchep.keyguard.common.usecase.Fido2UnlockAvailability,
    private val fido2UnlockService: Fido2UnlockService,
    private val base64Service: com.artemchep.keyguard.common.service.text.Base64Service,
    /** Re-seals the authority keys of linked devices as the master password changes. */
    private val authorityResealer: KeyguardAuthorityResealer = KeyguardAuthorityResealer(
        keys = KeyguardAuthorityKeys(
            cryptoGenerator = cryptoGenerator,
            cipherEncryptor = cipherEncryptor,
        ),
    ),
    /** Waits before retrying to open an unavailable fingerprint store. */
    private val delayForRetry: suspend (Duration) -> Unit = { delay(it) },
    private val scope: CoroutineScope = GlobalScope,
) : UnlockUseCase {
    companion object {
        private const val TAG = "UnlockFlow"

        private val FINGERPRINT_RETRY_DELAY = 1.seconds
        private val FINGERPRINT_RETRY_DELAY_MAX = 30.seconds

        /** 1 s, 2 s, 4 s, 8 s, … capped at 30 s. */
        private fun getFingerprintRetryDelay(attempt: Long): Duration =
            (FINGERPRINT_RETRY_DELAY * 2.0.pow(attempt.toDouble()))
                .coerceAtMost(FINGERPRINT_RETRY_DELAY_MAX)
    }

    /**
     * Generates a new master key with the given key derivation from the
     * given password.
     */
    private fun generateMasterKey(
        password: String,
        kdf: MasterKdf.Argon2id,
    ): IO<AuthResult> = authGenerateMasterKeyUseCase(kdf)(MasterPassword.of(password))

    /**
     * Runs the changes of the master password one at a time: each one
     * re-keys the database and replaces the fingerprint.
     */
    private val changeMasterPasswordMutex = Mutex()

    private val creatingFrom = MutableStateFlow<MasterSession.Empty?>(null)

    private val sharedFlow = combineTransform(
        getFingerprintFlow()
            .measureTimeTillFirstEvent { d, _ ->
                val msg = "Fetching the vault info took $d"
                logRepository.post(TAG, msg, level = LogLevel.INFO)
            },
        getBiometricStatusFlow()
            .measureTimeTillFirstEvent { d, _ ->
                val msg = "Fetching the biometric status took $d"
                logRepository.post(TAG, msg, level = LogLevel.INFO)
            },
        getVaultSession(),
        creatingFrom,
    ) { persistableUserTokens, biometric, session, _ ->
        // Read the guard directly: the fingerprint update can reach combine before
        // its creation-start update. Preserve the existing setup screen until both
        // writes are observed, or a subsequent lock replaces the starting session.
        val startingSession = creatingFrom.value
        if (startingSession != null) {
            if (session === startingSession ||
                session is MasterSession.Key && persistableUserTokens == null
            ) return@combineTransform
            creatingFrom.compareAndSet(startingSession, null)
        }
        val state = when {
            persistableUserTokens == null && session is MasterSession.Empty ->
                createCreateVaultState(
                    biometric = biometric.forCreate,
                    session = session,
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
                    lockInfo = session.lockInfo,
                )

            persistableUserTokens != null && session is MasterSession.Key -> {
                // The vault is open, so the master key is at hand: restore the
                // platform key behind the saved biometric binding if it is gone.
                // Doing it here, rather than inside the unlock action, covers a
                // restored persisted session and avoids re-emitting the Unlock
                // state while the unlock action is still running.
                rearmBiometricIfNeeded(
                    persistableUserTokens,
                    biometric.forCreate,
                    session.masterKey,
                ).bind()
                createMainVaultState(
                    tokens = persistableUserTokens,
                    biometric = biometric.forCreate,
                    masterKey = session.masterKey,
                    session = session.session,
                )
            }

            else -> error("Unreachable statement")
        }
        emit(state)
    }
        .distinctUntilChanged()
        .measureTimeTillFirstEvent(
            predicate = { it !is VaultState.Loading },
        ) { d, _ ->
            val msg = "Initializing the app took $d"
            logRepository.post(TAG, msg, level = LogLevel.INFO)
        }
        .shareIn(scope, SharingStarted.WhileSubscribed(10000L), replay = 1)


    override fun invoke() = sharedFlow

    /**
     * The persisted fingerprint. A store that cannot be opened must not read as
     * "no vault": that would show the Create screen and let the user overwrite the
     * real vault. Retries opening it instead; until then no vault state is emitted
     * and the app keeps showing Loading.
     */
    private fun getFingerprintFlow(): Flow<Fingerprint?> = keyReadWriteRepository.get()
        .retryWhen { cause, attempt ->
            if (cause !is KeyValueStoreUnavailableException) {
                return@retryWhen false
            }
            val msg = "The vault info is unavailable, retry #${attempt + 1}: ${cause.cause}"
            logRepository.post(TAG, msg, level = LogLevel.WARNING)
            delayForRetry(getFingerprintRetryDelay(attempt))
            true
        }

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
        session: MasterSession.Empty,
    ): VaultState {
        // A failed or cancelled creation must release the guard so the persisted
        // fingerprint determines whether the user can retry setup or unlock.
        @Suppress("TooGenericExceptionCaught")
        fun IO<Unit>.withCreationGuard(): IO<Unit> = ioEffect {
            check(creatingFrom.compareAndSet(null, session)) { "Vault creation is already in progress" }
            try {
                bind()
            } catch (e: Throwable) {
                creatingFrom.compareAndSet(session, null)
                throw e
            }
        }

        return VaultState.Create(
            createWithMasterPassword = VaultState.Create.WithPassword(
                getCreateIo = { password, kdf ->
                    generateMasterKey(password, kdf)
                        .flatMap { result ->
                            create(
                                result = result,
                                biometricTokens = null,
                            )
                        }
                        .flatTap {
                            writeLastPasswordUseTimestamp()
                        }
                        .withCreationGuard()
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
                    getCreateIo = { password, kdf ->
                        generateMasterKey(password, kdf)
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
                            .withCreationGuard()
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
        lockInfo: MasterSession.Empty.LockInfo?,
    ): VaultState {
        val unlockMasterKey = authConfirmMasterKeyUseCase(
            tokens.master.salt,
            tokens.master.hash,
            tokens.kdf,
        )
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
            unlockWithBiometric = if (biometric is BiometricStatus.Available && hasBiometricKey(tokens)) {
                val biometricTokens = requireNotNull(tokens.biometric)
                val getCipherForDecryption = biometric
                    .createCipher
                    .partially1(BiometricPurpose.Decrypt(DKey(biometricTokens.iv)))
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
                        val encryptedMasterKey = biometricTokens.encryptedMasterKey
                        val cipherIo = ioEffect {
                            getCipherForDecryption()
                                .getOrElse { e ->
                                    throw e
                                }
                        }
                        decryptBiometricKeyUseCase(cipherIo, encryptedMasterKey)
                            .map { masterKeyBytes ->
                                MasterKey(
                                    version = tokens.kdf.version,
                                    byteArray = masterKeyBytes,
                                )
                            }
                            .handleErrorWith { e ->
                                val newException = BiometricKeyDecryptException(e)
                                ioRaise(newException)
                            }
                            // Try to unlock the vault using decrypted
                            // master key.
                            .flatMap(::unlock)
                            .dispatchOn(Dispatchers.Default)
                    },
                    getFailureIo = { exception ->
                        ioRaise<Unit>(exception)
                            .handleErrorTap(
                                predicate = {
                                    exception.code == BiometricAuthException.ERROR_KEY_INVALIDATED
                                },
                            ) {
                                // The saved binding can never succeed again, so remove
                                // the biometric unlock without failing the caller.
                                disableBiometric()
                                    .crashlyticsTap()
                                    .attempt()
                                    .bind()
                            }
                    },
                    requireConfirmation = requireConfirmation,
                )
            } else {
                null
            },
            unlockWithYubiKey = if (yubiKeyUnlockAvailability.isSupported()) {
                tokens.yubiKey?.let { protector ->
                    VaultState.Unlock.WithYubiKey(
                        slot = protector.slot,
                        challenge = protector.challenge,
                        getCreateIo = { response ->
                            decryptYubiKeyMasterKey(
                                tokens = tokens,
                                response = response,
                            )
                                .flatMap(::unlock)
                                .dispatchOn(Dispatchers.Default)
                        },
                    )
                }
            } else {
                null
            },
            unlockWithFido2 = tokens.fido2?.takeIf { fido2UnlockAvailability.isSupported() }?.let { protector ->
                VaultState.Unlock.WithFido2(
                    getRequest = {
                        com.artemchep.keyguard.util.fido2.Fido2Operation.Derive(
                            base64Service.decode(protector.credentialId),
                            base64Service.decode(protector.salt),
                            cryptoGenerator.seed(FIDO2_INPUT_LENGTH),
                        )
                    },
                    getCreateIo = { secret ->
                        ioEffect { fido2UnlockService.decrypt(tokens, secret) }
                            .flatMap(::unlock)
                            .dispatchOn(Dispatchers.Default)
                    },
                )
            },
            lockInfo = lockInfo?.run {
                VaultState.Unlock.LockInfo(
                    type = type,
                    timestamp = timestamp,
                    reason = reason,
                )
            },
        )
    }

    private fun decryptYubiKeyMasterKey(
        tokens: Fingerprint,
        response: ByteArray,
    ): IO<MasterKey> = ioEffect {
        val protector = requireNotNull(tokens.yubiKey)
        val wrappingKey = cryptoGenerator.hkdf(
            seed = response,
            salt = protector.hkdfSalt,
            info = YUBIKEY_UNLOCK_HKDF_INFO.encodeToByteArray(),
            length = 64,
        )
        val plainBytes = cipherEncryptor.decode2(
            cipher = protector.encryptedMasterKey,
            symmetricCryptoKey = SymmetricCryptoKey2(wrappingKey),
        ).data
        MasterKey(
            version = tokens.kdf.version,
            byteArray = plainBytes,
        )
    }.handleErrorWith { e ->
        ioRaise(YubiKeyUnlockDecryptException(e))
    }

    private fun createMainVaultState(
        tokens: Fingerprint,
        biometric: BiometricStatus,
        masterKey: MasterKey,
        session: VaultSession,
    ): VaultState {
        val databaseManager = vaultDatabaseSessionAccess.get(session) ?: return VaultState.Loading
        val getCreateIo = { currentPassword: String, change: VaultState.Main.ChangePassword.Change ->
            changeMasterPassword(
                databaseManager = databaseManager,
                tokens = tokens,
                currentPassword = currentPassword,
                change = change,
                getBiometricTokens = null,
            )
        }
        val changePassword = VaultState.Main.ChangePassword(
            key = tokens.master,
            kdf = tokens.kdf,
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
                    getCreateIo = { currentPassword, change ->
                        changeMasterPassword(
                            databaseManager = databaseManager,
                            tokens = tokens,
                            currentPassword = currentPassword,
                            change = change,
                        ) { newMasterKey ->
                            val cipherIo = ioEffect {
                                getCipherForEncryption()
                                    .getOrElse { e ->
                                        throw e
                                    }
                            }
                            val encryptedNewMasterKey = biometricKeyEncryptUseCase(cipherIo, newMasterKey)
                                .handleErrorWith { e ->
                                    val newException = BiometricKeyEncryptException(e)
                                    ioRaise(newException)
                                }
                                .bind()
                            val cipher = getCipherForEncryption()
                                .getOrElse { e ->
                                    throw e
                                }
                            FingerprintBiometric(
                                iv = cipher.iv,
                                encryptedMasterKey = encryptedNewMasterKey,
                            )
                        }
                    },
                )
            } else {
                null
            },
        )
        return VaultState.Main(
            masterKey = masterKey,
            changePassword = changePassword,
            session = session,
        )
    }

    /**
     * Applies the [change] to the vault that [currentPassword] opens: a new master
     * password or a new key derivation.
     *
     * The slow and fallible work runs before the database is re-keyed, so a failure there
     * changes nothing. From the re-key on, the vault opens only with the new fingerprint:
     * nothing may stop its writing, or neither password would open the vault.
     *
     * The changes run one at a time. A change that starts from an outdated fingerprint
     * [tokens] fails with [MasterPasswordChangedException]: its current password belongs
     * to a master key the database no longer uses.
     *
     * @param change a new password keeps the current key derivation, or moves a legacy
     *   or unknown one to [MasterKdf.Argon2id.DEFAULT]. A new key derivation keeps the
     *   current password.
     * @param getBiometricTokens seals the new master key for the biometric unlock, or `null`
     *   to turn the biometric unlock off.
     */
    private fun changeMasterPassword(
        databaseManager: VaultDatabaseManager,
        tokens: Fingerprint,
        currentPassword: String,
        change: VaultState.Main.ChangePassword.Change,
        getBiometricTokens: (suspend (MasterKey) -> FingerprintBiometric)?,
    ): IO<VaultState.Main.ChangePassword.Report> = ioEffect {
        changeMasterPasswordMutex.withLock {
            // Another change could have replaced the fingerprint
            // while this one was waiting for its turn.
            val latestTokens = keyReadWriteRepository.get().first()
            if (latestTokens?.master != tokens.master) {
                throw MasterPasswordChangedException()
            }

            generateMasterHashUseCase.verifyPassword(tokens, currentPassword).bind()
            // The authority keys use their own key derivation, so a change of the key
            // derivation alone leaves them as they are.
            val (newPassword, newKdf, reseal) = when (change) {
                is VaultState.Main.ChangePassword.Change.Password -> {
                    val kdf = tokens.kdf as? MasterKdf.Argon2id ?: MasterKdf.Argon2id.DEFAULT
                    Triple(change.newPassword, kdf, true)
                }

                is VaultState.Main.ChangePassword.Change.Kdf -> Triple(currentPassword, change.kdf, false)
            }
            val result = generateMasterKey(newPassword, newKdf).bind()
            // The key derivations are slow: re-seal the authority keys in memory first.
            val prepared = if (reseal) {
                authorityResealer.prepare(databaseManager, currentPassword, newPassword)
            } else {
                null
            }
            val biometricTokens = getBiometricTokens?.invoke(result.key)
            val skipped = withContext(NonCancellable) {
                databaseManager
                    .changePassword(result.key)
                    .bind()
                val skipped = prepared
                    ?.let {
                        authorityResealer.commit(databaseManager, it.reseals) { e ->
                            val msg = "Failed to write the re-sealed authority key of a linked device: ${e.message}"
                            logRepository.post(TAG, msg, level = LogLevel.WARNING)
                        }
                    }
                    .orEmpty()
                create(
                    result = result,
                    biometricTokens = biometricTokens,
                ).bind()
                skipped
            }
            val stale = prepared?.stale.orEmpty()
            val notResealed = stale.size + skipped.size
            if (notResealed > 0) {
                val msg = "The authority keys of $notResealed linked device groups stay sealed " +
                        "under another password: ${stale.size} did not open, ${skipped.size} changed."
                logRepository.post(TAG, msg, level = LogLevel.WARNING)
            }
            writeLastPasswordUseTimestamp().bind()
            VaultState.Main.ChangePassword.Report(
                authoritiesNotResealed = notResealed,
            )
        }
    }
        .handleErrorTap {
            it.printStackTrace()
        }
        .dispatchOn(Dispatchers.Default)

    /**
     * `true` if there is a saved biometric binding and the platform
     * still holds the key that protects it.
     */
    private val hasBiometricKey: suspend (Fingerprint) -> Boolean = { tokens ->
        if (tokens.biometric == null) {
            false
        } else {
            // If the check itself fails, keep the option and let the
            // unlock attempt report the actual problem.
            biometricKeyRepository.exists()
                .attempt()
                .bind()
                .getOrElse { true }
        }
    }

    /**
     * Re-creates the platform key behind the saved biometric binding
     * when the platform has lost it: Linux keeps the key in memory only
     * and drops it when the app exits. Runs whenever the vault is open,
     * so the master key is already available and no prompt is needed.
     * Never fails.
     */
    private fun rearmBiometricIfNeeded(
        tokens: Fingerprint,
        biometric: BiometricStatus,
        masterKey: MasterKey,
    ): IO<Unit> = ioEffect {
        if (tokens.biometric == null || biometric !is BiometricStatus.Available) {
            return@ioEffect
        }
        val rearmed = rearmBiometricBinding(
            tokens = tokens,
            masterKey = masterKey,
            biometric = biometric,
            biometricKeyRepository = biometricKeyRepository,
            keyReadWriteRepository = keyReadWriteRepository,
            biometricKeyEncryptUseCase = biometricKeyEncryptUseCase,
        )
        if (rearmed) {
            logRepository.post(TAG, "Re-created the biometric unlock key.", level = LogLevel.INFO)
        }
    }
        .crashlyticsTap()
        .attempt()
        .map { }

    private fun (suspend () -> LeBiometricCipher).createCipherOrDisableBiometrics() = this
        .let { createCipher ->
            // Lambda.
            suspend {
                try {
                    createCipher()
                } catch (e: BiometricBindingException) {
                    // The binding is already unusable, so remove the biometric
                    // unlock without failing the caller.
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
            kdf = result.kdf,
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
        val session = sessionFactory.createAuthenticated(masterKey)
        putVaultSession(
            MasterSession.Key(
                masterKey = masterKey,
                session = session,
                origin = MasterSession.Key.Authenticated,
                createdAt = Clock.System.now(),
            ),
        ).bind()
    }

    private fun writeLastPasswordUseTimestamp(): IO<Unit> = sessionMetadataReadWriteRepository
        .setLastPasswordUseTimestamp(instant = Clock.System.now())
}

/**
 * Verifies that the [password] gives the master hash of the [tokens].
 * Only the hash stage runs: the master key is not derived.
 */
private fun GenerateMasterHashUseCase.verifyPassword(
    tokens: Fingerprint,
    password: String,
): IO<Unit> = invoke(MasterPassword.of(password), tokens.master.salt, tokens.kdf)
    .flatMap { hash ->
        if (hash.byteArray.constantTimeEquals(tokens.master.hash.byteArray)) {
            io(Unit)
        } else {
            ioRaise(PasswordMismatchException())
        }
    }
    .handleErrorWith(
        predicate = { e -> e is UnsupportedMasterKdfVersionException },
    ) {
        ioRaise(PasswordMismatchException())
    }
