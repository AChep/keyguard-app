package com.artemchep.keyguard.feature.auth.companion

import android.app.Application
import com.artemchep.keyguard.common.service.crypto.CipherEncryptor
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.provider.bitwarden.crypto.SymmetricCryptoKey2
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.kodein.di.DirectDI
import org.kodein.di.instance

internal class CompanionAuthSecurityAndroid(
    private val application: Application,
    private val json: Json,
    private val cryptoGenerator: CryptoGenerator,
    private val cipherEncryptor: CipherEncryptor,
    private val base64Service: Base64Service,
) {
    private val mutex = Mutex()
    private val pendingSessions = mutableMapOf<String, CompanionAuthPendingSession>()
    private val expectedPublicKeyParameters by lazy {
        val generator = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }
        val publicKey = generator.generateKeyPair().public as ECPublicKey
        publicKey.params
    }

    constructor(
        directDI: DirectDI,
    ) : this(
        application = directDI.instance(),
        json = directDI.instance(),
        cryptoGenerator = directDI.instance(),
        cipherEncryptor = directDI.instance(),
        base64Service = directDI.instance(),
    )

    suspend fun createEphemeralKeyPair(): CompanionAuthEphemeralKeyPair =
        withContext(Dispatchers.Default) {
            val generator = KeyPairGenerator.getInstance("EC").apply {
                initialize(ECGenParameterSpec("secp256r1"))
            }
            val keyPair = generator.generateKeyPair()
            CompanionAuthEphemeralKeyPair(
                publicKeyBase64 = base64Service.encodeToString(keyPair.public.encoded),
                privateKeyBase64 = base64Service.encodeToString(keyPair.private.encoded),
            )
        }

    fun isValidRemotePublicKey(
        publicKeyBase64: String,
    ): Boolean {
        if (publicKeyBase64.length > COMPANION_AUTH_MAX_PUBLIC_KEY_BASE64_CHARS) {
            return false
        }
        val bytes = runCatching {
            base64Service.decode(publicKeyBase64)
        }.getOrNull() ?: return false
        if (bytes.isEmpty() || bytes.size > COMPANION_AUTH_MAX_PUBLIC_KEY_BYTES) {
            return false
        }

        val publicKey = runCatching {
            decodePublicKey(bytes)
        }.getOrNull() as? ECPublicKey ?: return false
        return publicKey.params.hasSameParameters(expectedPublicKeyParameters) &&
            publicKey.w.isValidPoint(publicKey.params)
    }

    suspend fun getPendingSession(
        requestId: String,
    ): CompanionAuthPendingSession? = mutex.withLock {
        val normalizedRequestId = canonicalCompanionAuthRequestIdOrNull(requestId)
            ?: return@withLock null
        val session = pendingSessions[normalizedRequestId]
            ?: return@withLock null
        if (!session.isExpired()) {
            return@withLock session
        }

        pendingSessions.remove(normalizedRequestId)
        withContext(Dispatchers.IO) {
            requestDir(normalizedRequestId).deleteRecursively()
        }
        null
    }

    suspend fun sweepExpiredArtifacts(
        now: Long = System.currentTimeMillis(),
    ) = mutex.withLock {
        val expiredRequestIds = pendingSessions
            .values
            .filter { session ->
                session.isExpired(now) || session.isLaunchExpired(now)
            }
            .map { session ->
                session.requestId
            }
        expiredRequestIds.forEach { requestId ->
            pendingSessions.remove(requestId)
        }

        withContext(Dispatchers.IO) {
            expiredRequestIds.forEach { requestId ->
                requestDir(requestId).deleteRecursively()
            }
            sweepCompanionAuthRequestDirs(
                rootDir = companionAuthRootDir(),
                activeRequestIds = pendingSessions.keys.toSet(),
            )
        }
    }

    suspend fun putPendingSession(
        session: CompanionAuthPendingSession,
    ) = mutex.withLock {
        val normalizedRequestId = canonicalCompanionAuthRequestIdOrNull(session.requestId)
            ?: error("Invalid companion auth request id: ${session.requestId}")
        pendingSessions[normalizedRequestId] = session.copy(
            requestId = normalizedRequestId,
        )
    }

    suspend fun deletePendingSession(
        requestId: String,
    ) = mutex.withLock {
        val normalizedRequestId = canonicalCompanionAuthRequestIdOrNull(requestId)
            ?: return@withLock
        pendingSessions.remove(normalizedRequestId)
        withContext(Dispatchers.IO) {
            val dir = requestDir(normalizedRequestId)
            if (dir.exists() && dir.listFiles().isNullOrEmpty()) {
                dir.delete()
            }
        }
    }

    suspend fun encryptSecurePayload(
        session: CompanionAuthPendingSession,
        payload: CompanionAuthSecurePayload,
    ): CompanionAuthEncryptedPayload = withContext(Dispatchers.Default) {
        CompanionAuthEncryptedPayload(
            cipherText = encryptCipherText(
                session = session,
                plainText = json.encodeToString<CompanionAuthSecurePayload>(payload).encodeToByteArray(),
            ),
        )
    }

    suspend fun decryptSecurePayload(
        session: CompanionAuthPendingSession,
        encryptedPayload: CompanionAuthEncryptedPayload,
    ): CompanionAuthSecurePayload = withContext(Dispatchers.Default) {
        val plainText = decryptCipherText(
            session = session,
            cipherText = encryptedPayload.cipherText,
        )
        json.decodeFromString<CompanionAuthSecurePayload>(plainText.decodeToString())
    }

    suspend fun encryptBlob(
        session: CompanionAuthPendingSession,
        data: ByteArray,
    ): CompanionAuthEncryptedBlob = withContext(Dispatchers.Default) {
        CompanionAuthEncryptedBlob(
            cipherText = encryptCipherText(
                session = session,
                plainText = data,
            ),
        )
    }

    suspend fun decryptBlob(
        session: CompanionAuthPendingSession,
        blob: CompanionAuthEncryptedBlob,
    ): ByteArray = withContext(Dispatchers.Default) {
        decryptCipherText(
            session = session,
            cipherText = blob.cipherText,
        )
    }

    suspend fun incomingDir(
        requestId: String,
    ): File = withContext(Dispatchers.IO) {
        val normalizedRequestId = canonicalCompanionAuthRequestIdOrNull(requestId)
            ?: error("Invalid companion auth request id: $requestId")
        requestDir(normalizedRequestId)
    }

    internal fun requestDir(
        requestId: String,
    ): File = companionAuthRootDir().resolve(requestId)

    private fun encryptCipherText(
        session: CompanionAuthPendingSession,
        plainText: ByteArray,
    ): String {
        val key = requireSessionKey(session)
        return cipherEncryptor.encode2(
            cipherType = CipherEncryptor.Type.AesCbc256_HmacSha256_B64,
            plainText = plainText,
            symmetricCryptoKey = key,
        )
    }

    private fun decryptCipherText(
        session: CompanionAuthPendingSession,
        cipherText: String,
    ): ByteArray {
        val key = requireSessionKey(session)
        return cipherEncryptor.decode2(
            cipher = cipherText,
            symmetricCryptoKey = key,
        ).data
    }

    private fun requireSessionKey(
        session: CompanionAuthPendingSession,
    ): SymmetricCryptoKey2 {
        val remotePublicKeyBase64 = session.remotePublicKeyBase64
            ?: error("Remote public key is missing for ${session.requestId}.")
        val localPrivateKey = decodePrivateKey(session.localPrivateKeyBase64)
        val remotePublicKey = decodePublicKey(remotePublicKeyBase64)
        val agreement = KeyAgreement.getInstance("ECDH").apply {
            init(localPrivateKey)
            doPhase(remotePublicKey, true)
        }
        val sharedSecret = agreement.generateSecret()
        val info = buildSessionInfo(session)
        return SymmetricCryptoKey2(
            data = cryptoGenerator.hkdf(
                seed = sharedSecret,
                info = info,
                length = 64,
            ),
        )
    }

    private fun buildSessionInfo(
        session: CompanionAuthPendingSession,
    ): ByteArray {
        val watchNodeId = when (session.role) {
            CompanionAuthPendingSession.Role.Initiator -> session.localNodeId
            CompanionAuthPendingSession.Role.Receiver -> session.expectedNodeId
        }
        val phoneNodeId = when (session.role) {
            CompanionAuthPendingSession.Role.Initiator -> session.expectedNodeId
            CompanionAuthPendingSession.Role.Receiver -> session.localNodeId
        }
        return buildString {
            append("companion-auth:v")
            append(session.protocolVersion)
            append(':')
            append(session.requestId)
            append(':')
            append(session.provider.name)
            append(':')
            append(watchNodeId)
            append(':')
            append(phoneNodeId)
        }.encodeToByteArray()
    }

    private fun decodePrivateKey(
        privateKeyBase64: String,
    ): PrivateKey {
        val bytes = base64Service.decode(privateKeyBase64)
        val spec = PKCS8EncodedKeySpec(bytes)
        return KeyFactory.getInstance("EC").generatePrivate(spec)
    }

    private fun decodePublicKey(
        publicKeyBase64: String,
    ): PublicKey {
        val bytes = base64Service.decode(publicKeyBase64)
        return decodePublicKey(bytes)
    }

    private fun decodePublicKey(
        bytes: ByteArray,
    ): PublicKey {
        val spec = X509EncodedKeySpec(bytes)
        return KeyFactory.getInstance("EC").generatePublic(spec)
    }

    private fun companionAuthRootDir(): File = application.cacheDir.resolve(COMPANION_AUTH_ROOT_DIR_NAME)
}

