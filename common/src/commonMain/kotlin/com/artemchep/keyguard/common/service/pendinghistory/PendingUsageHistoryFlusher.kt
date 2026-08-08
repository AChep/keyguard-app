package com.artemchep.keyguard.common.service.pendinghistory

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.throwIfFatalOrCancellation
import com.artemchep.keyguard.common.model.AddGpgUsageHistoryRequest
import com.artemchep.keyguard.common.model.AddSshUsageHistoryRequest
import com.artemchep.keyguard.common.model.GpgUsageHistoryRequestType
import com.artemchep.keyguard.common.model.GpgUsageHistoryResponseType
import com.artemchep.keyguard.common.model.SshUsageHistoryRequestType
import com.artemchep.keyguard.common.model.SshUsageHistoryResponseType
import com.artemchep.keyguard.common.service.keyvalue.VaultSettingsKeyValueStore
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.usecase.AddGpgUsageHistory
import com.artemchep.keyguard.common.usecase.AddSshUsageHistory
import com.artemchep.keyguard.nativecrypto.NativeCrypto
import com.artemchep.keyguard.nativecrypto.NativeCryptoPrimitives
import com.artemchep.keyguard.nativecrypto.NativeSshKeyType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import org.kodein.di.DirectDI
import org.kodein.di.instance
import kotlin.time.Instant

/**
 * Drains the pending usage-history queue into the vault database.
 *
 * Runs with an unlocked session: first makes sure the envelope key pair
 * exists (private half in the vault settings store, public half in the
 * app settings where the locked-state writers can reach it), then opens
 * and inserts each queued row. Rows that cannot be opened — sealed to a
 * key from a previous vault, or corrupt — are dropped.
 */
