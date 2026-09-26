package com.artemchep.keyguard.android

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.Argon2Mode
import com.artemchep.keyguard.common.model.CryptoHashAlgorithm
import com.artemchep.keyguard.common.model.DPrivilegedApp
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.gpmprivapps.PrivilegedAppsService
import com.artemchep.keyguard.common.service.tld.TldService
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.KotlinxSerializationConverter
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.security.MessageDigest
import kotlin.test.assertFailsWith

class PasskeyUtilsTest {
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
}

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
                    else ->
                        prefixLabels
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
