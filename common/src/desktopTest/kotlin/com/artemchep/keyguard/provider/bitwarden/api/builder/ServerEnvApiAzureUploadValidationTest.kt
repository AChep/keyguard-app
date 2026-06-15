package com.artemchep.keyguard.provider.bitwarden.api.builder

import com.artemchep.keyguard.common.exception.HttpException
import com.artemchep.keyguard.provider.bitwarden.ServerEnv
import com.artemchep.keyguard.provider.bitwarden.entity.SendFileUploadTarget
import com.artemchep.keyguard.provider.bitwarden.entity.SendFileUploadType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
                filePath = file.absolutePath,
                fileLength = file.length(),
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
                filePath = file.absolutePath,
                fileLength = file.length(),
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
                filePath = file.absolutePath,
                fileLength = file.length(),
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
                    filePath = file.absolutePath,
                    fileLength = file.length(),
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
                    filePath = file.absolutePath,
                    fileLength = file.length(),
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
            respond(
                content = "",
                status = statusForRequest(requestIndex),
            )
        },
    )

    private inline fun withTempUploadFile(
        block: (File) -> Unit,
    ) {
        val file = Files.createTempFile("keyguard-upload", ".bin").toFile()
        try {
            file.writeBytes(byteArrayOf(1, 2, 3))
            block(file)
        } finally {
            file.delete()
        }
    }

    private data class RecordedRequest(
        val method: HttpMethod,
        val url: String,
        val authorization: String?,
        val blobType: String?,
        val hasBlobDate: Boolean = false,
        val blobVersion: String? = null,
    )

    private companion object {
        val env = ServerEnv(baseUrl = "https://vault.example.com")
        const val token = "access-token"
    }
}
