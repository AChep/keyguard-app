package com.artemchep.keyguard.provider.bitwarden.api.builder

import com.artemchep.keyguard.common.exception.HttpException
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.util.CHROME_MAJOR_VERSION
import com.artemchep.keyguard.provider.bitwarden.ServerEnv
import com.artemchep.keyguard.provider.bitwarden.api.BitwardenPersona
import com.artemchep.keyguard.provider.bitwarden.entity.SyncEntity
import com.artemchep.keyguard.provider.bitwarden.entity.AttachmentEntity
import com.artemchep.keyguard.provider.bitwarden.entity.AvatarRequestEntity
import com.artemchep.keyguard.provider.bitwarden.entity.CipherAttachmentUploadEntity
import com.artemchep.keyguard.provider.bitwarden.entity.CipherEntity
import com.artemchep.keyguard.provider.bitwarden.entity.CipherListEntity
import com.artemchep.keyguard.provider.bitwarden.entity.FolderEntity
import com.artemchep.keyguard.provider.bitwarden.entity.HibpBreachResponse
import com.artemchep.keyguard.provider.bitwarden.entity.OrganizationSubscriptionResponseModel
import com.artemchep.keyguard.provider.bitwarden.entity.ProfileRequestEntity
import com.artemchep.keyguard.provider.bitwarden.entity.ProfileResponseModel
import com.artemchep.keyguard.provider.bitwarden.entity.SendEntity
import com.artemchep.keyguard.provider.bitwarden.entity.SendFileUploadEntity
import com.artemchep.keyguard.provider.bitwarden.entity.SendFileUploadTarget
import com.artemchep.keyguard.provider.bitwarden.entity.SendFileUploadType
import com.artemchep.keyguard.provider.bitwarden.entity.SubscriptionResponseModel
import com.artemchep.keyguard.provider.bitwarden.entity.TwoFactorEmailRequestEntity
import com.artemchep.keyguard.provider.bitwarden.entity.request.CipherArchiveRequest
import com.artemchep.keyguard.provider.bitwarden.entity.request.CipherAttachmentCreateRequest
import com.artemchep.keyguard.provider.bitwarden.entity.request.CipherBulkShareRequest
import com.artemchep.keyguard.provider.bitwarden.entity.request.CipherBulkUpdateCollectionsRequest
import com.artemchep.keyguard.provider.bitwarden.entity.request.CipherCreateRequest
import com.artemchep.keyguard.provider.bitwarden.entity.request.CipherDeleteRequest
import com.artemchep.keyguard.provider.bitwarden.entity.request.CipherMoveRequest
import com.artemchep.keyguard.provider.bitwarden.entity.request.CipherRequest
import com.artemchep.keyguard.provider.bitwarden.entity.request.CipherRestoreRequest
import com.artemchep.keyguard.provider.bitwarden.entity.request.CipherUnarchiveRequest
import com.artemchep.keyguard.provider.bitwarden.entity.request.FolderRequest
import com.artemchep.keyguard.provider.bitwarden.entity.request.SendRequest
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.escapeIfNeeded
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import io.ktor.utils.io.streams.asInput
import io.ktor.util.AttributeKey
import java.io.File
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

val routeAttribute = AttributeKey<String>("route")

val accountAttribute = AttributeKey<String>("account")

/**
 * A DSL for the API server endpoints. To use, call [ServerEnv.api]
 * property.
 *
 * @author Artem Chepurnyi
 */
