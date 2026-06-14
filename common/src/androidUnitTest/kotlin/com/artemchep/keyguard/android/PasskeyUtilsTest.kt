package com.artemchep.keyguard.android

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.Argon2Mode
import com.artemchep.keyguard.common.model.CryptoHashAlgorithm
import com.artemchep.keyguard.common.model.DPrivilegedApp
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.gpmprivapps.PrivilegedAppsService
import com.artemchep.keyguard.common.service.passkey.entity.CreatePasskeyAttestation
import com.artemchep.keyguard.common.service.tld.TldService
import com.artemchep.keyguard.common.service.webauthn.authDataFlags
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.KotlinxSerializationConverter
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.security.MessageDigest
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * WebAuthn Level 3 coverage for the local authenticator helpers:
 * authenticator data encoding, user-verification flags, RP ID scoping, and
 * related-origin validation.
 */
class PasskeyUtilsTest {
    // Spec coverage: WebAuthn L3 Section 6.1 Authenticator Data defines the
    // UP/UV/BE/BS/AT/ED flag positions, with bit 0 as the least significant bit.
    @Test
    fun `authenticator data flags use WebAuthn bits`() {
        assertEquals(
            0x01,
            flags(userPresence = true),
        )
        assertEquals(
            0x04,
            flags(userVerification = true),
        )
        assertEquals(
            0x08,
            flags(backupEligibility = true),
        )
        assertEquals(
            0x10,
            flags(backupState = true),
        )
        assertEquals(
            0x40,
            flags(attestationData = true),
        )
        assertEquals(
            0x80,
            flags(extensionData = true),
        )
    }

    // Spec coverage: Section 6.1 packs authenticator-data flags into one byte,
    // so independent AT and ED bits must combine without shifting each other.
    @Test
    fun `authenticator data flags combine attestation and extension bits`() {
        assertEquals(
            0xc0,
            flags(
                extensionData = true,
                attestationData = true,
            ),
        )
    }

    // Spec coverage: Section 6.5.1 Attested Credential Data caps credential ID
    // length at 1023 bytes; Section 7.1 repeats that RP verification limit.
    @Test
    fun `authenticator data rejects credential id above WebAuthn maximum`() {
        val passkeyUtils = createPasskeyUtils()

        val error = assertFailsWith<IllegalArgumentException> {
            passkeyUtils.authData(
                rpId = "example.com",
                counter = 0,
                credentialId = ByteArray(1024),
                credentialPublicKey = byteArrayOf(0xa0.toByte()),
                userVerification = true,
                userPresence = true,
            )
        }

        assertEquals(
            "WebAuthn credential ID must be at most 1023 bytes.",
            error.message,
        )
    }

    // Spec coverage: Section 8.7 None Attestation replaces the attestation
    // statement with fmt "none" and an empty attStmt; Section 6.5.1 still keeps
    // the authenticator AAGUID inside attestedCredentialData.
    @Test
    fun `none and omitted attestation preserve authenticator aaguid`() {
        val passkeyUtils = createPasskeyUtils()

        val directAaguid = aaguidFor(
            passkeyUtils = passkeyUtils,
            attestation = CreatePasskeyAttestation.DIRECT,
        )
        val noneAaguid = aaguidFor(
            passkeyUtils = passkeyUtils,
            attestation = CreatePasskeyAttestation.NONE,
        )
        val omittedAaguid = aaguidFor(
            passkeyUtils = passkeyUtils,
            attestation = null,
        )

        assertFalse(directAaguid.all { it == 0.toByte() })
        assertContentEquals(directAaguid, noneAaguid)
        assertContentEquals(directAaguid, omittedAaguid)
    }

    @Test
    fun `indirect attestation derives anonymized aaguid from direct aaguid and credential id`() {
        val passkeyUtils = createPasskeyUtils()
        val credentialId = byteArrayOf(0x10, 0x20, 0x30, 0x40)

        val directAaguid = aaguidFor(
            passkeyUtils = passkeyUtils,
            attestation = CreatePasskeyAttestation.DIRECT,
            credentialId = credentialId,
        )
        val indirectAaguid = aaguidFor(
            passkeyUtils = passkeyUtils,
            attestation = CreatePasskeyAttestation.INDIRECT,
            credentialId = credentialId,
        )
        val expectedIndirectAaguid = MessageDigest
            .getInstance("MD5")
            .digest(directAaguid + credentialId)

        assertContentEquals(expectedIndirectAaguid, indirectAaguid)
        assertFalse(directAaguid.contentEquals(indirectAaguid))
    }

