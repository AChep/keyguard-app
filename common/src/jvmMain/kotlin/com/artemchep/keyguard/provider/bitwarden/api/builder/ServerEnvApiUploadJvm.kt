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
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import io.ktor.utils.io.streams.asInput
import java.io.File
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

internal actual suspend fun platformUploadFileToTargetDirect(
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
                                File(filePath)
                                    .inputStream()
                                    .asInput()
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

internal actual suspend fun platformUploadFileToTargetAzure(
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
            header(
                "x-ms-date",
                DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC)),
            )
            uploadUrl.parameters["sv"]?.let { version ->
                header("x-ms-version", version)
            }
            setBody(
                object : OutgoingContent.ReadChannelContent() {
                    override val contentType = ContentType.Application.OctetStream
                    override val contentLength = fileLength

                    override fun readFrom() = File(filePath)
                        .inputStream()
                        .toByteReadChannel()
                },
            )
            attributes.put(routeAttribute, route)
        }
        .bodyOrApiExceptionUnitStrict(expectedStatus = HttpStatusCode.Created)
}
