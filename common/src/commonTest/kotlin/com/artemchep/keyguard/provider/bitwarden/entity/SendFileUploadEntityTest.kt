package com.artemchep.keyguard.provider.bitwarden.entity

import com.artemchep.keyguard.provider.bitwarden.ServerEnv
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SendFileUploadEntityTest {
    @Test
    fun `relative direct upload url resolves against api base`() {
        val target = SendFileUploadTarget(
            type = SendFileUploadType.Direct,
            url = "/sends/send-1/file/file-1",
        )

        val resolvedUrl = target.resolveUrl(
            env = ServerEnv(baseUrl = "https://vault.example.com"),
        )

        assertEquals(
            "https://vault.example.com/api/sends/send-1/file/file-1",
            resolvedUrl,
        )
    }

    @Test
    fun `absolute azure upload url stays unchanged`() {
        val target = SendFileUploadTarget(
            type = SendFileUploadType.Azure,
            url = "https://storage.example.com/container/blob?sas=token",
        )

        val resolvedUrl = target.resolveUrl(
            env = ServerEnv(baseUrl = "https://vault.example.com"),
        )

        assertEquals(
            "https://storage.example.com/container/blob?sas=token",
            resolvedUrl,
        )
    }

    @Test
    fun `unsupported upload type fails loudly`() {
        assertFailsWith<IllegalArgumentException> {
            SendFileUploadType.of(99)
        }
    }

    @Test
    fun `upload target can decode without send response`() {
        val entity = Json.decodeFromString<SendFileUploadEntity>(
            """
            {
              "object": "send-fileUpload",
              "url": "/sends/send-1/file/file-1",
              "fileUploadType": 0
            }
            """.trimIndent(),
        )

        assertNull(entity.sendResponse)
        assertEquals(SendFileUploadType.Direct, entity.uploadTarget.type)
        assertEquals("/sends/send-1/file/file-1", entity.uploadTarget.url)
    }
}