    // Spec coverage: Section 5.8.6 defines required/preferred/discouraged user
    // verification, and Section 6.1 defines the authenticator-data UV flag as
    // the bit reporting whether user verification was performed.
    @Test
    fun `user verified flag follows actual verification result for required mode`() {
        val passkeyUtils = createPasskeyUtils()

        assertEquals(
            false,
            passkeyUtils.userVerification(
                mode = "required",
                userVerified = false,
            ),
        )
        assertEquals(
            true,
            passkeyUtils.userVerification(
                mode = "required",
                userVerified = true,
            ),
        )
    }

    @Test
    fun `user verified flag follows actual verification result for preferred mode`() {
        val passkeyUtils = createPasskeyUtils()

        assertEquals(
            false,
            passkeyUtils.userVerification(
                mode = "preferred",
                userVerified = false,
            ),
        )
        assertEquals(
            true,
            passkeyUtils.userVerification(
                mode = "preferred",
                userVerified = true,
            ),
        )
    }

    @Test
    fun `user verified flag defaults omitted mode to preferred`() {
        val passkeyUtils = createPasskeyUtils()

        assertEquals(
            false,
            passkeyUtils.userVerification(
                mode = null,
                userVerified = false,
            ),
        )
        assertEquals(
            true,
            passkeyUtils.userVerification(
                mode = null,
                userVerified = true,
            ),
        )
    }

    @Test
    fun `user verified flag is suppressed for discouraged mode`() {
        val passkeyUtils = createPasskeyUtils()

        assertEquals(
            false,
            passkeyUtils.userVerification(
                mode = "discouraged",
                userVerified = true,
            ),
        )
    }

    // Spec coverage: Sections 5.1.3 and 5.1.4.1 establish rp.id/rpId from the
    // caller effective domain, and Section 5.11 allows related origins only
    // after the direct equal-or-suffix RP ID check fails.
    @Test
    fun `https rp validation allows exact effective domain`() = runTest {
        var requestCount = 0
        val passkeyUtils = createPasskeyUtils {
            requestCount++
        }

        passkeyUtils.requireRpMatchesOrigin(
            rpId = "login.example.com",
            origin = "https://login.example.com",
            packageName = "com.example.app",
        )

        assertEquals(0, requestCount)
    }

    @Test
    fun `https rp validation allows same registrable domain suffix`() = runTest {
        var requestCount = 0
        val passkeyUtils = createPasskeyUtils {
            requestCount++
        }

        passkeyUtils.requireRpMatchesOrigin(
            rpId = "example.com",
            origin = "https://login.example.com",
            packageName = "com.example.app",
        )

        assertEquals(0, requestCount)
    }

    // Spec coverage: HTML's registrable-domain-suffix algorithm rejects suffix
    // matches that cross a public suffix boundary; its examples call out
    // `*.compute.amazonaws.com` directly. WebAuthn L3 then falls through to
    // related-origin validation instead of accepting the RP ID directly.
    @Test
    fun `https rp validation falls through across wildcard public suffix boundary`() = runTest {
        var requestedUrl: String? = null
        val passkeyUtils = createPasskeyUtils { url ->
            requestedUrl = url
        }

        assertFailsWith<IllegalStateException> {
            passkeyUtils.requireRpMatchesOrigin(
                rpId = "compute.amazonaws.com",
                origin = "https://www.example.compute.amazonaws.com",
                packageName = "com.example.app",
            )
        }

        assertEquals(
            "https://compute.amazonaws.com/.well-known/webauthn",
            requestedUrl,
        )
    }

    @Test
    fun `https rp validation allows wildcard public suffix boundary via related origins`() = runTest {
        var requestedUrl: String? = null
        val passkeyUtils = createPasskeyUtils(
            responseBody = """{"origins":["https://www.example.compute.amazonaws.com"]}""",
        ) { url ->
            requestedUrl = url
        }

        passkeyUtils.requireRpMatchesOrigin(
            rpId = "compute.amazonaws.com",
            origin = "https://www.example.compute.amazonaws.com",
            packageName = "com.example.app",
        )

        assertEquals(
            "https://compute.amazonaws.com/.well-known/webauthn",
            requestedUrl,
        )
    }

    @Test
    fun `https rp validation keeps trailing dot significant for explicit rp id`() = runTest {
        var requestedUrl: String? = null
        val passkeyUtils = createPasskeyUtils { url ->
            requestedUrl = url
        }

        assertFailsWith<IllegalStateException> {
            passkeyUtils.requireRpMatchesOrigin(
                rpId = "example.com.",
                origin = "https://example.com",
                packageName = "com.example.app",
            )
        }

        assertEquals(
            "https://example.com./.well-known/webauthn",
            requestedUrl,
        )
    }

