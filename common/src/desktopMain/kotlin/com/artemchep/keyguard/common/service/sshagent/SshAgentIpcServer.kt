package com.artemchep.keyguard.common.service.sshagent

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.model.SshAgentFilter
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetSshAgentFilter
import com.artemchep.keyguard.common.usecase.GetVaultSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.RSAPrivateCrtKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil
import org.bouncycastle.util.encoders.Base64
import org.kodein.di.direct
import org.kodein.di.instance
import java.io.EOFException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousCloseException
import java.nio.channels.ClosedChannelException
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.security.Signature as JcaSignature

/**
 * IPC server that listens for connections from the keyguard-ssh-agent
 * binary and serves SSH key data from the Keyguard vault.
 *
 * The server uses Unix domain sockets (macOS/Linux) and communicates
 * via length-prefixed protobuf messages.
 *
 * The server handles:
 * - Authentication via shared token
 * - Key listing (returning SSH keys from the vault)
 * - Sign requests (delegating to the vault's private keys)
 */
@OptIn(ExperimentalSerializationApi::class)
class SshAgentIpcServer(
    private val logRepository: LogRepository,
    private val getVaultSession: GetVaultSession,
    private val getSshAgentFilter: GetSshAgentFilter,
    private val authToken: ByteArray,
    private val scope: CoroutineScope,
    private val maxConcurrentConnections: Int = 8,
    /**
     * Called before signing to request user approval.
     *
     * The callback receives the key name and fingerprint, and must
     * return `true` to allow signing or `false` to deny. The IPC
     * server will return [SshAgentMessages.ErrorCode.USER_DENIED]
     * when this returns `false`.
     */
    private val onApprovalRequest: suspend (caller: SshAgentMessages.CallerIdentity?, keyName: String, keyFingerprint: String) -> Boolean =
        { _, _, _ -> true },
    /**
     * Called when the vault is locked and a list-keys request
     * needs vault access.
     *
     * The callback should prompt the user to unlock the vault and
     * return `true` if the vault was successfully unlocked, or
     * `false` if the user dismissed the prompt or it timed out.
     */
    private val onGetListRequest: suspend (caller: SshAgentMessages.CallerIdentity?) -> Boolean = { _ -> false },
) {
    companion object {
        private const val TAG = "SshAgentIpcServer"

        /** Timeout for user approval prompts in milliseconds. */
        const val APPROVAL_TIMEOUT_MS = 60_000L
    }

    private val protoBuf = ProtoBuf

    private val sshAgentFilterState = getSshAgentFilter()
        .stateIn(scope, SharingStarted.Eagerly, SshAgentFilter())

    private val serverChannelLock = Any()
    private var serverChannelRef: ServerSocketChannel? = null

    internal fun stop() {
        val channel = synchronized(serverChannelLock) {
            serverChannelRef
        }
        try {
            channel?.close()
        } catch (_: Exception) {
        }
    }

    /**
     * Starts the IPC server on the given Unix domain socket path.
     *
     * This method blocks (suspends) until the server is stopped or an error occurs.
     *
     * @param socketPath The path to the Unix domain socket.
     * @param onReady An optional [CompletableDeferred] that will be completed once the server
     *   has bound the socket and is ready to accept connections. This allows callers to
     *   reliably wait for the server to be ready before spawning the SSH agent binary.
     */
    suspend fun start(
        socketPath: Path,
        onReady: CompletableDeferred<Unit>? = null,
    ) {
        val osName = System.getProperty("os.name")
        if (osName.startsWith("Windows", ignoreCase = true)) {
            val msg = "SSH agent IPC server requires Unix domain sockets; " +
                    "Windows IPC is not implemented yet."
            throw UnsupportedOperationException(msg)
        }

        // Clean up stale socket file.
        Files.deleteIfExists(socketPath)

        // Ensure parent directory exists.
        socketPath.parent?.let { Files.createDirectories(it) }

        val address = UnixDomainSocketAddress.of(socketPath)
        val serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        synchronized(serverChannelLock) {
            serverChannelRef = serverChannel
        }
        serverChannel.bind(address)
        val cancellationHandler = currentCoroutineContext()[Job]
            ?.invokeOnCompletion { stop() }

        // Restrict socket permissions to owner-only (0600) to prevent
        // other local users from connecting to the IPC socket.
        try {
            Files.setPosixFilePermissions(
                socketPath,
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                ),
            )
        } catch (_: UnsupportedOperationException) {
            // Non-POSIX filesystem (e.g. Windows) — skip.
            // Windows does not use Unix domain socket file permissions
            // for access control.
        }

        // Signal that the server is ready to accept connections.
        onReady?.complete(Unit)

        val connectionSemaphore = Semaphore(maxConcurrentConnections)

        try {
            logRepository.post(TAG, "IPC server listening on $socketPath", LogLevel.INFO)

            while (scope.isActive && currentCoroutineContext().isActive) {
                val clientChannel = try {
                    withContext(Dispatchers.IO) {
                        serverChannel.accept()
                    }
                } catch (_: AsynchronousCloseException) {
                    break
                } catch (_: ClosedChannelException) {
                    break
                }
                if (!connectionSemaphore.tryAcquire()) {
                    val errorMessage = "Connection rejected: too many concurrent IPC connections " +
                            "(limit=$maxConcurrentConnections)"
                    logRepository.post(TAG, errorMessage, LogLevel.ERROR)

                    try {
                        clientChannel.close()
                    } catch (_: Exception) {
                    }
                    continue
                }
                // Handle each connection in a separate coroutine.
                scope.launch(Dispatchers.IO) {
                    try {
                        handleConnection(clientChannel)
                    } finally {
                        connectionSemaphore.release()
                    }
                }
            }
        } finally {
            cancellationHandler?.dispose()
            stop()
            Files.deleteIfExists(socketPath)
            synchronized(serverChannelLock) {
                serverChannelRef = null
            }
        }
    }

    /**
     * Handles a single client connection from the Rust SSH agent.
     */
    private suspend fun handleConnection(channel: SocketChannel) {
        var authenticated = false
        try {
            while (true) {
                val request = readMessage(channel) ?: break
                val response = processRequest(request, authenticated)

                writeMessage(channel, response)

                // Track authentication state. Close the connection immediately
                // after a failed auth attempt (defense-in-depth) to prevent
                // brute-force attempts on the same connection.
                if (request.authenticate != null) {
                    if (response.authenticate?.success == true) {
                        authenticated = true
                    } else {
                        break
                    }
                }
            }
        } catch (_: AsynchronousCloseException) {
            // Normal during server shutdown.
        } catch (_: ClosedChannelException) {
            // Normal during server shutdown.
        } catch (_: EOFException) {
            logRepository.post(TAG, "Client disconnected", LogLevel.INFO)
        } catch (e: Exception) {
            if (e !is CancellationException) {
                val errorMessage = "Error handling IPC connection: ${e.message}"
                logRepository.post(TAG, errorMessage, LogLevel.ERROR)
            }
        } finally {
            try {
                channel.close()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Processes a single IPC request and returns the corresponding response.
     */
    internal suspend fun processRequest(
        request: SshAgentMessages.IpcRequest,
        authenticated: Boolean,
    ): SshAgentMessages.IpcResponse {
        val requestVariantCount =
            (if (request.authenticate != null) 1 else 0) +
                    (if (request.listKeys != null) 1 else 0) +
                    (if (request.signData != null) 1 else 0)
        if (requestVariantCount > 1) {
            return SshAgentMessages.IpcResponse(
                id = request.id,
                error = SshAgentMessages.ErrorResponse(
                    message = "Malformed request: multiple request variants set",
                    code = SshAgentMessages.ErrorCode.UNSPECIFIED,
                ),
            )
        }

        // Authentication must happen first.
        if (!authenticated && request.authenticate == null) {
            return SshAgentMessages.IpcResponse(
                id = request.id,
                error = SshAgentMessages.ErrorResponse(
                    message = "Not authenticated. Send AuthenticateRequest first.",
                    code = SshAgentMessages.ErrorCode.NOT_AUTHENTICATED,
                ),
            )
        }

        return when {
            request.authenticate != null -> handleAuthenticate(request.id, request.authenticate)
            request.listKeys != null -> handleListKeys(request.id, request.listKeys)
            request.signData != null -> handleSignData(request.id, request.signData)
            else -> SshAgentMessages.IpcResponse(
                id = request.id,
                error = SshAgentMessages.ErrorResponse(
                    message = "Unknown request type",
                    code = SshAgentMessages.ErrorCode.UNSPECIFIED,
                ),
            )
        }
    }

    /**
     * Handles the authentication handshake.
     */
    internal fun handleAuthenticate(
        requestId: Long,
        req: SshAgentMessages.AuthenticateRequest,
    ): SshAgentMessages.IpcResponse {
        val success = MessageDigest.isEqual(authToken, req.token)
        if (!success) {
            val errorMessage = "Authentication failed: token mismatch"
            logRepository.post(TAG, errorMessage, LogLevel.ERROR)
        } else {
            val successMessage = "Authentication successful"
            logRepository.post(TAG, successMessage, LogLevel.INFO)
        }
        return SshAgentMessages.IpcResponse(
            id = requestId,
            authenticate = SshAgentMessages.AuthenticateResponse(success = success),
        )
    }

    /**
     * Handles a request to list available SSH keys from the vault.
     */
    internal suspend fun handleListKeys(
        requestId: Long,
        req: SshAgentMessages.ListKeysRequest,
    ): SshAgentMessages.IpcResponse {
        val sshKeys = getSshKeysFromVaultOrRequestGetList(req.caller)
            ?: return SshAgentMessages.IpcResponse(
                id = requestId,
                error = SshAgentMessages.ErrorResponse(
                    message = "Vault is locked",
                    code = SshAgentMessages.ErrorCode.VAULT_LOCKED,
                ),
            )

        val keys = sshKeys.mapNotNull { secret ->
            val sshKey = secret.sshKey
                ?: return@mapNotNull null
            val publicKey = sshKey.publicKey
                ?: return@mapNotNull null
            val keyType = extractKeyType(publicKey) ?: "unknown"
            SshAgentMessages.SshKey(
                name = secret.name,
                publicKey = publicKey,
                keyType = keyType,
                fingerprint = sshKey.fingerprint.orEmpty(),
            )
        }

        return SshAgentMessages.IpcResponse(
            id = requestId,
            listKeys = SshAgentMessages.ListKeysResponse(keys = keys),
        )
    }

    /**
     * Handles a request to sign data with a specific SSH key.
     *
     * This retrieves the private key from the vault, prompts the user
     * for approval, performs the signing operation, and returns the
     * signature.
     */
    internal suspend fun handleSignData(
        requestId: Long,
        req: SshAgentMessages.SignDataRequest,
    ): SshAgentMessages.IpcResponse {
        var sshKeys = getSshKeysFromVault()

        val wasVaultLocked = sshKeys == null
        if (wasVaultLocked) {
            // For sign requests, approval handles the unlock flow while the
            // vault is locked. If denied/cancelled/timed out, return USER_DENIED.
            val approved = requestSigningApproval(
                keyName = "SSH key",
                keyFingerprint = "",
                caller = req.caller,
            )
            if (!approved) {
                logRepository.post(TAG, "User denied signing request while vault was locked", LogLevel.INFO)
                return SshAgentMessages.IpcResponse(
                    id = requestId,
                    error = SshAgentMessages.ErrorResponse(
                        message = "User denied the signing request",
                        code = SshAgentMessages.ErrorCode.USER_DENIED,
                    ),
                )
            }

            // The approval flow should have handled unlock. Retry vault access.
            sshKeys = getSshKeysFromVault()
            if (sshKeys == null) {
                return SshAgentMessages.IpcResponse(
                    id = requestId,
                    error = SshAgentMessages.ErrorResponse(
                        message = "Vault is locked",
                        code = SshAgentMessages.ErrorCode.VAULT_LOCKED,
                    ),
                )
            }
        }

        val availableSshKeys = sshKeys
            ?: return SshAgentMessages.IpcResponse(
                id = requestId,
                error = SshAgentMessages.ErrorResponse(
                    message = "Vault is locked",
                    code = SshAgentMessages.ErrorCode.VAULT_LOCKED,
                ),
            )

        // Find the key that matches the requested public key.
        val matchingSecret = availableSshKeys.find { secret ->
            val publicKey = secret.sshKey?.publicKey ?: return@find false
            publicKey == req.publicKey
        }

        if (matchingSecret == null) {
            return SshAgentMessages.IpcResponse(
                id = requestId,
                error = SshAgentMessages.ErrorResponse(
                    message = "Key not found for public key: ${req.publicKey.take(40)}...",
                    code = SshAgentMessages.ErrorCode.KEY_NOT_FOUND,
                ),
            )
        }

        val privateKeyPem = matchingSecret.sshKey?.privateKey
        if (privateKeyPem.isNullOrBlank()) {
            return SshAgentMessages.IpcResponse(
                id = requestId,
                error = SshAgentMessages.ErrorResponse(
                    message = "Private key not available for: ${matchingSecret.name}",
                    code = SshAgentMessages.ErrorCode.KEY_NOT_FOUND,
                ),
            )
        }

        val sshKey = matchingSecret.sshKey!!

        if (!wasVaultLocked) {
            // Vault was already unlocked, so ask for approval using exact key metadata.
            val approved = requestSigningApproval(
                keyName = matchingSecret.name,
                keyFingerprint = sshKey.fingerprint ?: "",
                caller = req.caller,
            )
            if (!approved) {
                logRepository.post(TAG, "User denied signing request for: ${matchingSecret.name}", LogLevel.INFO)
                return SshAgentMessages.IpcResponse(
                    id = requestId,
                    error = SshAgentMessages.ErrorResponse(
                        message = "User denied the signing request",
                        code = SshAgentMessages.ErrorCode.USER_DENIED,
                    ),
                )
            }
        }

        // Sign the data using the private key.
        return try {
            val result = signWithPrivateKey(privateKeyPem, req.data, req.flags)
            SshAgentMessages.IpcResponse(
                id = requestId,
                signData = SshAgentMessages.SignDataResponse(
                    signature = result.signature,
                    algorithm = result.algorithm,
                ),
            )
        } catch (e: Exception) {
            logRepository.post(TAG, "Signing failed: ${e.message}", LogLevel.ERROR)
            SshAgentMessages.IpcResponse(
                id = requestId,
                error = SshAgentMessages.ErrorResponse(
                    message = "Signing failed: ${e.message}",
                    code = SshAgentMessages.ErrorCode.UNSPECIFIED,
                ),
            )
        }
    }

    // ================================================================
    // Vault access
    // ================================================================

    /**
     * Retrieves all SSH key secrets from the currently unlocked vault.
     * Returns null if the vault is locked.
     */
    private suspend fun getSshKeysFromVault(): List<DSecret>? {
        val session = getVaultSession.valueOrNull
        val key = session as? MasterSession.Key ?: return null

        val getCiphers = key.di.direct.instance<GetCiphers>()
        val sshKeys = getCiphers()
            .map { ciphers ->
                ciphers.filter { it.type == DSecret.Type.SshKey }
            }
            .first()

        val sshAgentFilter = sshAgentFilterState.value.normalize()
        if (!sshAgentFilter.isActive) {
            return sshKeys
        }

        val predicate = sshAgentFilter.toDFilter().prepare(
            directDI = key.di.direct,
            ciphers = sshKeys,
        )
        return sshKeys.filter(predicate)
    }

    /**
     * Retrieves SSH keys from the vault, prompting the user to unlock
     * if the vault is currently locked.
     *
     * If the vault is locked, this calls [onGetListRequest] which shows
     * a UI prompt for the user to unlock. If the user successfully
     * unlocks, retrieves and returns the SSH keys. Otherwise returns null.
     */
    private suspend fun getSshKeysFromVaultOrRequestGetList(
        caller: SshAgentMessages.CallerIdentity?,
    ): List<DSecret>? {
        // Fast path: vault is already unlocked.
        getSshKeysFromVault()?.let { return it }

        // Vault is locked — ask the user to unlock.
        logRepository.post(TAG, "Vault is locked, requesting list-keys unlock from user", LogLevel.INFO)
        val unlocked = try {
            onGetListRequest(caller)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logRepository.post(TAG, "Unlock request failed: ${e.message}", LogLevel.ERROR)
            false
        }

        if (!unlocked) {
            logRepository.post(TAG, "User did not unlock the vault", LogLevel.INFO)
            return null
        }

        // Vault should now be unlocked — retry.
        logRepository.post(TAG, "Vault unlocked, retrying key retrieval", LogLevel.INFO)
        return getSshKeysFromVault()
    }

    private suspend fun requestSigningApproval(
        keyName: String,
        keyFingerprint: String,
        caller: SshAgentMessages.CallerIdentity?,
    ): Boolean = try {
        withTimeoutOrNull(APPROVAL_TIMEOUT_MS) {
            onApprovalRequest(
                caller,
                keyName,
                keyFingerprint,
            )
        } ?: false
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        logRepository.post(TAG, "Approval request failed: ${e.message}", LogLevel.ERROR)
        false
    }

    // ================================================================
    // Cryptographic signing
    // ================================================================

    /**
     * Result of a signing operation: raw signature bytes and the algorithm name.
     */
    internal data class SignResult(
        val signature: ByteArray,
        val algorithm: String,
    )

    /**
     * Signs data using an OpenSSH private key (PEM format).
     *
     * Parses the PEM key using BouncyCastle's OpenSSHPrivateKeyUtil
     * (matching the pattern from KeyPairGeneratorJvm) and performs the
     * signature operation.
     */
    internal fun signWithPrivateKey(
        privateKeyPem: String,
        data: ByteArray,
        flags: Int,
    ): SignResult {
        // Parse the OpenSSH private key PEM.
        // Strip the PEM header/footer and base64-decode, matching
        // the approach in KeyPairGeneratorJvm.performParse().
        val encodedPrivateKey = privateKeyPem
            .replace("-{1,5}(BEGIN|END) (|RSA |OPENSSH )PRIVATE KEY-{1,5}".toRegex(), "")
            .lineSequence()
            .map { it.trim() }
            .joinToString(separator = "")
            .let { Base64.decode(it) }

        val parsedKey = OpenSSHPrivateKeyUtil.parsePrivateKeyBlob(encodedPrivateKey)

        return when (parsedKey) {
            is Ed25519PrivateKeyParameters -> {
                signEd25519(parsedKey, data)
            }

            is RSAPrivateCrtKeyParameters -> {
                signRsa(parsedKey, data, flags)
            }

            else -> throw IllegalArgumentException(
                "Unsupported key type: ${parsedKey::class.simpleName}",
            )
        }
    }

    /**
     * Signs data with an Ed25519 key.
     * Returns the raw 64-byte Ed25519 signature and algorithm name.
     */
    internal fun signEd25519(
        privateKey: Ed25519PrivateKeyParameters,
        data: ByteArray,
    ): SignResult {
        val signer = Ed25519Signer()
        signer.init(true, privateKey)
        signer.update(data, 0, data.size)
        val rawSignature = signer.generateSignature()
        return SignResult(
            signature = rawSignature,
            algorithm = "ssh-ed25519",
        )
    }

    /**
     * Signs data with an RSA key using PKCS#1 v1.5 signature scheme.
     * Returns the raw RSA signature and the algorithm name determined
     * by the SSH agent protocol flags.
     */
    internal fun signRsa(
        privateKey: RSAPrivateCrtKeyParameters,
        data: ByteArray,
        flags: Int,
    ): SignResult {
        // Determine the hash algorithm based on SSH agent flags.
        // Flag 0x04 = rsa-sha2-512, 0x02 = rsa-sha2-256, default = ssh-rsa (SHA-1).
        val (algorithm, jcaAlgorithm) = when {
            flags and 0x04 != 0 -> "rsa-sha2-512" to "SHA512withRSA"
            flags and 0x02 != 0 -> "rsa-sha2-256" to "SHA256withRSA"
            else -> "ssh-rsa" to "SHA1withRSA"
        }

        // Convert BouncyCastle RSA key to JCA for standard PKCS#1 v1.5 signing.
        val keyFactory = java.security.KeyFactory.getInstance("RSA")
        val jcaPrivateKey = keyFactory.generatePrivate(
            java.security.spec.RSAPrivateCrtKeySpec(
                privateKey.modulus,
                privateKey.publicExponent,
                privateKey.exponent,
                privateKey.p,
                privateKey.q,
                privateKey.dp,
                privateKey.dq,
                privateKey.qInv,
            ),
        )

        val signer = JcaSignature.getInstance(jcaAlgorithm)
        signer.initSign(jcaPrivateKey)
        signer.update(data)
        val rawSignature = signer.sign()

        return SignResult(
            signature = rawSignature,
            algorithm = algorithm,
        )
    }

    // ================================================================
    // Protobuf I/O helpers
    // ================================================================

    /**
     * Reads a length-prefixed protobuf message from the channel.
     * Returns null if the channel is closed.
     */
    private fun readMessage(channel: SocketChannel): SshAgentMessages.IpcRequest? {
        // Read 4-byte big-endian length prefix.
        val lenBuf = ByteBuffer.allocate(4)
        val bytesRead = readFully(channel, lenBuf)
        if (bytesRead < 4) return null

        lenBuf.flip()
        val len = lenBuf.int
        if (len <= 0 || len > 16 * 1024 * 1024) {
            throw IllegalArgumentException("Invalid message length: $len")
        }

        // Read message body.
        val msgBuf = ByteBuffer.allocate(len)
        readFully(channel, msgBuf)
        msgBuf.flip()

        val bytes = ByteArray(len)
        msgBuf.get(bytes)

        return protoBuf.decodeFromByteArray<SshAgentMessages.IpcRequest>(bytes)
    }

    /**
     * Writes a length-prefixed protobuf message to the channel.
     */
    private fun writeMessage(channel: SocketChannel, response: SshAgentMessages.IpcResponse) {
        val bytes = protoBuf.encodeToByteArray(response)
        val buf = ByteBuffer.allocate(4 + bytes.size)
        buf.putInt(bytes.size)
        buf.put(bytes)
        buf.flip()
        while (buf.hasRemaining()) {
            channel.write(buf)
        }
    }

    /**
     * Reads exactly the remaining capacity of the buffer from the channel.
     * Returns the number of bytes read.
     */
    private fun readFully(channel: SocketChannel, buf: ByteBuffer): Int {
        var totalRead = 0
        while (buf.hasRemaining()) {
            val n = channel.read(buf)
            if (n < 0) {
                if (totalRead == 0) return -1
                throw EOFException("Unexpected end of stream")
            }
            totalRead += n
        }
        return totalRead
    }

    // ================================================================
    // Utilities
    // ================================================================

    /**
     * Extracts the key type from an OpenSSH public key string.
     * E.g., "ssh-ed25519 AAAA..." -> "ssh-ed25519"
     */
    internal fun extractKeyType(publicKey: String): String? {
        return publicKey.trim().split(' ').firstOrNull()
    }
}
