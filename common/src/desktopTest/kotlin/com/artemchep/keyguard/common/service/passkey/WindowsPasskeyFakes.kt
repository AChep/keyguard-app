package com.artemchep.keyguard.common.service.passkey

import com.artemchep.keyguard.common.exception.credential.CredentialNotStoredException
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.AddCipherUsedPasskeyHistoryRequest
import com.artemchep.keyguard.common.model.AddCredentialCipherRequest
import com.artemchep.keyguard.common.model.DCollection
import com.artemchep.keyguard.common.model.DProfile
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.service.session.testVaultSessionLocker
import com.artemchep.keyguard.common.usecase.AddCipherUsedPasskeyHistory
import com.artemchep.keyguard.common.usecase.AddCredentialCipher
import com.artemchep.keyguard.common.usecase.GetCanWrite
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetCollections
import com.artemchep.keyguard.common.usecase.GetProfiles
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.util.webauthn.WebAuthnAuthenticator
import com.artemchep.keyguard.util.webauthn.WebAuthnAuthenticatorDataFactory
import com.artemchep.keyguard.util.webauthn.WebAuthnRegistrationRequest
import com.artemchep.keyguard.util.webauthn.entity.CreatePasskeyPubKeyCredParams
import com.artemchep.keyguard.util.windowspasskeys.WindowsPasskeyBridge
import com.artemchep.keyguard.util.windowspasskeys.WindowsPasskeyEvent
import com.artemchep.keyguard.util.windowspasskeys.WindowsPasskeyException
import com.artemchep.keyguard.util.windowspasskeys.WindowsPasskeyFailure
import com.artemchep.keyguard.util.windowspasskeys.WindowsPasskeyIdentity
import com.artemchep.keyguard.util.windowspasskeys.WindowsPasskeyResponse
import com.artemchep.keyguard.util.windowspasskeys.WindowsPasskeyStatus
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import kotlin.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Records what Windows accepts from the service. Like the native bridge, Windows accepts only
 * the first answer to a request, and Hello returns only when its prompt closes.
 */
@Suppress("TooManyFunctions")
internal class FakeWindowsPasskeyBridge : WindowsPasskeyBridge {
    data class Verification(
        val id: Long,
        val window: Long,
        val username: String,
        val hint: String,
        val unlocked: Boolean = false,
    )

    data class Completion(val id: Long, val response: WindowsPasskeyResponse?, val failure: WindowsPasskeyFailure?)

    var status = WindowsPasskeyStatus.ENABLED

    /** Delivered by [next], in order. */
    val events = Channel<WindowsPasskeyEvent>(Channel.UNLIMITED)
    val waits = mutableListOf<Long?>()

    /** The timeout of each [next] wait; null asks for the native bound. */
    val timeouts = mutableListOf<Duration?>()
    val completions = mutableListOf<Completion>()
    val verifications = mutableListOf<Verification>()

    /** The Windows Hello outcome. Verified at once unless a test replaces it. */
    var hello = CompletableDeferred(Unit)

    /** Requests Windows stopped waiting on by itself, e.g. after the site cancelled. */
    val ended = mutableSetOf<Long>()

    /** Windows cannot receive the responses, e.g. because they cannot be encoded. */
    var rejectsResponses = false
    val locks = mutableListOf<Boolean>()
    val syncs = mutableListOf<List<WindowsPasskeyIdentity>>()

    /** The next this many syncs fail. */
    var failingSyncs = 0

    /** The next this many clears fail. */
    var failingClears = 0
    var clears = 0

    /** The answer Windows accepted for the request, or null for a cancellation. */
    fun answer(id: Long): WindowsPasskeyResponse? = completions.first { it.id == id }.response

    /** Why Windows accepted no answer for the request; null when Keyguard abandoned it. */
    fun failure(id: Long): WindowsPasskeyFailure? = completions.first { it.id == id }.failure

    fun answered(id: Long) = completions.any { it.id == id }

    private fun requireLive(id: Long) {
        if (id in ended || answered(id)) throw WindowsPasskeyException(WindowsPasskeyException.CANCELLED)
    }

    override suspend fun readStatus() = status

    override suspend fun register() {
        status = WindowsPasskeyStatus.DISABLED
    }

    override suspend fun unregister() {
        status = WindowsPasskeyStatus.UNREGISTERED
    }

    override suspend fun clear() {
        if (failingClears > 0) {
            failingClears -= 1
            error("Windows rejected the clear")
        }
        clears += 1
    }

    /** Until a [serve], every [next] fails, as the native bridge does without a server. */
    var serving = true
    var serves = 0

    override suspend fun serve() {
        serves += 1
        serving = true
    }

    override suspend fun next(active: Long?, timeout: Duration?): WindowsPasskeyEvent {
        waits += active
        timeouts += timeout
        if (!serving) throw WindowsPasskeyException(WindowsPasskeyException.FAILED)
        return events.receive()
    }

    override suspend fun setLocked(locked: Boolean) {
        locks += locked
    }