    @Test
    fun `https rp validation keeps trailing dot significant for origin host`() = runTest {
        var requestedUrl: String? = null
        val passkeyUtils = createPasskeyUtils { url ->
            requestedUrl = url
        }

        assertFailsWith<IllegalStateException> {
            passkeyUtils.requireRpMatchesOrigin(
                rpId = "example.com",
                origin = "https://example.com.",
                packageName = "com.example.app",
            )
        }

        assertEquals(
            "https://example.com/.well-known/webauthn",
            requestedUrl,
        )
    }

    @Test
    fun `https rp validation allows matching trailing dot rp id and origin`() = runTest {
        var requestCount = 0
        val passkeyUtils = createPasskeyUtils {
            requestCount++
        }

        passkeyUtils.requireRpMatchesOrigin(
            rpId = "example.com.",
            origin = "https://login.example.com.",
            packageName = "com.example.app",
        )

        assertEquals(0, requestCount)
    }

    @Test
    fun `https rp validation rejects public suffix rp id`() = runTest {
        val passkeyUtils = createPasskeyUtils()

        assertFailsWith<IllegalStateException> {
            passkeyUtils.requireRpMatchesOrigin(
                rpId = "co.uk",
                origin = "https://example.co.uk",
                packageName = "com.example.app",
            )
        }
    }

    @Test
    fun `https rp validation rejects rp id that is not suffix of origin domain`() = runTest {
        var requestedUrl: String? = null
        val passkeyUtils = createPasskeyUtils { url ->
            requestedUrl = url
        }

        assertFailsWith<IllegalStateException> {
            passkeyUtils.requireRpMatchesOrigin(
                rpId = "m.login.example.com",
                origin = "https://login.example.com",
                packageName = "com.example.app",
            )
        }

        assertEquals(
            "https://m.login.example.com/.well-known/webauthn",
            requestedUrl,
        )
    }

    // Spec coverage: Section 5.1.3/5.1.4.1 requires a valid-domain effective
    // domain. Section 4's RP ID definition permits HTTPS web origins, with an
    // HTTP exception only when the origin host is exactly localhost.
    @Test
    fun `web rp validation allows http localhost origin`() = runTest {
        var requestCount = 0
        val passkeyUtils = createPasskeyUtils {
            requestCount++
        }

        passkeyUtils.requireRpMatchesOrigin(
            rpId = "localhost",
            origin = "http://localhost:8080",
            packageName = "com.example.app",
        )

        assertEquals(0, requestCount)
    }

    @Test
    fun `web rp validation rejects http non-localhost origin`() = runTest {
        val passkeyUtils = createPasskeyUtils(
            responseBody = """{"origins":["http://example.co.uk"]}""",
        )

        assertFailsWith<IllegalStateException> {
            passkeyUtils.requireRpMatchesOrigin(
                rpId = "example.co.uk",
                origin = "http://example.co.uk",
                packageName = "com.example.app",
            )
        }
    }

    @Test
    fun `web rp validation rejects http origins whose host is not exactly localhost`() = runTest {
        val invalidOrigins = listOf(
            "http://127.0.0.1:8080",
            "http://[::1]:8080",
            "http://sub.localhost:8080",
            "http://localhost.example.com",
        )

        invalidOrigins.forEach { origin ->
            var requestCount = 0
            val passkeyUtils = createPasskeyUtils {
                requestCount++
            }

            assertFailsWith<IllegalStateException> {
                passkeyUtils.requireRpMatchesOrigin(
                    rpId = "localhost",
                    origin = origin,
                    packageName = "com.example.app",
                )
            }

            assertEquals(0, requestCount)
        }
    }

    @Test
    fun `web rp validation rejects related localhost origins`() = runTest {
        val relatedOrigins = listOf(
            "http://localhost:8080",
            "https://localhost:8443",
        )

        relatedOrigins.forEach { relatedOrigin ->
            var requestedUrl: String? = null
            val passkeyUtils = createPasskeyUtils(
                responseBody = """{"origins":["$relatedOrigin"]}""",
            ) { url ->
                requestedUrl = url
            }

            assertFailsWith<IllegalStateException> {
                passkeyUtils.requireRpMatchesOrigin(
                    rpId = "example.com",
                    origin = relatedOrigin,
                    packageName = "com.example.app",
                )
            }

            assertEquals(
                "https://example.com/.well-known/webauthn",
                requestedUrl,
            )
        }
    }

