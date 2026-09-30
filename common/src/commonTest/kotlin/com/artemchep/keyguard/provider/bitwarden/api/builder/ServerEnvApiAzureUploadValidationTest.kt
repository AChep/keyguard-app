package com.artemchep.keyguard.provider.bitwarden.api.builder

import com.artemchep.keyguard.common.exception.HttpException
import com.artemchep.keyguard.provider.bitwarden.ServerEnv
import com.artemchep.keyguard.provider.bitwarden.entity.SendFileUploadTarget
import com.artemchep.keyguard.provider.bitwarden.entity.SendFileUploadType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.fromHttpToGmtDate
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ServerEnvApiAzureUploadValidationTest {
    @Test
    fun `azure send file upload completes after blob put`() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val client = recordingClient(requests)

        withTempUploadFile { file ->
            uploadSendFile(
                httpClient = client,
                env = env,
                token = token,
                target = SendFileUploadTarget(
                    type = SendFileUploadType.Azure,
                    url = "https://storage.example.com/send.bin?sv=2025-07-05&sas=token",
                ),
                fileName = "send.bin",
                filePath = file.path,
                fileLength = file.length,
            )
        }

        assertEquals(
            listOf(
                RecordedRequest(
                    method = HttpMethod.Put,
                    url = "https://storage.example.com/send.bin?sv=2025-07-05&sas=token",
                    authorization = null,
                    blobType = "BlockBlob",
                    hasBlobDate = true,
                    blobVersion = "2025-07-05",
                ),
            ),
            requests,
        )
    }

    @Test
    fun `azure cipher attachment upload completes after blob put`() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val client = recordingClient(requests)

        withTempUploadFile { file ->
            uploadCipherAttachment(
                httpClient = client,
                env = env,
                token = token,
                target = SendFileUploadTarget(
                    type = SendFileUploadType.Azure,
                    url = "https://storage.example.com/attachment.bin?sv=2025-07-05&sas=token",
                ),
                fileName = "attachment.bin",
                filePath = file.path,
                fileLength = file.length,
            )
        }

        assertEquals(
            listOf(
                RecordedRequest(
                    method = HttpMethod.Put,
                    url = "https://storage.example.com/attachment.bin?sv=2025-07-05&sas=token",
                    authorization = null,
                    blobType = "BlockBlob",
                    hasBlobDate = true,
                    blobVersion = "2025-07-05",
                ),
            ),
            requests,
        )
    }

    @Test
    fun `azure upload streams the file as the blob body`() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val bodies = mutableListOf<RecordedBody>()
        val client = recordingClient(requests, bodies)

        withTempUploadFile { file ->
            uploadSendFile(
                httpClient = client,
                env = env,
                token = token,
                target = SendFileUploadTarget(
                    type = SendFileUploadType.Azure,
                    url = "https://storage.example.com/send.bin?sv=2025-07-05&sas=token",
                ),
                fileName = "send.bin",
                filePath = file.path,
                fileLength = file.length,
            )
        }

        val body = bodies.single()
        assertContentEquals(payload, body.bytes)
        assertEquals(payload.size.toLong(), body.contentLength)
        // Azure rejects a request whose x-ms-date is not an RFC 1123 date.
        val blobDate = assertNotNull(body.blobDate)
        blobDate.fromHttpToGmtDate()
    }

    @Test
    fun `direct send file upload does not validate azure upload`() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val client = recordingClient(requests)

        withTempUploadFile { file ->
            uploadSendFile(
                httpClient = client,
                env = env,
                token = token,
                target = SendFileUploadTarget(
                    type = SendFileUploadType.Direct,
                    url = "/sends/send-1/file/file-1",
                ),
                fileName = "send.bin",
                filePath = file.path,
                fileLength = file.length,
            )
        }

        assertEquals(
            listOf(
                RecordedRequest(
                    method = HttpMethod.Post,
                    url = "https://vault.example.com/api/sends/send-1/file/file-1",
                    authorization = "Bearer $token",
                    blobType = null,
                    hasBlobDate = false,
                    blobVersion = null,
                ),
            ),
            requests,
        )
    }

    @Test
    fun `direct upload streams the file as a multipart part`() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val bodies = mutableListOf<RecordedBody>()
        val client = recordingClient(requests, bodies)

        withTempUploadFile { file ->
            uploadCipherAttachment(
                httpClient = client,
                env = env,
                token = token,
                target = SendFileUploadTarget(
                    type = SendFileUploadType.Direct,
                    url = "/ciphers/cipher-1/attachment/attachment-1",
                ),
                fileName = "my attachment.bin",
                filePath = file.path,
                fileLength = file.length,
            )
        }

        val body = bodies.single().bytes.decodeToString()
        assertTrue(
            "Content-Disposition: form-data; name=\"data\"; filename=\"my attachment.bin\"" in body,
            body,
        )
        assertTrue(payload.decodeToString() in body, body)
    }

    @Test
    fun `direct upload failure propagates`() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val client = recordingClient(requests) {
            HttpStatusCode.InternalServerError
        }

        withTempUploadFile { file ->
            assertFailsWith<HttpException> {
                uploadSendFile(
                    httpClient = client,
                    env = env,
                    token = token,
                    target = SendFileUploadTarget(
                        type = SendFileUploadType.Direct,
                        url = "/sends/send-1/file/file-1",
                    ),
                    fileName = "send.bin",
                    filePath = file.path,
                    fileLength = file.length,
                )
            }
        }

        assertEquals(
            listOf(
                RecordedRequest(
                    method = HttpMethod.Post,
                    url = "https://vault.example.com/api/sends/send-1/file/file-1",
                    authorization = "Bearer $token",
                    blobType = null,
                    hasBlobDate = false,
                    blobVersion = null,
                ),
            ),
            requests,
        )
    }

    @Test
    fun `azure blob upload failure propagates`() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val client = recordingClient(requests) { requestIndex ->
            if (requestIndex == 0) HttpStatusCode.InternalServerError else HttpStatusCode.OK
        }

        withTempUploadFile { file ->
            assertFailsWith<HttpException> {
                uploadSendFile(
                    httpClient = client,
                    env = env,
                    token = token,
                    target = SendFileUploadTarget(
                        type = SendFileUploadType.Azure,
                        url = "https://storage.example.com/send.bin?sv=2025-07-05&sas=token",
                    ),
                    fileName = "send.bin",
                    filePath = file.path,
                    fileLength = file.length,
                )
            }
        }

        assertEquals(
            listOf(
                RecordedRequest(
                    method = HttpMethod.Put,
                    url = "https://storage.example.com/send.bin?sv=2025-07-05&sas=token",
                    authorization = null,
                    blobType = "BlockBlob",
                    hasBlobDate = true,
                    blobVersion = "2025-07-05",
                ),
            ),
            requests,
        )
    }

    private fun recordingClient(
        requests: MutableList<RecordedRequest>,
        bodies: MutableList<RecordedBody> = mutableListOf(),
        statusForRequest: (Int) -> HttpStatusCode = { requestIndex ->
            if (requests.getOrNull(requestIndex)?.method == HttpMethod.Put) {
                HttpStatusCode.Created
            } else {
                HttpStatusCode.OK
            }
        },
    ) = HttpClient(
        MockEngine { request ->
            val requestIndex = requests.size
            requests += RecordedRequest(
                method = request.method,
                url = request.url.toString(),
                authorization = request.headers[HttpHeaders.Authorization],
                blobType = request.headers["x-ms-blob-type"],
                hasBlobDate = request.headers["x-ms-date"] != null,
                blobVersion = request.headers["x-ms-version"],
            )
            bodies += RecordedBody(
                bytes = request.body.toByteArray(),
                contentLength = request.body.contentLength,
                blobDate = request.headers["x-ms-date"],
            )
            respond(
                content = "",
                status = statusForRequest(requestIndex),
            )
        },
    )

    private inline fun withTempUploadFile(
        block: (TempUploadFile) -> Unit,
    ) {
        val path = Path(
            SystemTemporaryDirectory,
            "keyguard-upload-${Random.nextLong().toULong()}.bin",
        )
        try {
            SystemFileSystem.sink(path).buffered().use { sink ->
                sink.write(payload)
            }
            block(
                TempUploadFile(
                    path = path.toString(),
                    length = payload.size.toLong(),
                ),
            )
        } finally {
            SystemFileSystem.delete(path, mustExist = false)
        }
    }

    private class TempUploadFile(
        val path: String,
        val length: Long,
    )

    private data class RecordedRequest(
        val method: HttpMethod,
        val url: String,
        val authorization: String?,
        val blobType: String?,
        val hasBlobDate: Boolean = false,
        val blobVersion: String? = null,
    )

    private class RecordedBody(
        val bytes: ByteArray,
        val contentLength: Long?,
        val blobDate: String?,
    )

    private companion object {
        val env = ServerEnv(baseUrl = "https://vault.example.com")
        const val token = "access-token"
        val payload = "keyguard-upload-payload".encodeToByteArray()
    }
}
