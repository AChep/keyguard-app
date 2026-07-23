package db_key_value.datastore.encrypted

import android.content.Context
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import db_key_value.datastore.encrypted.exception.SecureStorageColdStartRequiredException
import db_key_value.datastore.encrypted.exception.SecureStorageInitializationException
import db_key_value.datastore.encrypted.exception.WipeRequiredException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes the one-time creation of the AndroidKeyStore master key and the shared
 * Tink keyset that every secure DataStore depends on, and recovers from undecryptable
 * state by wiping and recreating it (rather than bricking the app).
 *
 * A failure of one store's payload never disables the others, and a failure is never
 * latched permanently: the next access retries from scratch.
 */
internal class SecureStorageCoordinator(
    scope: CoroutineScope,
    private val artifacts: SecureStorageArtifacts,
    private val classifyFailure: (
        throwable: Throwable,
        stage: SecureStorageStage,
        attempt: Int,
    ) -> SecureStorageDisposition = { throwable, stage, attempt ->
        classifySecureStorageFailure(throwable = throwable, stage = stage, attempt = attempt)
    },
    private val delayForRetry: suspend (Long) -> Unit = { delay(it) },
    private val log: (message: String, level: LogLevel) -> Unit = { _, _ -> },
) {
    companion object {
        private const val TAG = "SecureStorage"

        fun create(
            context: Context,
            logRepository: LogRepository,
        ): SecureStorageCoordinator =
            SecureStorageCoordinator(
                scope = CoroutineScope(Dispatchers.IO),
                artifacts = AndroidSecureStorageArtifacts(context.applicationContext),
                log = { message, level ->
                    logRepository.post(
                        tag = TAG,
                        message = message,
                        level = level,
                    )
                },
            )
    }

    // A supervised child scope: one store's failed initialization can never cancel the
    // shared key-material job or a sibling store's work.
    private val scope =
        CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))

    /**
     * Serializes key-material initialization and store construction. In addition to
     * avoiding concurrent DataStore construction, this makes the transition to
     * [hasOpenedStore] atomic with respect to the decision whether a global wipe is
     * still safe.
     */
    private val lifecycleMutex = Mutex()

    // The single master-key/keyset initialization shared by every secure store. It is
    // cleared after a failure so the next access retries instead of latching a permanent
    // failure for the whole process.
    private var keyMaterial: Deferred<String>? = null

    /**
     * Encrypted DataStores keep their unwrapped Tink primitive for their lifetime.
     * Rotating the shared keyset after this becomes true would leave those stores
     * writing ciphertext with an obsolete key generation.
     */
    private var hasOpenedStore = false

    /**
     * Returns the master key alias, running (and sharing) the one-time key-material
     * initialization. Throws [SecureStorageInitializationException] only when the
     * keystore is unusable even after a clean-slate recovery attempt.
     */
    suspend fun masterKeyAlias(
    ): String = lifecycleMutex.withLock {
        awaitKeyMaterialLocked()
    }

    /** Must be called while [lifecycleMutex] is held. */
    private suspend fun awaitKeyMaterialLocked(): String {
        val allowGlobalWipe = !hasOpenedStore
        val deferred =
            keyMaterial
                ?: scope
                    .async(start = CoroutineStart.LAZY) {
                        initializeKeyMaterial(
                            allowGlobalWipe = allowGlobalWipe,
                        )
                    }.also { keyMaterial = it }
        deferred.start()
        return try {
            deferred.await()
        } catch (throwable: Throwable) {
            // A cancelled caller must not discard initialization that other callers are
            // still awaiting. A Deferred whose initialization itself was cancelled is
            // terminal, however, and must not be replayed forever on later accesses.
            if (throwable !is CancellationException || deferred.isCancelled) {
                if (keyMaterial === deferred) {
                    keyMaterial = null
                }
            }
            throw throwable
        }
    }

    /**
     * Opens a secure store: ensures key material, probes this store's existing
     * ciphertext for decryptability, performs any per-store corruption wipe, and only
     * then builds the DataStore. Global key recovery is a separate lifecycle concern
     * and is deferred to a cold start after any store has opened.
     */
    suspend fun <P : Any, T : Any> openStore(
        store: String,
        probe: suspend (masterKeyAlias: String) -> P,
        open: (preparedStore: P) -> T,
    ): T =
        lifecycleMutex.withLock {
            val masterKeyAlias = awaitKeyMaterialLocked()
            val preparedStore =
                probeStore(
                    store = store,
                    masterKeyAlias = masterKeyAlias,
                    probe = probe,
                )
            open(preparedStore)
                .also {
                    hasOpenedStore = true
                }
        }

    /** Must be called while [lifecycleMutex] is held. */
    private suspend fun <P : Any> probeStore(
        store: String,
        masterKeyAlias: String,
        probe: suspend (masterKeyAlias: String) -> P,
    ): P {
        var attempt = 1
        var payloadWiped = false
        while (true) {
            try {
                return probe(masterKeyAlias)
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) throw throwable
                val disposition =
                    classifyFailure(
                        throwable,
                        SecureStorageStage.PAYLOAD_READ,
                        attempt,
                    )
                log(
                    "store=$store attempt=$attempt ${disposition.logMessage()}",
                    disposition.logLevel(),
                )
                when (disposition) {
                    is SecureStorageDisposition.Retry -> {
                        if (attempt < disposition.maxAttempts) {
                            delayForRetry(disposition.delayMillis)
                            attempt += 1
                            continue
                        }
                        if (disposition.wipeOnExhaustion) {
                            // A payload builder crypto failure belongs to the shared key
                            // material, not to this individual store.
                            keyMaterial = null
                        }
                        throw SecureStorageInitializationException(throwable)
                    }

                    is SecureStorageDisposition.Wipe -> {
                        when (disposition.code) {
                            SecureStorageFailureCode.INTEGRITY_FAILURE -> {
                                // Definitive corruption of this one store's ciphertext:
                                // drop just this file and prepare a fresh primitive for the
                                // replacement store. Do not wipe repeatedly if preparation still
                                // reports corruption after the payload is gone.
                                if (payloadWiped) {
                                    throw SecureStorageInitializationException(throwable)
                                }
                                artifacts.wipeStore(store)
                                payloadWiped = true
                                attempt = 1
                                continue
                            }

                            SecureStorageFailureCode.KEY_UNAVAILABLE -> {
                                // The key is shared by every secure store. Invalidate its
                                // cached validation and let the next access enter the
                                // lifecycle-safe global recovery path.
                                keyMaterial = null
                                throw SecureStorageInitializationException(throwable)
                            }

                            else -> {
                                throw SecureStorageInitializationException(throwable)
                            }
                        }
                    }

                    is SecureStorageDisposition.Degrade -> {
                        // Do not cache a DataStore after a probe that could not establish
                        // that its payload is safe to use. A later access can retry.
                        throw SecureStorageInitializationException(throwable)
                    }
                }
            }
        }
    }

    private suspend fun initializeKeyMaterial(allowGlobalWipe: Boolean): String {
        var wiped = false
        while (true) {
            try {
                val inventory = runStage(SecureStorageStage.ARTIFACT_INVENTORY) {
                    artifacts.inspect()
                }
                if (inventory.isUndecryptable()) {
                    // Existing ciphertext/keyset can no longer be decrypted (e.g. restored
                    // without the hardware-backed key): wipe it and start from a clean install.
                    log(
                        "stage=artifact_inventory undecryptable inventory=$inventory",
                        LogLevel.WARNING,
                    )
                    if (wiped) {
                        throw SecureStorageInitializationException()
                    }
                    wipeAllForRecovery(
                        allowGlobalWipe = allowGlobalWipe,
                        cause = null,
                    )
                    wiped = true
                }

                val masterKeyAlias =
                    runStage(SecureStorageStage.MASTER_KEY) {
                        artifacts.getOrCreateMasterKey()
                    }
                runStage(SecureStorageStage.KEYSET) {
                    artifacts.validateKeyset(masterKeyAlias)
                }
                log("state=ready", LogLevel.INFO)
                return masterKeyAlias
            } catch (expectedWipe: WipeRequiredException) {
                if (wiped) {
                    throw SecureStorageInitializationException(expectedWipe.failure)
                }
                wipeAllForRecovery(
                    allowGlobalWipe = allowGlobalWipe,
                    cause = expectedWipe.failure,
                )
                wiped = true
            }
        }
    }

    private suspend fun <T> runStage(
        stage: SecureStorageStage,
        block: suspend () -> T,
    ): T {
        var attempt = 1
        while (true) {
            try {
                return block()
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) throw throwable
                val disposition = classifyFailure(throwable, stage, attempt)
                log(
                    "stage=$stage attempt=$attempt ${disposition.logMessage()}",
                    disposition.logLevel(),
                )
                when (disposition) {
                    is SecureStorageDisposition.Retry -> {
                        if (attempt < disposition.maxAttempts) {
                            delayForRetry(disposition.delayMillis)
                            attempt += 1
                            continue
                        }
                        if (disposition.wipeOnExhaustion) {
                            throw WipeRequiredException(throwable)
                        }
                        throw SecureStorageInitializationException(throwable)
                    }

                    is SecureStorageDisposition.Wipe -> {
                        throw WipeRequiredException(throwable)
                    }

                    is SecureStorageDisposition.Degrade -> {
                        throw SecureStorageInitializationException(throwable)
                    }
                }
            }
        }
    }

    private suspend fun wipeAllForRecovery(
        allowGlobalWipe: Boolean,
        cause: Throwable?,
    ) {
        if (!allowGlobalWipe) {
            log("state=cold_start_required", LogLevel.ERROR)
            throw SecureStorageColdStartRequiredException(cause)
        }
        artifacts.wipeAll()
    }
}