    @Test
    fun `web rp validation rejects related public suffix origin`() = runTest {
        val passkeyUtils = createPasskeyUtils(
            responseBody = """{"origins":["https://co.uk"]}""",
        )

        assertFailsWith<IllegalStateException> {
            passkeyUtils.requireRpMatchesOrigin(
                rpId = "example.com",
                origin = "https://co.uk",
                packageName = "com.example.app",
            )
        }
    }

    @Test
    fun `android rp validation rejects invalid rp id before asset links fetch`() = runTest {
        val invalidRpIds = listOf(
            "example.com:443",
            "example.com/path",
            "127.0.0.1",
            "co.uk",
        )

        invalidRpIds.forEach { rpId ->
            var requestCount = 0
            val passkeyUtils = createPasskeyUtils {
                requestCount++
            }

            assertFailsWith<IllegalStateException> {
                passkeyUtils.requireRpMatchesOrigin(
                    rpId = rpId,
                    origin = "android:apk-key-hash:AA",
                    packageName = "com.example.app",
                )
            }
            assertEquals(0, requestCount)
        }
    }

    @Test
    fun `rp id defaulting uses caller effective domain for https origin`() {
        val passkeyUtils = createPasskeyUtils()

        val rpId = passkeyUtils.resolveRpId(
            rpId = null,
            origin = "https://login.example.com:1337",
        )

        assertEquals("login.example.com", rpId)
    }

    @Test
    fun `rp id defaulting keeps trailing dot from caller effective domain`() {
        val passkeyUtils = createPasskeyUtils()

        val rpId = passkeyUtils.resolveRpId(
            rpId = null,
            origin = "https://login.example.com.:1337",
        )

        assertEquals("login.example.com.", rpId)
    }

    @Test
    fun `rp id defaulting uses localhost for http localhost origin`() {
        val passkeyUtils = createPasskeyUtils()

        val rpId = passkeyUtils.resolveRpId(
            rpId = null,
            origin = "http://localhost:8080",
        )

        assertEquals("localhost", rpId)
    }

    @Test
    fun `rp id defaulting rejects https origins whose host is not a domain`() {
        val passkeyUtils = createPasskeyUtils()
        val invalidOrigins = listOf(
            "https://127.0.0.1",
            "https://[::1]",
        )

        invalidOrigins.forEach { origin ->
            assertFailsWith<RuntimeException> {
                passkeyUtils.resolveRpId(
                    rpId = null,
                    origin = origin,
                )
            }
        }
    }

    @Test
    fun `rp id defaulting rejects http origins whose host is not exactly localhost`() {
        val passkeyUtils = createPasskeyUtils()
        val invalidOrigins = listOf(
            "http://example.com",
            "http://127.0.0.1:8080",
            "http://[::1]:8080",
            "http://sub.localhost:8080",
            "http://localhost.example.com",
        )

        invalidOrigins.forEach { origin ->
            assertFailsWith<RuntimeException> {
                passkeyUtils.resolveRpId(
                    rpId = null,
                    origin = origin,
                )
            }
        }
    }

    @Test
    fun `rp id resolution canonicalizes explicit rp id case before validation`() = runTest {
        val passkeyUtils = createPasskeyUtils()

        val rpId = passkeyUtils.resolveAndValidateRpId(
            rpId = "EXAMPLE.COM",
            origin = "https://login.example.com",
            packageName = "com.example.app",
        )

        assertEquals("example.com", rpId)
    }

    @Test
    fun `rp id resolution rejects explicit rp id with surrounding whitespace`() = runTest {
        val passkeyUtils = createPasskeyUtils()

        assertFailsWith<IllegalStateException> {
            passkeyUtils.resolveAndValidateRpId(
                rpId = " example.com ",
                origin = "https://login.example.com",
                packageName = "com.example.app",
            )
        }
    }

    @Test
    fun `begin get rp id resolution rejects explicit unrelated rp id`() = runTest {
        var requestedUrl: String? = null
        val passkeyUtils = createPasskeyUtils { url ->
            requestedUrl = url
        }

        val rpId = resolveCredentialProviderBeginGetRpId(
            requestRpId = "example.co.uk",
            origin = "https://login.example.com",
            packageName = "com.example.app",
            passkeyUtils = passkeyUtils,
        )

        assertNull(rpId)
        assertEquals(
            "https://example.co.uk/.well-known/webauthn",
            requestedUrl,
        )
    }

