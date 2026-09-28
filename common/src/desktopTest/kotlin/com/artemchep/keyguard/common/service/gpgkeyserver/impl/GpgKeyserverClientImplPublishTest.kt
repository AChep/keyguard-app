package com.artemchep.keyguard.common.service.gpgkeyserver.impl

import com.artemchep.keyguard.common.exception.HttpException
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.DGpgKeyserverUploadResult
import com.artemchep.keyguard.common.model.GpgKeyserverConfig
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.parseQueryString
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GpgKeyserverClientImplPublishTest {
    @Test
    fun `VKS upload posts keytext as JSON to upload endpoint`() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val armored = "-----BEGIN PGP PUBLIC KEY BLOCK-----\nabc+def\n-----END PGP PUBLIC KEY BLOCK-----"
        val client = recordingClient(
            requests = requests,
            response = """{"key_fpr":"$TEST_FINGERPRINT","status":{"alice@example.com":"unpublished"},"token":"abc"}""",
            contentType = ContentType.Application.Json,
        )

        val result = GpgKeyserverClientImpl(client, FakeParser())
            .upload(
                publicKeyArmored = armored,
                config = GpgKeyserverConfig(),
            )
            .bind()

        val request = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals(listOf("vks", "v1", "upload"), request.pathSegments)
        assertEquals(GpgKeyserverClientImpl.ROUTE_VKS_UPLOAD, request.route)
        assertEquals(ContentType.Application.Json, request.contentType?.withoutParameters())
        val body = Json.parseToJsonElement(request.body).jsonObject
        assertEquals(armored, body.getValue("keytext").jsonPrimitive.content)

        assertEquals(TEST_FINGERPRINT, result.fingerprint)
        assertEquals("abc", result.token)
        assertEquals(
            mapOf("alice@example.com" to DGpgKeyserverUploadResult.EmailStatus.UNPUBLISHED),
            result.emailStatus,
        )
        assertEquals(setOf("alice@example.com"), result.verifiableEmails)
    }

    @Test
    fun `VKS request-verify posts token and addresses as JSON`() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val client = recordingClient(
            requests = requests,
            response = """
                {
                  "key_fpr": "$TEST_FINGERPRINT",
                  "status": {"alice@example.com": "pending", "bob@example.com": "published"},
                  "token": "abc"
                }
            """.trimIndent(),
            contentType = ContentType.Application.Json,
        )

        val result = GpgKeyserverClientImpl(client, FakeParser())
            .requestVerify(
                token = "abc",
                addresses = listOf("alice@example.com", " alice@example.com ", ""),
                config = GpgKeyserverConfig(),
            )
            .bind()

        val request = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals(listOf("vks", "v1", "request-verify"), request.pathSegments)
        assertEquals(GpgKeyserverClientImpl.ROUTE_VKS_REQUEST_VERIFY, request.route)
        assertEquals(ContentType.Application.Json, request.contentType?.withoutParameters())
        val body = Json.parseToJsonElement(request.body).jsonObject
        assertEquals("abc", body.getValue("token").jsonPrimitive.content)
        assertEquals(
            listOf("alice@example.com"),
            body.getValue("addresses").jsonArray.map { it.jsonPrimitive.content },
        )

        assertEquals(
            mapOf(
                "alice@example.com" to DGpgKeyserverUploadResult.EmailStatus.PENDING,
                "bob@example.com" to DGpgKeyserverUploadResult.EmailStatus.PUBLISHED,
            ),
            result.emailStatus,
        )
    }

    @Test
    fun `HKP request-verify is unsupported and issues no request`() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val client = recordingClient(
            requests = requests,
            response = "",
            contentType = ContentType.Text.Plain,
        )

        val result = GpgKeyserverClientImpl(client, FakeParser())
            .requestVerify(
                token = "abc",
                addresses = listOf("alice@example.com"),
                config = GpgKeyserverConfig(
                    url = GpgKeyserverConfig.HKP_UBUNTU_URL,
                    protocol = GpgKeyserverConfig.Protocol.HKP,
                ),
            )
            .attempt()
            .bind()

        assertIs<UnsupportedOperationException>(result.leftOrNull())
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `VKS upload fails when the response has no fingerprint`() = runTest {
        val client = recordingClient(
            requests = mutableListOf(),
            response = """{"status":{}}""",
            contentType = ContentType.Application.Json,
        )

        val result = GpgKeyserverClientImpl(client, FakeParser())
            .upload(
                publicKeyArmored = "-----BEGIN PGP PUBLIC KEY BLOCK-----",
                config = GpgKeyserverConfig(),
            )
            .attempt()
            .bind()

        assertIs<IllegalStateException>(result.leftOrNull())
    }

    @Test
    fun `VKS upload surfaces the keyserver error message`() = runTest {
        val client = recordingClient(
            requests = mutableListOf(),
            response = """{"error":"expected application/json data."}""",
            contentType = ContentType.Application.Json,
            status = HttpStatusCode.BadRequest,
        )

        val result = GpgKeyserverClientImpl(client, FakeParser())
            .upload(
                publicKeyArmored = "-----BEGIN PGP PUBLIC KEY BLOCK-----",
                config = GpgKeyserverConfig(),
            )
            .attempt()
            .bind()

        val error = assertIs<HttpException>(result.leftOrNull())
        assertEquals(HttpStatusCode.BadRequest, error.statusCode)
        assertEquals(GpgKeyserverClientImpl.ROUTE_VKS_UPLOAD, error.route)
        assertEquals("""{"error":"expected application/json data."}""", error.message)
    }

    @Test
    fun `HKP upload posts keytext to add endpoint`() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val armored = "-----BEGIN PGP PUBLIC KEY BLOCK-----\nabc\n-----END PGP PUBLIC KEY BLOCK-----"
        val client = recordingClient(
            requests = requests,
            response = "",
            contentType = ContentType.Text.Plain,
        )

        val result = GpgKeyserverClientImpl(client, FakeParser())
            .upload(
                publicKeyArmored = armored,
                config = GpgKeyserverConfig(
                    url = GpgKeyserverConfig.HKP_UBUNTU_URL,
                    protocol = GpgKeyserverConfig.Protocol.HKP,
                ),
            )
            .bind()

        val request = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals(listOf("pks", "add"), request.pathSegments)
        assertEquals(GpgKeyserverClientImpl.ROUTE_HKP_ADD, request.route)
        assertEquals(armored, parseQueryString(request.body)["keytext"])
        assertEquals(DGpgKeyserverUploadResult(), result)
    }
}
