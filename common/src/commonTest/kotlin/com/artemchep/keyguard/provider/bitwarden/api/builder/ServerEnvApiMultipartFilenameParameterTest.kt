package com.artemchep.keyguard.provider.bitwarden.api.builder

import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.test.runTest
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ServerEnvApiMultipartFilenameParameterTest {
    @Test
    fun `safe filename stays unquoted`() {
        assertEquals(
            "filename=file.txt",
            multipartFilenameParameter("file.txt"),
        )
    }

    @Test
    fun `filename with spaces is quoted`() {
        assertEquals(
            "filename=\"my file.txt\"",
            multipartFilenameParameter("my file.txt"),
        )
    }

    @Test
    fun `filename with quote is escaped`() {
        assertEquals(
            "filename=\"a\\\"b.txt\"",
            multipartFilenameParameter("a\"b.txt"),
        )
    }

    @Test
    fun `filename with backslash is escaped`() {
        assertEquals(
            "filename=\"a\\\\b.txt\"",
            multipartFilenameParameter("a\\b.txt"),
        )
    }

    @Test
    fun `filename with control characters is escaped`() {
        assertEquals(
            "filename=\"a\\tb\\nc\\rd.txt\"",
            multipartFilenameParameter("a\tb\nc\rd.txt"),
        )
    }

    @Test
    fun `multipart payload keeps form-data disposition and filename parameter`() = runTest {
        val fileName = "my file.txt"
        val content = MultiPartFormDataContent(
            parts = formData {
                append(
                    key = "data",
                    value = byteArrayOf(1, 2, 3),
                    headers = Headers.build {
                        append(HttpHeaders.ContentDisposition, multipartFilenameParameter(fileName))
                        append(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
                    },
                )
            },
            boundary = "boundary",
        )

        val payload = content.readString()
        val dispositionLine = payload
            .lineSequence()
            .firstOrNull { it.startsWith("Content-Disposition: ") }
            ?.removePrefix("Content-Disposition: ")
        val disposition = dispositionLine?.let(ContentDisposition.Companion::parse)

        assertEquals(
            "Content-Disposition: form-data; name=data; filename=\"my file.txt\"",
            payload.lineSequence().first { it.startsWith("Content-Disposition: ") },
        )
        assertNotNull(disposition)
        assertEquals("form-data", disposition.disposition)
        assertEquals("data", disposition.parameter(ContentDisposition.Parameters.Name))
        assertEquals(fileName, disposition.parameter(ContentDisposition.Parameters.FileName))
    }
}

private suspend fun MultiPartFormDataContent.readString(): String {
    val channel = ByteChannel()
    writeTo(channel)
    channel.close()
    return channel.readRemaining().readByteArray().decodeToString()
}
