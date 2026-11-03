@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.artemchep.keyguard.provider.bitwarden.transport

import com.artemchep.keyguard.nativecrypto.NativeCryptoPkcs12
import com.artemchep.keyguard.nativecrypto.NativeCryptoPrimitives
import com.artemchep.keyguard.nativecrypto.NativePkcs12Result
import com.artemchep.keyguard.provider.bitwarden.ServerEnv
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.engine.darwin.ChallengeHandler
import kotlin.io.encoding.Base64
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.serialization.json.Json
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFRelease
import platform.Foundation.NSURLAuthenticationMethodClientCertificate
import platform.Foundation.NSURLCredential
import platform.Foundation.NSURLSessionAuthChallengeCancelAuthenticationChallenge
import platform.Foundation.NSURLSessionAuthChallengePerformDefaultHandling
import platform.Foundation.NSURLSessionAuthChallengeUseCredential

class BitwardenHttpClientFactoryApple internal constructor(
    private val json: Json,
    private val defaultChallengeHandler: ChallengeHandler,
) : BitwardenHttpClientFactory {
    constructor(json: Json) : this(json, { _, _, _, complete ->
        complete(NSURLSessionAuthChallengePerformDefaultHandling, null)
    })

    override fun create(env: ServerEnv, origins: Set<String>): HttpClient {
        val identity = requireNotNull(env.mutualTls as? ServerEnv.MutualTls.ImportedPkcs12)
        val credential = createMutualTlsCredential(identity.pkcs12Base64)
        return HttpClient(Darwin) {
            engine {
                configureSession {
                    setURLCache(null)
                    setURLCredentialStorage(null)
                }
                handleChallenge { session, task, challenge, complete ->
                    val protection = challenge.protectionSpace
                    if (protection.authenticationMethod != NSURLAuthenticationMethodClientCertificate) {
                        defaultChallengeHandler(session, task, challenge, complete)
                    } else if (challenge.previousFailureCount == 0L &&
                        mutualTlsOrigin(protection.host, protection.port.toInt()) in origins
                    ) {
                        complete(NSURLSessionAuthChallengeUseCredential, credential)
                    } else {
                        complete(NSURLSessionAuthChallengeCancelAuthenticationChallenge, null)
                    }
                }
            }
            configureBitwardenMutualTlsClient(json, origins)
        }
    }
}

/** Rewrap only in memory: Apple importers require a nonempty password. */
internal fun createMutualTlsCredential(pkcs12Base64: String): NSURLCredential {
    val bytes = Base64.Default.decode(pkcs12Base64)
    val random = NativeCryptoPrimitives.randomBytes(32)
    val password = try { Base64.Default.encode(random) } finally { random.fill(0) }
    val result = try {
        NativeCryptoPkcs12.process(bytes, outputPassword = password, exportPrivateKey = true)
    } finally {
        bytes.fill(0)
    }
    require(result is NativePkcs12Result.Success) { "Could not read the client certificate." }
    val bundle = result.identity.bundle
    return try {
        val data = bundle.usePinned { CFDataCreate(null, it.addressOf(0).reinterpret(), bundle.size.convert()) }
        requireNotNull(data)
        try { importMutualTlsCredential(data, password, result.identity.privateKeyDer) } finally { CFRelease(data) }
    } finally {
        bundle.fill(0)
        result.identity.privateKeyDer.fill(0)
    }
}

/** The returned credential retains the imported identity; no keychain entry is created. */
internal expect fun importMutualTlsCredential(
    data: CFDataRef,
    password: String,
    privateKeyDer: ByteArray,
): NSURLCredential