class PendingUsageHistoryFlusher(
    private val queue: PendingUsageHistoryQueue,
    private val addGpgUsageHistory: AddGpgUsageHistory,
    private val addSshUsageHistory: AddSshUsageHistory,
    private val vaultSettingsStore: VaultSettingsKeyValueStore,
    private val settingsRepository: SettingsReadWriteRepository,
    private val base64Service: Base64Service,
    private val json: Json,
    private val logRepository: LogRepository,
) {
    companion object {
        private const val TAG = "PendingUsageHistoryFlusher"

        private const val PRIVATE_KEY_PREF_KEY = "content_private_key"
        private const val RSA_KEY_BITS = 3072

        /**
         * Flushers are session-scoped, so a session switch can briefly
         * overlap two of them; without the lock both could generate a
         * key pair on the first-ever unlock and rows sealed to the
         * losing public key would become unreadable.
         */
        private val envelopeKeyMutex = Mutex()
    }

    constructor(directDI: DirectDI) : this(
        queue = directDI.instance(),
        addGpgUsageHistory = directDI.instance(),
        addSshUsageHistory = directDI.instance(),
        vaultSettingsStore = directDI.instance(),
        settingsRepository = directDI.instance(),
        base64Service = directDI.instance(),
        json = directDI.instance(),
        logRepository = directDI.instance(),
    )

    fun flush(): IO<PendingUsageHistoryFlushResult> = ioEffect {
        val privateKeyPkcs8 = ensureEnvelopeKeys()
        try {
            val rows = queue.get()
                .bind()
            var deferredRows = 0
            rows.forEach { row ->
                val removed = flushRow(privateKeyPkcs8, row)
                if (!removed) {
                    deferredRows += 1
                }
            }
            PendingUsageHistoryFlushResult(
                deferredRows = deferredRows,
            )
        } finally {
            privateKeyPkcs8.fill(0)
        }
    }

    /**
     * Returns the envelope private key, generating and publishing a new
     * key pair when none exists yet. The public half is re-derived and
     * re-published every time so that a wiped or stale app-settings copy
     * heals on the next unlock.
     */
    private suspend fun ensureEnvelopeKeys(): ByteArray = envelopeKeyMutex.withLock {
        val privateKeyPref = vaultSettingsStore.getString(
            key = PRIVATE_KEY_PREF_KEY,
            defaultValue = "",
        )
        val existingBase64 = privateKeyPref.first()
        val privateKeyPkcs8 = if (existingBase64.isNotEmpty()) {
            base64Service.decode(existingBase64)
        } else {
            val pkcs8 = generatePrivateKeyPkcs8()
            val pkcs8Base64 = base64Service.encodeToString(pkcs8)
            privateKeyPref
                .setAndCommit(pkcs8Base64)
                .bind()
            pkcs8
        }
        val publicKeySpki = NativeCryptoPrimitives.rsaPublicKeySpkiFromPkcs8(privateKeyPkcs8)
        val storedPublicKeySpki = settingsRepository.getExposedContentPublicKey()
            .first()
        if (storedPublicKeySpki == null || !storedPublicKeySpki.contentEquals(publicKeySpki)) {
            settingsRepository.setExposedContentPublicKey(publicKeySpki)
                .bind()
        }
        return privateKeyPkcs8
    }

    private fun generatePrivateKeyPkcs8(): ByteArray {
        val material = NativeCrypto.ssh.generate(
            type = NativeSshKeyType.RSA,
            rsaBits = RSA_KEY_BITS,
        )
        try {
            val description = NativeCrypto.ssh.describe(
                type = NativeSshKeyType.RSA,
                privateKey = material.privateKey,
                publicKey = material.publicKey,
            )
            val export = NativeCrypto.ssh.exportCxf(
                privateKeyPem = description.privateKeyPem,
                publicKeyOpenSsh = description.publicKeyOpenSsh,
            )
            return export.privateKeyPkcs8
        } finally {
            material.privateKey.fill(0)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun flushRow(
        privateKeyPkcs8: ByteArray,
        row: SealedPendingUsageHistory,
    ): Boolean {
        val payload = try {
            val plaintext = PendingUsageHistoryEnvelope.open(
                privateKeyPkcs8 = privateKeyPkcs8,
                blob = row.payload,
            )
            try {
                json.decodeFromString<PendingUsageHistoryPayload>(plaintext.decodeToString())
            } finally {
                plaintext.fill(0)
            }
        } catch (e: Exception) {
            e.throwIfFatalOrCancellation()
            null
        }
        val protocol = payload
            ?.let { p ->
                PendingUsageHistory.Protocol.entries
                    .firstOrNull { it.name == p.protocol }
            }
        if (payload == null || protocol == null) {
            logRepository.post(
                tag = TAG,
                message = "Dropping an unreadable pending usage history entry.",
                level = LogLevel.WARNING,
            )
            queue.remove(row.id)
                .bind()
            return true
        }
        // A cipher referenced by the event may have been deleted while the
        // vault was locked; the foreign key would reject the insert, so we
        // retry once without the link before giving up on the row.
        val inserted = insert(protocol, payload, row) ||
                payload.cipherId != null &&
                insert(protocol, payload.copy(cipherId = null), row)
        if (inserted) {
            queue.remove(row.id)
                .bind()
        }
        return inserted
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun insert(
        protocol: PendingUsageHistory.Protocol,
        payload: PendingUsageHistoryPayload,
        row: SealedPendingUsageHistory,
    ): Boolean = try {
        val instant = Instant.fromEpochMilliseconds(row.timestampEpochMilliseconds)
        when (protocol) {
            PendingUsageHistory.Protocol.OPENPGP -> {
                addGpgUsageHistory(
                    AddGpgUsageHistoryRequest(
                        cipherId = payload.cipherId,
                        sessionId = payload.sessionId,
                        caller = payload.caller,
                        request = enumValueOrUnknown(
                            payload.requestType,
                            GpgUsageHistoryRequestType.UNKNOWN,
                        ),
                        response = enumValueOrUnknown(
                            payload.responseType,
                            GpgUsageHistoryResponseType.UNKNOWN,
                        ),
                        fingerprint = payload.fingerprint,
                        keygrip = payload.keygrip,
                        instant = instant,
                        eventId = row.id,
                    ),
                ).bind()
            }

            PendingUsageHistory.Protocol.SSH -> {
                addSshUsageHistory(
                    AddSshUsageHistoryRequest(
                        cipherId = payload.cipherId,
                        sessionId = payload.sessionId,
                        caller = payload.caller,
                        request = enumValueOrUnknown(
                            payload.requestType,
                            SshUsageHistoryRequestType.UNKNOWN,
                        ),
                        response = enumValueOrUnknown(
                            payload.responseType,
                            SshUsageHistoryResponseType.UNKNOWN,
                        ),
                        fingerprint = payload.fingerprint,
                        instant = instant,
                        eventId = row.id,
                    ),
                ).bind()
            }
        }
        true
    } catch (e: Exception) {
        e.throwIfFatalOrCancellation()
        false
    }
}

data class PendingUsageHistoryFlushResult(
    val deferredRows: Int,
) {
    val isComplete: Boolean
        get() = deferredRows == 0
}

private inline fun <reified T : Enum<T>> enumValueOrUnknown(
    value: String,
    unknown: T,
): T = enumValues<T>().firstOrNull { it.name == value } ?: unknown
