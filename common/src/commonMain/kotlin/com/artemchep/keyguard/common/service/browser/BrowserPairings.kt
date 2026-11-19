package com.artemchep.keyguard.common.service.browser

import com.artemchep.keyguard.common.io.runCatchingUnlessCancelled
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.session.BrowserExtensionSessionDependencies
import com.artemchep.keyguard.feature.browserextension.protocol.BrowserError
import com.artemchep.keyguard.feature.browserextension.protocol.BrowserRequestException
import com.artemchep.keyguard.nativecrypto.NativeNoiseKeyPair
import kotlin.io.encoding.Base64
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The pairings of the extensions. Each one has a record outside of the vault,
 * which serves while the vault is locked, and a record in the vault, which
 * confirms it. While the vault is unlocked, a pairing counts only if both
 * records agree on its ID, key and origin: a process that writes the outside
 * record alone pairs nothing. App-scoped.
 */
internal class BrowserPairings(
    private val store: BrowserPairingStore,
    private val vaultAccess: BrowserVaultAccess,
    private val cryptoGenerator: CryptoGenerator,
) {
    private val mutex = Mutex()

    /** A null snapshot ends the session without reporting a revocation. */
    val stored: Flow<List<BrowserPairingRecord>?> = store.records

    /** The pairings to show: while the vault is unlocked, the confirmed ones only. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val listed: Flow<List<BrowserPairing>> = combine(
        store.records,
        vaultAccess.unlocked.flatMapLatest { dependencies ->
            dependencies?.vaultPairings()?.identities ?: flowOf(null)
        },
    ) { records, confirmed ->
        records.orEmpty()
            .filter { confirmed == null || it.identity in confirmed }
            .map(BrowserPairingRecord::pairing)
    }

    /** See [BrowserPairingStore.appKeyPair]. */
    suspend fun appKeyPair(): NativeNoiseKeyPair? = store.appKeyPair()

    /** See [BrowserPairingStore.find]. */
    suspend fun find(publicKey: ByteArray, origin: String): BrowserPairing? = store.find(publicKey, origin)

    /** See [BrowserPairingStore.touch]. */
    suspend fun touch(id: String) = store.touch(id)

    /** An unreadable store cannot establish that a pairing was removed. */
    suspend fun confirmationError(id: String?, publicKey: ByteArray, origin: String): BrowserError? {
        val vault = vaultAccess.dependencies()?.vaultPairings()
            ?: return BrowserError.UNPAIRED.takeIf { id == null }
        val identity = id?.let { BrowserPairingIdentity(it, Base64.encode(publicKey), origin) }
        return runCatchingUnlessCancelled { vault.records() }.fold(
            onSuccess = { records ->
                when {
                    records == null -> BrowserError.UPDATE_REQUIRED
                    identity !in records -> BrowserError.UNPAIRED
                    else -> null
                }
            },
            onFailure = { BrowserError.NOT_ALLOWED },
        )
    }

    /**
     * Pairs the extension with [publicKey] at [origin], named [name], replacing
     * its earlier pairing there. Writes the record in the vault first, then
     * the one outside of it, and takes the first back if the second fails.
     *
     * @throws BrowserRequestException `vault_locked` while the vault is locked;
     *   `not_allowed` when a record can't be written.
     */
    suspend fun add(name: String, publicKey: ByteArray, origin: String): BrowserPairing = mutex.withLock {
        val vault = vaultAccess.dependencies()?.vaultPairings()
            ?: throw BrowserRequestException(BrowserError.VAULT_LOCKED)
        val outside = store.readRecords()
            ?: throw BrowserRequestException(BrowserError.UPDATE_REQUIRED)
        val identity = BrowserPairingIdentity(
            id = cryptoGenerator.uuid(),
            publicKey = Base64.encode(publicKey),
            origin = origin,
        )
        var previous: List<BrowserPairingIdentity> = emptyList()
        val confirmed = runCatchingUnlessCancelled {
            vault.update { records ->
                previous = records
                // The earlier pairing of the extension there is replaced.
                records.pruned(outside).filterNot { it.publicKey == identity.publicKey && it.origin == origin } +
                    identity
            }
        }.getOrDefault(false)
        if (!confirmed) throw BrowserRequestException(BrowserError.NOT_ALLOWED)
        val pairing = runCatchingUnlessCancelled { store.add(identity.id, name, publicKey, origin) }
            .getOrNull()
        if (pairing == null) {
            // The vault confirms no pairing that isn't stored.
            withContext(NonCancellable) {
                runCatchingUnlessCancelled { vault.update { previous } }
            }
            throw BrowserRequestException(BrowserError.NOT_ALLOWED)
        }
        pairing
    }

    /** Forgets the pairing [id], and while the vault is unlocked, its record there. */
    suspend fun remove(id: String) {
        mutex.withLock {
            store.remove(id)
            val vault = vaultAccess.dependencies()?.vaultPairings()
                ?: return
            val outside = store.readRecords()
                ?: return
            // A record left behind confirms nothing, and the next write prunes it.
            runCatchingUnlessCancelled {
                vault.update { records -> records.pruned(outside) }
            }
        }
    }

    /** Names the pairing [id] [name]; the vault's record has no name. */
    suspend fun rename(id: String, name: String) {
        store.rename(id, name)
    }

    private fun BrowserExtensionSessionDependencies.vaultPairings() = BrowserVaultPairingStore(vaultSettings)

    /** Without the records that have no record outside of the vault. */
    private fun List<BrowserPairingIdentity>.pruned(outside: List<BrowserPairingRecord>) =
        filter { record -> outside.any { it.identity == record } }
}