    @Test
    fun `begin get rp id resolution allows explicit related origin rp id`() = runTest {
        val passkeyUtils = createPasskeyUtils(
            responseBody = """{"origins":["https://login.example.com"]}""",
        )

        val rpId = resolveCredentialProviderBeginGetRpId(
            requestRpId = "example.co.uk",
            origin = "https://login.example.com",
            packageName = "com.example.app",
            passkeyUtils = passkeyUtils,
        )

        assertEquals("example.co.uk", rpId)
    }

    @Test
    fun `begin get rp id resolution defaults missing rp id before discovery`() = runTest {
        var requestCount = 0
        val passkeyUtils = createPasskeyUtils {
            requestCount++
        }

        val rpId = resolveCredentialProviderBeginGetRpId(
            requestRpId = null,
            origin = "https://login.example.com",
            packageName = "com.example.app",
            passkeyUtils = passkeyUtils,
        )

        assertEquals("login.example.com", rpId)
        assertEquals(0, requestCount)
    }

    @Test
    fun `rp id defaulting rejects android origin without effective domain`() {
        val passkeyUtils = createPasskeyUtils()

        assertFailsWith<IllegalArgumentException> {
            passkeyUtils.resolveRpId(
                rpId = null,
                origin = "android:apk-key-hash:abc",
            )
        }
    }

    // Spec coverage: Section 5.11 requires HTTPS well-known JSON with
    // application/json, status 200, an origins string array, at least five
    // origin labels, and HTTPS-only redirects. Section 5.11.1 defines the
    // per-origin validation loop.
    @Test
    fun `https rp validation allows related origin from webauthn well known document`() = runTest {
        var requestedUrl: String? = null
        val passkeyUtils = createPasskeyUtils(
            responseBody = """{"origins":["https://example.co.uk"]}""",
        ) { url ->
            requestedUrl = url
        }

        passkeyUtils.requireRpMatchesOrigin(
            rpId = "example.com",
            origin = "https://example.co.uk",
            packageName = "com.example.app",
        )

        assertEquals(
            "https://example.com/.well-known/webauthn",
            requestedUrl,
        )
    }

    @Test
    fun `https rp validation rejects related origin document with non ok status`() = runTest {
        val passkeyUtils = createPasskeyUtils(
            responseBody = """{"origins":["https://example.co.uk"]}""",
            status = HttpStatusCode.Created,
        )

        assertFailsWith<IllegalStateException> {
            passkeyUtils.requireRpMatchesOrigin(
                rpId = "example.com",
                origin = "https://example.co.uk",
                packageName = "com.example.app",
            )
        }
    }

    @Test
    fun `https rp validation follows https redirects for related origin document`() = runTest {
        val requestedUrls = mutableListOf<String>()
        val passkeyUtils = createPasskeyUtils(
            responses = listOf(
                MockHttpResponse(
                    status = HttpStatusCode.Found,
                    contentType = null,
                    headers = headersOf(
                        HttpHeaders.Location,
                        "https://static.example.com/.well-known/webauthn",
                    ),
                ),
                MockHttpResponse(
                    responseBody = """{"origins":["https://example.co.uk"]}""",
                ),
            ),
        ) { url ->
            requestedUrls += url
        }

        passkeyUtils.requireRpMatchesOrigin(
            rpId = "example.com",
            origin = "https://example.co.uk",
            packageName = "com.example.app",
        )

        assertEquals(
            listOf(
                "https://example.com/.well-known/webauthn",
                "https://static.example.com/.well-known/webauthn",
            ),
            requestedUrls,
        )
    }

    @Test
    fun `https rp validation rejects related origin document redirect loop above limit`() = runTest {
        val requestedUrls = mutableListOf<String>()
        val passkeyUtils = createPasskeyUtils(
            responses = List(21) {
                MockHttpResponse(
                    status = HttpStatusCode.Found,
                    contentType = null,
                    headers = headersOf(
                        HttpHeaders.Location,
                        "https://example.com/.well-known/webauthn",
                    ),
                )
            },
        ) { url ->
            requestedUrls += url
        }

        val error = assertFailsWith<IllegalStateException> {
            passkeyUtils.requireRpMatchesOrigin(
                rpId = "example.com",
                origin = "https://example.co.uk",
                packageName = "com.example.app",
            )
        }

        assertTrue(
            actual = error.message.orEmpty()
                .contains("Related origins document redirects too many times."),
            message = error.message,
        )
        assertEquals(21, requestedUrls.size)
    }

