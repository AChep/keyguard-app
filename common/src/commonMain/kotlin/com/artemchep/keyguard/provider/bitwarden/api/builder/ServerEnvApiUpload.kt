package com.artemchep.keyguard.provider.bitwarden.api.builder

import com.artemchep.keyguard.provider.bitwarden.ServerEnv
import com.artemchep.keyguard.provider.bitwarden.entity.SendFileUploadTarget
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.InputProvider
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.OutgoingContent
import io.ktor.http.toHttpDate
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writePacket
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

internal suspend fun uploadFileToTargetDirect(
    httpClient: HttpClient,
    env: ServerEnv,
    token: String,
    target: SendFileUploadTarget,
    fileName: String,
    filePath: String,
    fileLength: Long,
    route: String,
) {
    httpClient
        .post(target.resolveUrl(env)) {
            headers(env)
            header("Authorization", "Bearer $token")
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append(
                            key = "data",
                            value = InputProvider(
                                size = fileLength,
                            ) {
                                openUploadFile(filePath)
                            },
                            headers = Headers.build {
                                append(
                                    HttpHeaders.ContentDisposition,
                                    multipartFilenameParameter(fileName),
                                )
                                append(
                                    HttpHeaders.ContentType,
                                    ContentType.Application.OctetStream.toString(),
                                )
                            },
                        )
                    },
                ),
            )
            attributes.put(routeAttribute, route)
        }
        .bodyOrApiExceptionUnitStrict()
}

internal suspend fun uploadFileToTargetAzure(
    httpClient: HttpClient,
    env: ServerEnv,
    target: SendFileUploadTarget,
    filePath: String,
    fileLength: Long,
    route: String,
) {
    httpClient
        .put(target.resolveUrl(env)) {
            val uploadUrl = Url(target.resolveUrl(env))
            header("x-ms-blob-type", "BlockBlob")
            header("x-ms-date", GMTDate().toHttpDate())
            uploadUrl.parameters["sv"]?.let { version ->
                header("x-ms-version", version)
            }
            setBody(
                object : OutgoingContent.WriteChannelContent() {
                    override val contentType = ContentType.Application.OctetStream
                    override val contentLength = fileLength

                    // Closes the file once sent; a ByteReadChannel over it would keep it open.
                    override suspend fun writeTo(channel: ByteWriteChannel) {
                        openUploadFile(filePath).use { source ->
                            channel.writePacket(source)
                        }
                    }
                },
            )
            attributes.put(routeAttribute, route)
        }
        .bodyOrApiExceptionUnitStrict(expectedStatus = HttpStatusCode.Created)
}

// Called again for every retry attempt, so each attempt reads the file from the start.
private fun openUploadFile(filePath: String): Source = SystemFileSystem
    .source(Path(filePath))
    .buffered()