@JvmInline
value class ServerEnvApi @Deprecated("Use the [ServerEnv.api] property instead.") constructor(
    private val url: String,
) {
    val twoFactor get() = TwoFactor(url = url + "two-factor/")

    val accounts get() = Accounts(url = url + "accounts/")

    val organizations get() = Organizations(url = url + "organizations/")

    val ciphers get() = Ciphers(url = url + "ciphers/")

    val folders get() = Folders(url = url + "folders/")

    val sends get() = Sends(url = url + "sends/")

    val hibp get() = HaveIBeenPwned(url = url + "hibp/")

    val sync get() = url + "sync"

    @JvmInline
    value class HaveIBeenPwned(
        private val url: String,
    ) {
        /**
         * Send a GET request to search breaches.
         *
         * Request:
         * ```
         * ?username=artemchep@gmail.com
         * ```
         *
         * Response: List of breaches found.
         */
        val breach get() = url + "breach"
    }

    @JvmInline
    value class TwoFactor(
        private val url: String,
    ) {
        val requestEmailCode get() = url + "send-email-login"
    }

    @JvmInline
    value class Accounts(
        private val url: String,
    ) {
        /**
         * Send a PUT request to change the avatar
         * color of the account.
         *
         * Request:
         * ```
         * { "avatarColor": "#e5beed" }
         * ```
         *
         * Response: profile object.
         */
        val avatar get() = url + "avatar"

        /**
         * Send a PUT request to change the profile info.
         *
         * Request:
         * ```
         * { "culture":"en-US", "name":"Main Vault2", "masterPasswordHint":null }
         * ```
         *
         * Response: profile object.
         */
        val profile get() = url + "profile"

        val subscription get() = url + "subscription"
    }

    @JvmInline
    value class Organizations(
        private val url: String,
    ) {
        fun focus(id: String) = Organization(url = url + id)

        @JvmInline
        value class Organization(
            val url: String,
        ) {
            val subscription get() = "$url/subscription"
        }
    }

    @JvmInline
    value class Ciphers(
        val url: String,
    ) {
        val create get() = url + "create"

        val archive get() = url + "archive"

        val unarchive get() = url + "unarchive"

        val trash get() = url + "delete"

        val restore get() = url + "restore"

        val move get() = url + "move"

        val share get() = url + "share"

        val bulkCollections get() = url + "bulk-collections"

        fun focus(id: String) = Cipher(url = url + id)

        @JvmInline
        value class Cipher(
            val url: String,
        ) {
            val trash get() = "$url/delete"

            val restore get() = "$url/restore"

            val attachments get() = Attachments(url = "$url/attachment/")
        }

        @JvmInline
        value class Attachments(
            val url: String,
        ) {
            fun postV2() = url + "v2"

            fun focus(id: String) = Attachment(url = url + id)

            @JvmInline
            value class Attachment(
                val url: String,
            ) {
                val renew get() = "$url/renew"
            }
        }
    }

    @JvmInline
    value class Folders(
        private val url: String,
    ) {
        fun post() = url

        fun create() = url + "create"

        fun put(id: String) = url + id

        fun delete(id: String) = url + id
    }

    @JvmInline
    value class Sends(
        private val url: String,
    ) {
        fun post() = url

        fun postFileV2() = url + "file/v2"

        fun focus(id: String) = Send(url = url + id)

        @JvmInline
        value class Send(
            val url: String,
        ) {
            val removePassword get() = "$url/remove-password"

            fun file(id: String) = "$url/file/$id"
        }
    }
}

@Suppress("DEPRECATION")
val ServerEnv.api
    get() = ServerEnvApi(url = buildApiUrl())

suspend fun ServerEnvApi.TwoFactor.requestEmailCode(
    httpClient: HttpClient,
    env: ServerEnv,
    model: TwoFactorEmailRequestEntity,
) = httpClient
    .post(requestEmailCode) {
        headers(env)
        contentType(ContentType.Application.Json)
        setBody(model)
        attributes.put(routeAttribute, "request-email-code")
    }
    .bodyOrApiException<Unit>()

suspend fun ServerEnvApi.Accounts.avatar(
    httpClient: HttpClient,
    env: ServerEnv,
    token: String,
    model: AvatarRequestEntity,
) = httpClient
    .put(avatar) {
        headers(env)
        header("Authorization", "Bearer $token")
        contentType(ContentType.Application.Json)
        setBody(model)
        attributes.put(routeAttribute, "put-avatar")
    }
    .bodyOrApiException<ProfileResponseModel>()