    @Test
    fun `https rp validation allows https redirect to unrelated related origin document host`() = runTest {
        val requestedUrls = mutableListOf<String>()
        val passkeyUtils = createPasskeyUtils(
            responses = listOf(
                MockHttpResponse(
                    status = HttpStatusCode.Found,
                    contentType = null,
                    headers = headersOf(
                        HttpHeaders.Location,
                        "https://evil-example.com/.well-known/webauthn",
                    ),
                ),
                MockHttpResponse(
                    responseBody = """{"origins":["https://example.co.uk"]}""",
                ),
            ),
        ) { url ->
            requestedUrls += url
        }

        passkeyUtils.requireRpMatchesOrigin(
            rpId = "example.com",
            origin = "https://example.co.uk",
            packageName = "com.example.app",
        )

        assertEquals(
            listOf(
                "https://example.com/.well-known/webauthn",
                "https://evil-example.com/.well-known/webauthn",
            ),
            requestedUrls,
        )
    }

    @Test
    fun `https rp validation rejects http redirects for related origin document`() = runTest {
        val requestedUrls = mutableListOf<String>()
        val passkeyUtils = createPasskeyUtils(
            responses = listOf(
                MockHttpResponse(
                    status = HttpStatusCode.Found,
                    contentType = null,
                    headers = headersOf(
                        HttpHeaders.Location,
                        "http://example.com/.well-known/webauthn",
                    ),
                ),
                MockHttpResponse(
                    responseBody = """{"origins":["https://example.co.uk"]}""",
                ),
            ),
        ) { url ->
            requestedUrls += url
        }

        assertFailsWith<IllegalStateException> {
            passkeyUtils.requireRpMatchesOrigin(
                rpId = "example.com",
                origin = "https://example.co.uk",
                packageName = "com.example.app",
            )
        }
        assertEquals(
            listOf("https://example.com/.well-known/webauthn"),
            requestedUrls,
        )
    }

    @Test
    fun `https rp validation rejects unlisted related origin`() = runTest {
        val passkeyUtils = createPasskeyUtils(
            responseBody = """{"origins":["https://another.example"]}""",
        )

        assertFailsWith<IllegalStateException> {
            passkeyUtils.requireRpMatchesOrigin(
                rpId = "example.com",
                origin = "https://example.co.uk",
                packageName = "com.example.app",
            )
        }
    }

    @Test
    fun `https rp validation keeps trailing dot significant for related origins`() = runTest {
        val passkeyUtils = createPasskeyUtils(
            responseBody = """{"origins":["https://example.co.uk."]}""",
        )

        assertFailsWith<IllegalStateException> {
            passkeyUtils.requireRpMatchesOrigin(
                rpId = "example.com",
                origin = "https://example.co.uk",
                packageName = "com.example.app",
            )
        }
    }

    @Test
    fun `https rp validation rejects related origin document with invalid content type`() = runTest {
        val passkeyUtils = createPasskeyUtils(
            responseBody = """{"origins":["https://example.co.uk"]}""",
            contentType = ContentType.Text.Plain.toString(),
        )

        assertFailsWith<IllegalStateException> {
            passkeyUtils.requireRpMatchesOrigin(
                rpId = "example.com",
                origin = "https://example.co.uk",
                packageName = "com.example.app",
            )
        }
    }

    @Test
    fun `https rp validation rejects lenient related origin json`() = runTest {
        val passkeyUtils = createPasskeyUtils(
            responseBody = """{origins:["https://example.co.uk"]}""",
        )

        assertFailsWith<IllegalStateException> {
            passkeyUtils.requireRpMatchesOrigin(
                rpId = "example.com",
                origin = "https://example.co.uk",
                packageName = "com.example.app",
            )
        }
    }

    @Test
    fun `https rp validation skips invalid related origin document entries`() = runTest {
        val passkeyUtils = createPasskeyUtils(
            responseBody = """{"origins":["https://example.co.uk/login","https://example.co.uk"]}""",
        )

        passkeyUtils.requireRpMatchesOrigin(
            rpId = "example.com",
            origin = "https://example.co.uk",
            packageName = "com.example.app",
        )
    }

    @Test
    fun `https rp validation rejects related origin document with only invalid entries`() = runTest {
        val passkeyUtils = createPasskeyUtils(
            responseBody = """{"origins":["https://example.co.uk/login"]}""",
        )

        assertFailsWith<IllegalStateException> {
            passkeyUtils.requireRpMatchesOrigin(
                rpId = "example.com",
                origin = "https://example.co.uk",
                packageName = "com.example.app",
            )
        }
    }

    @Test
    fun `https rp validation rejects related origin with fragment`() = runTest {
        val passkeyUtils = createPasskeyUtils(
            responseBody = """{"origins":["https://example.co.uk#fragment"]}""",
        )

        assertFailsWith<IllegalStateException> {
            passkeyUtils.requireRpMatchesOrigin(
                rpId = "example.com",
                origin = "https://example.co.uk",
                packageName = "com.example.app",
            )
        }
    }

