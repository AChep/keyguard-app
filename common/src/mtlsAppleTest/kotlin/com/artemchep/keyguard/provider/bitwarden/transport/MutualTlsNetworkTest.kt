@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.artemchep.keyguard.provider.bitwarden.transport

import com.artemchep.keyguard.common.service.http.KeyguardHttpUserAgent
import com.artemchep.keyguard.common.service.http.configureKeyguardHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.ChallengeHandler
import io.ktor.client.engine.darwin.Darwin
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import platform.CoreFoundation.CFArrayAppendValue
import platform.CoreFoundation.CFArrayCreateMutable
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFRelease
import platform.Foundation.NSURLAuthenticationMethodServerTrust
import platform.Foundation.NSURLCredential
import platform.Foundation.NSURLSessionAuthChallengeCancelAuthenticationChallenge
import platform.Foundation.NSURLSessionAuthChallengePerformDefaultHandling
import platform.Foundation.NSURLSessionAuthChallengeUseCredential
import platform.Foundation.credentialForTrust
import platform.Foundation.serverTrust
import platform.Security.SecCertificateCreateWithData
import platform.Security.SecTrustEvaluateWithError
import platform.Security.SecTrustSetAnchorCertificates
import platform.Security.SecTrustSetAnchorCertificatesOnly
import platform.Security.errSecSuccess
import platform.posix.getenv
import kotlin.test.Test

class MutualTlsNetworkTest {
    @Test
    fun handshakesRedirectsAndWebSocketsUseOnlyConfiguredIdentities() = runBlocking {
        HttpClient(Darwin).use { bootstrap ->
            verifyMutualTlsTransport(getenv("KEYGUARD_MTLS_PORT")?.toKString(), bootstrap) { ca ->
                val trust = fixtureTrust(ca)
                val base = HttpClient(Darwin) {
                    engine { handleChallenge(trust) }
                    configureKeyguardHttpClient(Json, KeyguardHttpUserAgent.Bitwarden)
                }
                base to BitwardenHttpClientFactoryApple(Json, trust)
            }
        }
    }
}

/** Trust only the disposable fixture CA, preserving the TLS hostname and validity policies. */
private fun fixtureTrust(ca: ByteArray): ChallengeHandler = { _, _, challenge, complete ->
    val protection = challenge.protectionSpace
    val trust = protection.serverTrust
    if (protection.authenticationMethod != NSURLAuthenticationMethodServerTrust || trust == null) {
        complete(NSURLSessionAuthChallengePerformDefaultHandling, null)
    } else {
        val data = requireNotNull(ca.usePinned {
            CFDataCreate(null, it.addressOf(0).reinterpret(), ca.size.convert())
        })
        val certificate = try {
            requireNotNull(SecCertificateCreateWithData(null, data))
        } finally {
            CFRelease(data)
        }
        try {
            val anchors = requireNotNull(CFArrayCreateMutable(null, 0, null))
            try {
                CFArrayAppendValue(anchors, certificate)
                check(SecTrustSetAnchorCertificates(trust, anchors) == errSecSuccess)
                check(SecTrustSetAnchorCertificatesOnly(trust, true) == errSecSuccess)
                if (SecTrustEvaluateWithError(trust, null)) {
                    complete(NSURLSessionAuthChallengeUseCredential, NSURLCredential.credentialForTrust(trust))
                } else {
                    complete(NSURLSessionAuthChallengeCancelAuthenticationChallenge, null)
                }
            } finally {
                CFRelease(anchors)
            }
        } finally {
            CFRelease(certificate)
        }
    }
}