suspend fun ServerEnvApi.Accounts.profile(
    httpClient: HttpClient,
    env: ServerEnv,
    token: String,
    model: ProfileRequestEntity,
) = httpClient
    .put(profile) {
        headers(env)
        header("Authorization", "Bearer $token")
        contentType(ContentType.Application.Json)
        setBody(model)
        attributes.put(routeAttribute, "put-profile")
    }
    .bodyOrApiException<ProfileResponseModel>()

suspend fun ServerEnvApi.Accounts.subscription(
    httpClient: HttpClient,
    env: ServerEnv,
    token: String,
) = httpClient
    .get(subscription) {
        headers(env)
        header("Authorization", "Bearer $token")
        attributes.put(routeAttribute, "get-account-subscription")
    }
    .bodyOrApiException<SubscriptionResponseModel>()

suspend fun ServerEnvApi.Organizations.subscription(
    httpClient: HttpClient,
    env: ServerEnv,
    token: String,
    organizationId: String,
) = focus(id = organizationId)
    .subscription(
        httpClient = httpClient,
        env = env,
        token = token,
    )

suspend fun ServerEnvApi.Organizations.Organization.subscription(
    httpClient: HttpClient,
    env: ServerEnv,
    token: String,
) = httpClient
    .get(subscription) {
        headers(env)
        header("Authorization", "Bearer $token")
        attributes.put(routeAttribute, "get-organization-subscription")
    }
    .bodyOrApiException<OrganizationSubscriptionResponseModel>()

suspend fun ServerEnvApi.HaveIBeenPwned.breach(
    httpClient: HttpClient,
    env: ServerEnv,
    token: String,
    username: String,
) = httpClient
    .get(breach) {
        headers(env)
        header("Authorization", "Bearer $token")
        parameter("username", username)
        attributes.put(routeAttribute, "get-username-breach")
    }
    .bodyOrApiException<List<HibpBreachResponse>>()

// Sync

suspend fun ServerEnvApi.sync(
    httpClient: HttpClient,
    env: ServerEnv,
    token: String,
) = httpClient
    .get(sync) {
        headers(env)
        header("Authorization", "Bearer $token")
        parameter("excludeDomains", false)
        attributes.put(routeAttribute, "sync")
    }
    .bodyOrApiException<SyncEntity>()

// Ciphers

suspend fun ServerEnvApi.Ciphers.post(
    httpClient: HttpClient,
    env: ServerEnv,
    token: String,
    body: CipherRequest,
) = url.post<CipherRequest, CipherEntity>(
    httpClient = httpClient,
    env = env,
    token = token,
    body = body,
    route = "post-cipher",
)

suspend fun ServerEnvApi.Ciphers.create(
    httpClient: HttpClient,
    env: ServerEnv,
    token: String,
    body: CipherCreateRequest,
) = create.post<CipherCreateRequest, CipherEntity>(
    httpClient = httpClient,
    env = env,
    token = token,
    body = body,
    route = "post-create-cipher",
)

suspend fun ServerEnvApi.Ciphers.archive(
    httpClient: HttpClient,
    env: ServerEnv,
    token: String,
    body: CipherArchiveRequest,
) = archive.put<CipherArchiveRequest, CipherListEntity>(
    httpClient = httpClient,
    env = env,
    token = token,
    body = body,
    route = "put-archive-ciphers",
)

suspend fun ServerEnvApi.Ciphers.unarchive(
    httpClient: HttpClient,
    env: ServerEnv,
    token: String,
    body: CipherUnarchiveRequest,
) = unarchive.put<CipherUnarchiveRequest, CipherListEntity>(
    httpClient = httpClient,
    env = env,
    token = token,
    body = body,
    route = "put-unarchive-ciphers",
)