    @Test
    fun `https rp validation rejects related origin with user info`() = runTest {
        val passkeyUtils = createPasskeyUtils(
            responseBody = """{"origins":["https://user@example.co.uk"]}""",
        )

        assertFailsWith<IllegalStateException> {
            passkeyUtils.requireRpMatchesOrigin(
                rpId = "example.com",
                origin = "https://example.co.uk",
                packageName = "com.example.app",
            )
        }
    }

    @Test
    fun `https rp validation enforces related origin label cap`() = runTest {
        val passkeyUtils = createPasskeyUtils(
            responseBody = """
                {
                  "origins": [
                    "https://one.example",
                    "https://two.example",
                    "https://three.example",
                    "https://four.example",
                    "https://five.example",
                    "https://target.example"
                  ]
                }
            """.trimIndent(),
        )

        assertFailsWith<IllegalStateException> {
            passkeyUtils.requireRpMatchesOrigin(
                rpId = "example.com",
                origin = "https://target.example",
                packageName = "com.example.app",
            )
        }
    }

    @Test
    fun `https rp validation rejects related origin document without origins property`() = runTest {
        val passkeyUtils = createPasskeyUtils(
            responseBody = """{"notOrigins":["https://example.co.uk"]}""",
        )

        assertFailsWith<IllegalStateException> {
            passkeyUtils.requireRpMatchesOrigin(
                rpId = "example.com",
                origin = "https://example.co.uk",
                packageName = "com.example.app",
            )
        }
    }

    @Test
    fun `https rp validation rejects related origin document with empty origins array`() = runTest {
        val passkeyUtils = createPasskeyUtils(
            responseBody = """{"origins":[]}""",
        )

        assertFailsWith<IllegalStateException> {
            passkeyUtils.requireRpMatchesOrigin(
                rpId = "example.com",
                origin = "https://example.co.uk",
                packageName = "com.example.app",
            )
        }
    }

    @Test
    fun `https rp validation rejects related origin document with non-string entries`() = runTest {
        val passkeyUtils = createPasskeyUtils(
            responseBody = """{"origins":[42,"https://example.co.uk"]}""",
        )

        assertFailsWith<IllegalStateException> {
            passkeyUtils.requireRpMatchesOrigin(
                rpId = "example.com",
                origin = "https://example.co.uk",
                packageName = "com.example.app",
            )
        }
    }

    private fun flags(
        extensionData: Boolean = false,
        attestationData: Boolean = false,
        backupState: Boolean = false,
        backupEligibility: Boolean = false,
        userVerification: Boolean = false,
        userPresence: Boolean = false,
    ): Int = authDataFlags(
        extensionData = extensionData,
        attestationData = attestationData,
        backupState = backupState,
        backupEligibility = backupEligibility,
        userVerification = userVerification,
        userPresence = userPresence,
    ).toInt() and 0xff

    private fun aaguidFor(
        passkeyUtils: PasskeyUtils,
        attestation: CreatePasskeyAttestation?,
        credentialId: ByteArray = byteArrayOf(0x01, 0x02),
    ): ByteArray = passkeyUtils.authData(
        rpId = "example.com",
        counter = 0,
        credentialId = credentialId,
        credentialPublicKey = byteArrayOf(0xa0.toByte()),
        attestation = attestation,
        userVerification = true,
        userPresence = true,
    ).attestedCredentialDataAaguid()
}

private const val AUTHENTICATOR_DATA_AAGUID_OFFSET = 32 + 1 + 4
private const val WEBAUTHN_AAGUID_SIZE_BYTES = 16

private fun ByteArray.attestedCredentialDataAaguid(): ByteArray = copyOfRange(
    fromIndex = AUTHENTICATOR_DATA_AAGUID_OFFSET,
    toIndex = AUTHENTICATOR_DATA_AAGUID_OFFSET + WEBAUTHN_AAGUID_SIZE_BYTES,
)

private fun createPasskeyUtils(
    responseBody: String = """{"origins":[]}""",
    status: HttpStatusCode = HttpStatusCode.OK,
    contentType: String? = ContentType.Application.Json.toString(),
    responses: List<MockHttpResponse> = listOf(
        MockHttpResponse(
            responseBody = responseBody,
            status = status,
            contentType = contentType,
        ),
    ),
    onRequest: (String) -> Unit = {},
): PasskeyUtils {
    var responseIndex = 0
    val engine = MockEngine { request ->
        onRequest(request.url.toString())
        val response = responses.getOrElse(responseIndex) {
            responses.last()
        }
        responseIndex++
        respond(
            content = response.responseBody,
            status = response.status,
            headers = response.toHeaders(),
        )
    }
    val httpClient = HttpClient(engine) {
        install(ContentNegotiation) {
            register(
                ContentType.Application.Json,
                KotlinxSerializationConverter(Json),
            )
        }
    }
    return PasskeyUtils(
        cryptoService = FakeCryptoGenerator,
        privilegedAppsService = FakePrivilegedAppsService,
        tldService = FakeTldService,
        httpClient = httpClient,
    )
}