private const val COMPANION_AUTH_ROOT_DIR_NAME = "companion-auth"

internal fun sweepCompanionAuthRequestDirs(
    rootDir: File,
    activeRequestIds: Set<String> = emptySet(),
) {
    val requestDirs = rootDir.listFiles()
        ?: return
    requestDirs.forEach { requestDir ->
        if (!requestDir.isDirectory) {
            requestDir.delete()
            return@forEach
        }

        val normalizedRequestId = canonicalCompanionAuthRequestIdOrNull(requestDir.name)
        if (normalizedRequestId == null) {
            requestDir.deleteRecursively()
            return@forEach
        }
        if (normalizedRequestId in activeRequestIds) {
            return@forEach
        }

        requestDir.deleteRecursively()
    }
}

internal data class CompanionAuthEphemeralKeyPair(
    val publicKeyBase64: String,
    val privateKeyBase64: String,
)

internal data class CompanionAuthPendingSession(
    val requestId: String,
    val provider: CompanionAuthProvider,
    val role: Role,
    val localNodeId: String,
    val expectedNodeId: String,
    val protocolVersion: Int,
    val createdAtEpochMillis: Long,
    val localPrivateKeyBase64: String,
    val localPublicKeyBase64: String,
    val remotePublicKeyBase64: String? = null,
    val launchConsumed: Boolean = false,
) {
    enum class Role {
        Initiator,
        Receiver,
    }

    fun isExpired(
        now: Long = System.currentTimeMillis(),
    ): Boolean = now - createdAtEpochMillis > CompanionAuthProtocol.SESSION_TIMEOUT_MS

    fun isLaunchExpired(
        now: Long = System.currentTimeMillis(),
    ): Boolean = role == Role.Receiver &&
        !launchConsumed &&
        now - createdAtEpochMillis > CompanionAuthProtocol.LAUNCH_TIMEOUT_MS
}

private fun ECParameterSpec.hasSameParameters(
    other: ECParameterSpec,
): Boolean = curve == other.curve &&
    generator == other.generator &&
    order == other.order &&
    cofactor == other.cofactor

private fun ECPoint.isValidPoint(
    params: ECParameterSpec,
): Boolean {
    if (this == ECPoint.POINT_INFINITY) {
        return false
    }
    val field = params.curve.field as? ECFieldFp
        ?: return false
    val p = field.p
    val x = affineX
        ?: return false
    val y = affineY
        ?: return false
    if (x.signum() < 0 || x >= p || y.signum() < 0 || y >= p) {
        return false
    }

    val left = y.modPow(BIG_INTEGER_TWO, p)
    val right = x.modPow(BIG_INTEGER_THREE, p)
        .add(params.curve.a.mod(p).multiply(x))
        .add(params.curve.b.mod(p))
        .mod(p)
    return left == right
}

private val BIG_INTEGER_TWO = BigInteger.valueOf(2L)
private val BIG_INTEGER_THREE = BigInteger.valueOf(3L)
private const val COMPANION_AUTH_MAX_PUBLIC_KEY_BASE64_CHARS = 1024
private const val COMPANION_AUTH_MAX_PUBLIC_KEY_BYTES = 512