// Note: This is a soft delete that moves ciphers to trash.
suspend fun ServerEnvApi.Ciphers.trash(
    httpClient: HttpClient,
    env: ServerEnv,
    token: String,
    body: CipherDeleteRequest,
) = trash.put<CipherDeleteRequest, HttpResponse>(
    httpClient = httpClient,
    env = env,
    token = token,
    body = body,
    route = "put-delete-ciphers",
)

// Note: This is a hard delete that permanently removes ciphers.
suspend fun ServerEnvApi.Ciphers.delete(
    httpClient: HttpClient,
    env: ServerEnv,
    token: String,
    body: CipherDeleteRequest,
) = url.delete<CipherDeleteRequest>(
    httpClient = httpClient,
    env = env,
    token = token,
    body = body,
    route = "delete-ciphers",
)

suspend fun ServerEnvApi.Ciphers.restore(
    httpClient: HttpClient,
    env: ServerEnv,
    token: String,
    body: CipherRestoreRequest,
) = restore.put<CipherRestoreRequest, CipherListEntity>(
    httpClient = httpClient,
    env = env,
    token = token,
    body = body,
    route = "put-restore-ciphers",
)

suspend fun ServerEnvApi.Ciphers.move(
    httpClient: HttpClient,
    env: ServerEnv,
    token: String,
    body: CipherMoveRequest,
) = move.put<CipherMoveRequest, HttpResponse>(
    httpClient = httpClient,
    env = env,
    token = token,
    body = body,
    route = "put-move-ciphers",
)

suspend fun ServerEnvApi.Ciphers.share(
    httpClient: HttpClient,
    env: ServerEnv,
    token: String,
    body: CipherBulkShareRequest,
) = share.put<CipherBulkShareRequest, CipherListEntity>(
    httpClient = httpClient,
    env = env,
    token = token,
    body = body,
    route = "put-share-ciphers",
)

suspend fun ServerEnvApi.Ciphers.bulkCollections(
    httpClient: HttpClient,
    env: ServerEnv,
    token: String,
    body: CipherBulkUpdateCollectionsRequest,
) = bulkCollections.post<CipherBulkUpdateCollectionsRequest, HttpResponse>(
    httpClient = httpClient,
    env = env,
    token = token,
    body = body,
    route = "post-bulk-cipher-collections",
)

suspend fun ServerEnvApi.Ciphers.Cipher.get(
    httpClient: HttpClient,
    env: ServerEnv,
    token: String,
) = httpClient
    .get(url) {
        headers(env)
        header("Authorization", "Bearer $token")
        attributes.put(routeAttribute, "get-cipher")
    }
    .bodyOrApiException<CipherEntity>()

suspend fun ServerEnvApi.Ciphers.Cipher.put(
    httpClient: HttpClient,
    env: ServerEnv,
    token: String,
    body: CipherRequest,
): CipherEntity = url.put(
    httpClient = httpClient,
    env = env,
    token = token,
    body = body,
    route = "put-cipher",
)

suspend fun ServerEnvApi.Ciphers.Cipher.delete(
    httpClient: HttpClient,
    env: ServerEnv,
    token: String,
) = url.delete(
    httpClient = httpClient,
    env = env,
    token = token,
    route = "delete-cipher",
)

suspend fun ServerEnvApi.Ciphers.Cipher.trash(
    httpClient: HttpClient,
    env: ServerEnv,
    token: String,
) = httpClient
    .put(trash) {
        headers(env)
        header("Authorization", "Bearer $token")
        contentType(ContentType.Any)
        attributes.put(routeAttribute, "trash-cipher")
    }
    .bodyOrApiException<HttpResponse>()

suspend fun ServerEnvApi.Ciphers.Cipher.restore(
    httpClient: HttpClient,
    env: ServerEnv,
    token: String,
    cipherRequest: CipherRequest,
) = httpClient
    .put(restore) {
        headers(env)
        header("Authorization", "Bearer $token")
        contentType(ContentType.Application.Json)
        setBody(cipherRequest)
        attributes.put(routeAttribute, "restore-cipher")
    }
    .bodyOrApiException<CipherEntity>()