private data class MockHttpResponse(
    val responseBody: String = """{"origins":[]}""",
    val status: HttpStatusCode = HttpStatusCode.OK,
    val contentType: String? = ContentType.Application.Json.toString(),
    val headers: Headers = Headers.Empty,
) {
    fun toHeaders(): Headers = Headers.build {
        headers.entries().forEach { (name, values) ->
            appendAll(name, values)
        }
        contentType?.let {
            append(HttpHeaders.ContentType, it)
        }
    }
}

private object FakeTldService : TldService {
    override val version: String = "test"

    private val publicSuffixes = setOf(
        "com",
        "co.uk",
        "example",
    )
    private val wildcardPublicSuffixBases = setOf(
        "compute.amazonaws.com",
    )

    override fun getDomainName(
        host: String,
    ): IO<String> = {
        val normalizedHost = host
            .trim()
            .lowercase()
        val wildcardDomainName = wildcardPublicSuffixBases
            .mapNotNull { base ->
                val suffix = ".$base"
                val prefix = normalizedHost
                    .takeIf { it.endsWith(suffix) }
                    ?.removeSuffix(suffix)
                    ?: return@mapNotNull null
                val prefixLabels = prefix
                    .split('.')
                    .filter(String::isNotEmpty)
                when (prefixLabels.size) {
                    0 -> null
                    1 -> normalizedHost
                    else -> prefixLabels
                        .takeLast(2)
                        .joinToString(separator = ".") + suffix
                }
            }
            .maxByOrNull { domainName ->
                domainName.count { it == '.' }
            }
        if (wildcardDomainName != null) {
            wildcardDomainName
        } else {
            val publicSuffix = publicSuffixes
                .filter { suffix ->
                    normalizedHost == suffix ||
                            normalizedHost.endsWith(".$suffix")
                }
                .maxByOrNull { suffix ->
                    suffix.count { it == '.' }
                }
            if (publicSuffix == null) {
                normalizedHost
            } else {
                val hostLabels = normalizedHost.split('.')
                val suffixLabelCount = publicSuffix.split('.').size
                if (hostLabels.size <= suffixLabelCount) {
                    normalizedHost
                } else {
                    hostLabels
                        .takeLast(suffixLabelCount + 1)
                        .joinToString(separator = ".")
                }
            }
        }
    }
}

private object FakePrivilegedAppsService : PrivilegedAppsService {
    override fun get(): IO<String> = error("Not used in this test.")

    override fun stringify(
        privilegedApps: List<DPrivilegedApp>,
    ): IO<String> = error("Not used in this test.")
}

private object FakeCryptoGenerator : CryptoGenerator {
    override fun hkdf(
        seed: ByteArray,
        salt: ByteArray?,
        info: ByteArray?,
        length: Int,
    ): ByteArray = error("Not used in this test.")

    override fun pbkdf2(
        seed: ByteArray,
        salt: ByteArray,
        iterations: Int,
        length: Int,
    ): ByteArray = error("Not used in this test.")

    override fun argon2(
        mode: Argon2Mode,
        seed: ByteArray,
        salt: ByteArray,
        iterations: Int,
        memoryKb: Int,
        parallelism: Int,
    ): ByteArray = error("Not used in this test.")

    override fun seed(
        length: Int,
    ): ByteArray = error("Not used in this test.")

    override fun hmac(
        key: ByteArray,
        data: ByteArray,
        algorithm: CryptoHashAlgorithm,
    ): ByteArray = error("Not used in this test.")

    override fun hashSha1(
        data: ByteArray,
    ): ByteArray = error("Not used in this test.")

    override fun hashSha256(
        data: ByteArray,
    ): ByteArray = ByteArray(32)

    override fun hashMd5(
        data: ByteArray,
    ): ByteArray = MessageDigest
        .getInstance("MD5")
        .digest(data)

    override fun uuid(): String = error("Not used in this test.")

    override fun random(): Int = error("Not used in this test.")

    override fun random(
        range: IntRange,
    ): Int = error("Not used in this test.")
}