    override suspend fun complete(id: Long, response: WindowsPasskeyResponse?, failure: WindowsPasskeyFailure?) {
        requireLive(id)
        // Like the native bridge, a response needs a verified user; otherwise the request ends.
        if (response != null && id !in verified) {
            completions += Completion(id, null, null)
            throw WindowsPasskeyException(WindowsPasskeyException.FAILED)
        }
        if (response != null && rejectsResponses) {
            // The request ends with an error for the site.
            completions += Completion(id, null, null)
            throw WindowsPasskeyException(WindowsPasskeyException.FAILED)
        }
        completions += Completion(id, response, failure)
    }

    /** The requests whose user was verified, which [complete] requires of a response. */
    val verified = mutableSetOf<Long>()

    override suspend fun verify(id: Long, window: Long, username: String, hint: String, unlocked: Boolean) {
        verifications += Verification(id, window, username, hint, unlocked)
        if (!unlocked) {
            // The OS has no dismiss API: cancellation waits for the prompt to close.
            withContext(NonCancellable) { hello.join() }
            requireLive(id)
            hello.await()
        }
        requireLive(id)
        verified += id
    }

    override suspend fun sync(identities: List<WindowsPasskeyIdentity>) {
        if (failingSyncs > 0) {
            failingSyncs -= 1
            error("Windows rejected the index")
        }
        syncs += identities
    }

    override suspend fun retire() = true
}

internal class FakeVaultSessions(initial: MasterSession = MasterSession.Empty()) : GetVaultSession {
    val state = MutableStateFlow(initial)

    override val valueOrNull: MasterSession get() = state.value

    override fun invoke(): Flow<MasterSession> = state

    /** The real inactivity timer, retiring the fake session just as a vault lock does. */
    fun locker(scope: CoroutineScope) = testVaultSessionLocker(scope) {
        (state.value as? MasterSession.Key)?.session?.retire()
        state.value = MasterSession.Empty()
    }
}

internal class FakeVault(vararg ciphers: DSecret) {
    val ciphers = MutableStateFlow(ciphers.toList())
    val profiles = MutableStateFlow(emptyList<DProfile>())
    val canWrite = MutableStateFlow(true)
    val collections = MutableStateFlow(emptyList<DCollection>())
    var storesCredentials = true
    val added = mutableListOf<AddCredentialCipherRequest>()
    val used = mutableListOf<AddCipherUsedPasskeyHistoryRequest>()

    val access = WindowsPasskeyVaultAccess {
        WindowsPasskeyVault(
            getCiphers = object : GetCiphers {
                override fun invoke(): Flow<List<DSecret>> = this@FakeVault.ciphers
            },
            getProfiles = object : GetProfiles {
                override fun invoke(): Flow<List<DProfile>> = profiles
            },
            addCredential = object : AddCredentialCipher {
                override fun invoke(request: AddCredentialCipherRequest): IO<AddCredentialCipher.Result> = {
                    added += request
                    // Like the real use case, a passkey the item does not store fails.
                    if (!storesCredentials) throw CredentialNotStoredException()
                    AddCredentialCipher.Result.Stored
                }
            },
            getCanWrite = object : GetCanWrite {
                override fun invoke(): Flow<Boolean> = canWrite
            },
            getCollections = object : GetCollections {
                override fun invoke(): Flow<List<DCollection>> = collections
            },
            recordUsage = object : AddCipherUsedPasskeyHistory {
                override fun invoke(request: AddCipherUsedPasskeyHistoryRequest): IO<Unit> = {
                    used += request
                }
            },
        )
    }
}

/** The production authenticator with native crypto, so signatures are real. */
internal fun testAuthenticator() = WebAuthnAuthenticator(
    json = Json,
    authenticatorDataFactory = WebAuthnAuthenticatorDataFactory(KEYGUARD_PASSKEY_AAGUID),
)

/** A stored passkey with a real private key, and the public key that verifies its signatures. */
internal class SigningPasskey(authenticator: WebAuthnAuthenticator) {
    private val registration = authenticator.createCredential(
        WebAuthnRegistrationRequest(
            rpId = TEST_RP_ID,
            userHandle = "dXNlcg",
            userName = "alice",
            pubKeyCredParams = listOf(CreatePasskeyPubKeyCredParams(type = "public-key", alg = -7.0)),
            discoverable = true,
        ),
        userVerified = true,
    )

    val credential = registration.credential.let {
        DSecret.Login.Fido2Credentials(
            credentialId = it.credentialId, keyType = it.keyType, keyAlgorithm = it.keyAlgorithm,
            keyCurve = it.keyCurve, keyValue = it.keyValue, rpId = it.rpId, rpName = it.rpName,
            counter = it.counter, userHandle = it.userHandle, userName = it.userName,
            userDisplayName = it.userDisplayName, discoverable = it.discoverable, creationDate = TEST_NOW,
        )
    }

    /** Whether [response] carries this key's signature over its authenticator data and the hash. */
    fun signed(response: WindowsPasskeyResponse): Boolean {
        val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(registration.publicKey))
        return Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey)
            update(response.authenticatorData + TEST_CLIENT_DATA_HASH)
            verify(requireNotNull(response.signature))
        }
    }
}