// Attachment

suspend fun ServerEnvApi.Ciphers.Attachments.post(
    httpClient: HttpClient,
    env: ServerEnv,
    token: String,
    attachmentKey: String,
    attachmentData: ByteArray,
): CipherEntity = kotlin.run {
    // TODO: Provide a stream to read data from, this should
    //  solve performance issues when uploading large files.
    //
    // val attachmentDataProvider = ChannelProvider {
    //     attachmentStream
    //         .toByteReadChannel()
    // }
    val body = MultiPartFormDataContent(
        formData {
            append("key", attachmentKey)
            append("data", attachmentData)
        },
    )
    url.post(
        httpClient = httpClient,
        env = env,
        token = token,
        body = body,
        route = "post-attachment",
    )
}

suspend fun ServerEnvApi.Ciphers.Attachments.postV2(
    httpClient: HttpClient,
    env: ServerEnv,
    token: String,
    body: CipherAttachmentCreateRequest,
) = postV2().post<CipherAttachmentCreateRequest, CipherAttachmentUploadEntity>(
    httpClient = httpClient,
    env = env,
    token = token,
    body = body,
    route = "post-cipher-attachment-v2",
)

suspend fun ServerEnvApi.Ciphers.Attachments.delete(
    httpClient: HttpClient,
    env: ServerEnv,
    token: String,
    id: String,
) = focus(id = id).url.delete(
    httpClient = httpClient,
    env = env,
    token = token,
    route = "delete-cipher",
)

suspend fun ServerEnvApi.Ciphers.Attachments.Attachment.get(
    httpClient: HttpClient,
    env: ServerEnv,
    token: String,
) = httpClient
    .get(url) {
        headers(env)
        header("Authorization", "Bearer $token")
        attributes.put(routeAttribute, "get-attachment")
    }
    .bodyOrApiException<AttachmentEntity>()

suspend fun ServerEnvApi.Ciphers.Attachments.Attachment.renew(
    httpClient: HttpClient,
    env: ServerEnv,
    token: String,
) = httpClient
    .get(renew) {
        headers(env)
        header("Authorization", "Bearer $token")
        attributes.put(routeAttribute, "renew-cipher-attachment-upload")
    }
    .bodyOrApiException<CipherAttachmentUploadEntity>()

// Folders

suspend fun ServerEnvApi.Folders.post(
    httpClient: HttpClient,
    env: ServerEnv,
    token: String,
    body: FolderRequest,
) = post().post<FolderRequest, FolderEntity>(
    httpClient = httpClient,
    env = env,
    token = token,
    body = body,
    route = "post-folder",
)

suspend fun ServerEnvApi.Folders.put(
    httpClient: HttpClient,
    env: ServerEnv,
    token: String,
    id: String,
    body: FolderRequest,
) = put(id = id).put<FolderRequest, FolderEntity>(
    httpClient = httpClient,
    env = env,
    token = token,
    body = body,
    route = "put-folder",
)

suspend fun ServerEnvApi.Folders.delete(
    httpClient: HttpClient,
    env: ServerEnv,
    token: String,
    id: String,
) = delete(id = id).delete(
    httpClient = httpClient,
    env = env,
    token = token,
    route = "delete-folder",
)

// Sends

suspend fun ServerEnvApi.Sends.post(
    httpClient: HttpClient,
    env: ServerEnv,
    token: String,
    body: SendRequest,
) = post().post<SendRequest, SendEntity>(
    httpClient = httpClient,
    env = env,
    token = token,
    body = body,
    route = "post-send",
)

suspend fun ServerEnvApi.Sends.postFileV2(
    httpClient: HttpClient,
    env: ServerEnv,
    token: String,
    body: SendRequest,
) = postFileV2().post<SendRequest, SendFileUploadEntity>(
    httpClient = httpClient,
    env = env,
    token = token,
    body = body,
    route = "post-send-file-v2",
)

suspend fun ServerEnvApi.Sends.Send.get(
    httpClient: HttpClient,
    env: ServerEnv,
    token: String,
) = httpClient
    .get(url) {
        headers(env)
        header("Authorization", "Bearer $token")
        attributes.put(routeAttribute, "get-send")
    }
    .bodyOrApiException<SendEntity>()

suspend fun ServerEnvApi.Sends.Send.getFileUploadTarget(
    httpClient: HttpClient,
    env: ServerEnv,
    token: String,
    fileId: String,
) = httpClient
    .get(file(id = fileId)) {
        headers(env)
        header("Authorization", "Bearer $token")
        attributes.put(routeAttribute, "get-send-file-upload-target")
    }
    .bodyOrApiException<SendFileUploadEntity>()

suspend fun ServerEnvApi.Sends.Send.put(
    httpClient: HttpClient,
    env: ServerEnv,
    token: String,
    body: SendRequest,
) = url.put<SendRequest, SendEntity>(
    httpClient = httpClient,
    env = env,
    token = token,
    body = body,
    route = "put-send",
)

suspend fun ServerEnvApi.Sends.Send.removePassword(
    httpClient: HttpClient,
    env: ServerEnv,
    token: String,
) = httpClient
    .put(removePassword) {
        headers(env)
        header("Authorization", "Bearer $token")
        attributes.put(routeAttribute, "remove-password-send")
    }
    .bodyOrApiException<SendEntity>()

suspend fun uploadSendFile(
    httpClient: HttpClient,
    env: ServerEnv,
    token: String,
    target: SendFileUploadTarget,
    fileName: String,
    filePath: String,
    fileLength: Long,
) = uploadFileToTarget(
    httpClient = httpClient,
    env = env,
    token = token,
    target = target,
    fileName = fileName,
    filePath = filePath,
    fileLength = fileLength,
    directRoute = "post-send-file-data",
    azureRoute = "put-send-file-data-azure",
)

suspend fun uploadCipherAttachment(
    httpClient: HttpClient,
    env: ServerEnv,
    token: String,
    target: SendFileUploadTarget,
    fileName: String,
    filePath: String,
    fileLength: Long,
) = uploadFileToTarget(
    httpClient = httpClient,
    env = env,
    token = token,
    target = target,
    fileName = fileName,
    filePath = filePath,
    fileLength = fileLength,
    directRoute = "post-cipher-attachment-data",
    azureRoute = "put-cipher-attachment-data-azure",
)

private suspend fun uploadFileToTarget(
    httpClient: HttpClient,
    env: ServerEnv,
    token: String,
    target: SendFileUploadTarget,
    fileName: String,
    filePath: String,
    fileLength: Long,
    directRoute: String,
    azureRoute: String,
) = when (target.type) {
    SendFileUploadType.Direct -> uploadFileToTargetDirect(
        httpClient = httpClient,
        env = env,
        token = token,
        target = target,
        fileName = fileName,
        filePath = filePath,
        fileLength = fileLength,
        route = directRoute,
    )

    SendFileUploadType.Azure -> {
        uploadFileToTargetAzure(
            httpClient = httpClient,
            env = env,
            target = target,
            filePath = filePath,
            fileLength = fileLength,
            route = azureRoute,
        )
    }
}

internal fun multipartFilenameParameter(
    fileName: String,
): String = "filename=${fileName.escapeIfNeeded()}"

private suspend fun uploadFileToTargetDirect(
    httpClient: HttpClient,
    env: ServerEnv,
    token: String,
    target: SendFileUploadTarget,
    fileName: String,
    filePath: String,
    fileLength: Long,
    route: String,
) = httpClient
    .post(target.resolveUrl(env)) {
        headers(env)
        header("Authorization", "Bearer $token")
        setBody(
            MultiPartFormDataContent(
                formData {
                    append(
                        key = "data",
                        value = io.ktor.client.request.forms.InputProvider(
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


private suspend fun uploadFileToTargetAzure(
    httpClient: HttpClient,
    env: ServerEnv,
    target: SendFileUploadTarget,
    filePath: String,
    fileLength: Long,
    route: String,
) = httpClient
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

private suspend fun HttpResponse.bodyOrApiExceptionUnitStrict(
    expectedStatus: HttpStatusCode? = null,
) {
    bodyOrApiException<Unit>()
    val isSuccessful = expectedStatus
        ?.let { status == it }
        ?: (status.value in 200..299)
    if (!isSuccessful) {
        throw HttpException(
            statusCode = status,
            m = status.description,
            e = null,
        )
    }
}

suspend fun ServerEnvApi.Sends.Send.delete(
    httpClient: HttpClient,
    env: ServerEnv,
    token: String,
) = url.delete(
    httpClient = httpClient,
    env = env,
    token = token,
    route = "delete-send",
)

// Base

private suspend inline fun <reified Input, reified Output : Any> String.post(
    httpClient: HttpClient,
    env: ServerEnv,
    token: String,
    body: Input,
    route: String,
) = httpClient
    .post(this) {
        headers(env)
        header("Authorization", "Bearer $token")
        contentType(ContentType.Application.Json)
        setBody(body)
        attributes.put(routeAttribute, route)
    }
    .bodyOrApiException<Output>()

private suspend inline fun <reified Input, reified Output : Any> String.put(
    httpClient: HttpClient,
    env: ServerEnv,
    token: String,
    body: Input,
    route: String,
) = httpClient
    .put(this) {
        headers(env)
        header("Authorization", "Bearer $token")
        contentType(ContentType.Application.Json)
        setBody(body)
        attributes.put(routeAttribute, route)
    }
    .bodyOrApiException<Output>()

private suspend inline fun <reified Input> String.delete(
    httpClient: HttpClient,
    env: ServerEnv,
    token: String,
    body: Input,
    route: String,
) = httpClient
    .delete(this) {
        headers(env)
        header("Authorization", "Bearer $token")
        contentType(ContentType.Application.Json)
        setBody(body)
        attributes.put(routeAttribute, route)
    }
    .bodyOrApiException<HttpResponse>()

private suspend inline fun String.delete(
    httpClient: HttpClient,
    env: ServerEnv,
    token: String,
    route: String,
) = httpClient
    .delete(this) {
        headers(env)
        header("Authorization", "Bearer $token")
        attributes.put(routeAttribute, route)
    }
    .bodyOrApiException<HttpResponse>()

fun HttpRequestBuilder.headers(env: ServerEnv) {
    // Let Bitwarden know who we are.
    header("Keyguard-Client", "1")
    // Seems like now Bitwarden now requires you to specify
    // the client name and version.
    val persona = CurrentPlatform
        .let(BitwardenPersona::of)
    header("Bitwarden-Client-Name", persona.clientName)
    header("Bitwarden-Client-Version", persona.clientVersion)
    // Cloudflare-pleasing headers that do
    // nothing except let Keyguard pass their
    // bot detection.
    val language = Locale.getDefault().toLanguageTag()
        ?: "en-US"
    header("Accept-Language", language)
    header("Sec-Ch-Ua", """"Not.A/Brand";v="8", "Chromium";v="$CHROME_MAJOR_VERSION"""")
    header("Sec-Ch-Ua-Mobile", persona.chUaMobile)
    header("Sec-Ch-Ua-Platform", persona.chUaPlatform)
    // Potentially needs those:
    // header("Sec-Fetch-Dest", "empty")
    // header("Sec-Fetch-Mode", "cors")
    // header("Sec-Fetch-Site", "cross-site")
    // App does not work if hidden behind reverse-proxy under
    // a subdirectory. We should specify the 'referer' so the server
    // generates correct urls for us.
    if (env.baseUrl.isNotEmpty()) {
        header(
            key = "referer",
            value = env.baseUrl
                .ensureSuffix("/"),
        )
    }
    env.headers.forEach { header ->
        header(
            key = header.key,
            value = header.value,
        )
    }
}
