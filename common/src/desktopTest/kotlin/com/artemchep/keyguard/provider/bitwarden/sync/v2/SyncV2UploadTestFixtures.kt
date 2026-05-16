package com.artemchep.keyguard.provider.bitwarden.sync.v2

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.AccountTask
import com.artemchep.keyguard.common.model.Argon2Mode
import com.artemchep.keyguard.common.model.CryptoHashAlgorithm
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.model.PasswordStrength
import com.artemchep.keyguard.common.service.crypto.CipherEncryptor
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.database.InstantToLongAdapter
import com.artemchep.keyguard.common.service.database.ObjectToStringAdapter
import com.artemchep.keyguard.common.service.database.SshUsageHistoryRequestTypeToLongAdapter
import com.artemchep.keyguard.common.service.database.SshUsageHistoryResponseTypeToLongAdapter
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.usecase.GetPasswordStrength
import com.artemchep.keyguard.common.usecase.Watchdog
import com.artemchep.keyguard.copy.Base64ServiceJvm
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCollection
import com.artemchep.keyguard.core.store.bitwarden.BitwardenEquivalentDomain
import com.artemchep.keyguard.core.store.bitwarden.BitwardenFolder
import com.artemchep.keyguard.core.store.bitwarden.BitwardenMeta
import com.artemchep.keyguard.core.store.bitwarden.BitwardenOrganization
import com.artemchep.keyguard.core.store.bitwarden.BitwardenProfile
import com.artemchep.keyguard.core.store.bitwarden.BitwardenSend
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.core.store.bitwarden.BitwardenToken
import com.artemchep.keyguard.core.store.bitwarden.ServiceToken
import com.artemchep.keyguard.crypto.CipherEncryptorImpl
import com.artemchep.keyguard.crypto.CryptoGeneratorJvm
import com.artemchep.keyguard.data.CipherFilter
import com.artemchep.keyguard.data.CipherUsageHistory
import com.artemchep.keyguard.data.Database
import com.artemchep.keyguard.data.GeneratorEmailRelay
import com.artemchep.keyguard.data.GeneratorHistory
import com.artemchep.keyguard.data.GeneratorWordlist
import com.artemchep.keyguard.data.GeneratorWordlistWord
import com.artemchep.keyguard.data.PrivilegedApp
import com.artemchep.keyguard.data.SshUsageHistory
import com.artemchep.keyguard.data.UrlBlock
import com.artemchep.keyguard.data.UrlOverride
import com.artemchep.keyguard.data.WatchtowerThreat
import com.artemchep.keyguard.data.bitwarden.Account
import com.artemchep.keyguard.data.bitwarden.Cipher
import com.artemchep.keyguard.data.bitwarden.Collection as DbCollection
import com.artemchep.keyguard.data.bitwarden.EquivalentDomains
import com.artemchep.keyguard.data.bitwarden.Folder
import com.artemchep.keyguard.data.bitwarden.Meta
import com.artemchep.keyguard.data.bitwarden.Organization
import com.artemchep.keyguard.data.bitwarden.Profile
import com.artemchep.keyguard.data.bitwarden.Send as DbSend
import com.artemchep.keyguard.data.pwnage.AccountBreach
import com.artemchep.keyguard.data.pwnage.PasswordBreach
import com.artemchep.keyguard.provider.bitwarden.ServerEnv
import com.artemchep.keyguard.provider.bitwarden.api.builder.api
import com.artemchep.keyguard.provider.bitwarden.api.builder.routeAttribute
import com.artemchep.keyguard.provider.bitwarden.crypto.BitwardenCrCta
import com.artemchep.keyguard.provider.bitwarden.crypto.BitwardenCrImpl
import com.artemchep.keyguard.provider.bitwarden.crypto.BitwardenCrKey
import com.artemchep.keyguard.provider.bitwarden.crypto.CryptoKey
import com.artemchep.keyguard.provider.bitwarden.crypto.SymmetricCryptoKey2
import com.artemchep.keyguard.provider.bitwarden.crypto.appendProfileToken2
import com.artemchep.keyguard.provider.bitwarden.crypto.decodeSymmetricOrThrow
import com.artemchep.keyguard.provider.bitwarden.crypto.transform
import com.artemchep.keyguard.provider.bitwarden.entity.AttachmentEntity
import com.artemchep.keyguard.provider.bitwarden.entity.CipherAttachmentUploadEntity
import com.artemchep.keyguard.provider.bitwarden.entity.CipherEntity
import com.artemchep.keyguard.provider.bitwarden.entity.CipherTypeEntity
import com.artemchep.keyguard.provider.bitwarden.entity.CollectionEntity
import com.artemchep.keyguard.provider.bitwarden.entity.ConnectTokenResponse
import com.artemchep.keyguard.provider.bitwarden.entity.FieldEntity
import com.artemchep.keyguard.provider.bitwarden.entity.FieldTypeEntity
import com.artemchep.keyguard.provider.bitwarden.entity.FolderEntity
import com.artemchep.keyguard.provider.bitwarden.entity.HibpBreachGroup
import com.artemchep.keyguard.provider.bitwarden.entity.LinkedIdTypeEntity
import com.artemchep.keyguard.provider.bitwarden.entity.LoginEntity
import com.artemchep.keyguard.provider.bitwarden.entity.LoginUriEntity
import com.artemchep.keyguard.provider.bitwarden.entity.PasswordHistoryEntity
import com.artemchep.keyguard.provider.bitwarden.entity.ProfileEntity
import com.artemchep.keyguard.provider.bitwarden.entity.SendEntity
import com.artemchep.keyguard.provider.bitwarden.entity.SendFileEntity
import com.artemchep.keyguard.provider.bitwarden.entity.SendFileUploadEntity
import com.artemchep.keyguard.provider.bitwarden.entity.SendFileUploadType
import com.artemchep.keyguard.provider.bitwarden.entity.SendTypeEntity
import com.artemchep.keyguard.provider.bitwarden.entity.SyncEntity
import com.artemchep.keyguard.provider.bitwarden.entity.UriMatchTypeEntity
import com.artemchep.keyguard.provider.bitwarden.entity.of
import com.artemchep.keyguard.provider.bitwarden.entity.request.CipherAttachmentCreateRequest
import com.artemchep.keyguard.provider.bitwarden.entity.request.CipherCreateRequest
import com.artemchep.keyguard.provider.bitwarden.entity.request.CipherDeleteRequest
import com.artemchep.keyguard.provider.bitwarden.entity.request.CipherRequest
import com.artemchep.keyguard.provider.bitwarden.entity.request.SendRequest
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadCoordinator
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadFile
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadTarget
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.KotlinxSerializationConverter
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readRemaining
import java.io.File
import java.nio.file.Files
import kotlinx.io.readByteArray
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Instant

internal const val ACCOUNT_ID = "account-1"

internal class UploadTestServer {
    val env = ServerEnv(baseUrl = BASE_URL)
    val token = "access-token"
    val requests = mutableListOf<RecordedRequest>()
    val folders = linkedMapOf<String, FolderEntity>()
    val ciphers = linkedMapOf<String, CipherEntity>()
    val collections = linkedMapOf<String, CollectionEntity>()
    val sends = linkedMapOf<String, SendEntity>()
    val uploadedCipherAttachmentBodies = linkedMapOf<String, String>()
    val uploadedSendFileBodies = linkedMapOf<String, String>()
    val uploadedSendFileNames = linkedMapOf<String, String?>()
    val deletedCipherAttachmentIds = mutableListOf<String>()
    val deletedSendIds = mutableListOf<String>()
    val renewNotFoundAttachmentIds = mutableSetOf<String>()
    val renewFailuresByAttachmentId = linkedMapOf<String, HttpStatusCode>()
    val renewFailureMessagesByAttachmentId = linkedMapOf<String, String>()
    val cipherAttachmentUploadFailuresById = linkedMapOf<String, HttpStatusCode>()
    val cipherAttachmentDeleteFailuresById = linkedMapOf<String, HttpStatusCode>()
    val cipherDeleteFailuresById = linkedMapOf<String, HttpStatusCode>()
    val sendDeleteFailuresById = linkedMapOf<String, HttpStatusCode>()

    var nextCipherAttachmentReservationMissingId: Boolean = false
    var nextCipherAttachmentReservationWithoutCipherResponse: Boolean = false
    var nextCipherAttachmentReservationWithoutRemoteAttachment: Boolean = false
    var nextCipherAttachmentReservationFailure: Pair<HttpStatusCode, String>? = null
    var nextCipherAttachmentUploadFailure: HttpStatusCode? = null
    var nextCipherAttachmentUploadFailureMessage: String = "cipher upload failed"
    var nextCipherGetFailure: HttpStatusCode? = null
    var cipherGetFailureAfterSuccessfulGets: Int? = null
    var nextCipherPutFailure: HttpStatusCode? = null
    var nextCipherTrashFailure: HttpStatusCode? = null
    var nextCipherBulkDeleteFailure: HttpStatusCode? = null
    var nextCipherTrashException: Throwable? = null
    var cipherPutAppliesRequestBody: Boolean = false
    var nextCipherCreateResponse: CipherEntity? = null
    var nextCipherPutResponse: CipherEntity? = null
    var nextCipherGetResponse: CipherEntity? = null
    var nextSendFileReservationFailure: Pair<HttpStatusCode, String>? = null
    var nextSendFileUploadFailure: HttpStatusCode? = null
    var nextSendFileUploadFailureMessage: String = "send file upload failed"
    var nextSendGetFailure: HttpStatusCode? = null
    var corruptNextCipherGetResponse: Boolean = false
    var revisionDate: String = "rev-upload-test"
    var profile: ProfileEntity = testProfile
    var refreshedAccessToken: String = "refreshed-access-token"
    var refreshedRefreshToken: String = "refresh-token-refreshed"
    val syncUnauthorizedTokens = mutableSetOf<String>()

    private var nextAttachmentIndex = 1
    private var nextCipherIndex = 1
    private var nextSendIndex = 1

    val client = HttpClient(
        MockEngine { request ->
            val recorded = RecordedRequest(
                method = request.method,
                url = request.url.toString(),
                route = request.attributes.getOrNull(routeAttribute),
                authorization = request.headers[HttpHeaders.Authorization],
                cacheControl = request.headers[HttpHeaders.CacheControl],
                body = request.body.asText(),
            )
            requests += recorded
            handle(recorded)
        },
    ) {
        install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
            register(ContentType.Application.Json, KotlinxSerializationConverter(json))
        }
    }

    fun seedCipher(cipher: CipherEntity) {
        ciphers[cipher.id] = cipher
    }

    fun seedFolder(folder: FolderEntity) {
        folders[folder.id] = folder
    }

    fun seedCollection(collection: CollectionEntity) {
        collections[collection.id] = collection
    }

    fun seedSend(send: SendEntity) {
        sends[send.id] = send
    }

    fun cipherAttachmentReservation(
        cipherId: String,
        attachmentId: String,
    ): CipherAttachmentUploadEntity =
        CipherAttachmentUploadEntity(
            attachmentId = attachmentId,
            fileUploadTypeCode = SendFileUploadType.Direct.code,
            objectType = "attachment-fileUpload",
            url = "/ciphers/$cipherId/attachment/$attachmentId/data",
            cipherResponse = requireNotNull(ciphers[cipherId]),
        )

    private fun MockRequestHandleScope.handle(request: RecordedRequest) = when {
        request.method == HttpMethod.Get && request.path == "/api/accounts/revision-date" -> respondText(revisionDate)

        request.method == HttpMethod.Get && request.path == "/api/sync" -> {
            val bearerToken = request.bearerToken()
            if (bearerToken in syncUnauthorizedTokens) {
                respondApiError(
                    status = HttpStatusCode.Unauthorized,
                    description = "access token expired",
                )
            } else {
                respondJson(
                    SyncEntity(
                        profile = profile,
                        folders = folders.values.toList(),
                        ciphers = ciphers.values.toList(),
                        collections = collections.values.toList(),
                        sends = sends.values.toList(),
                    ),
                )
            }
        }

        request.method == HttpMethod.Post &&
            request.path == "/identity/connect/token" -> {
            respondJson(
                ConnectTokenResponse(
                    accessToken = refreshedAccessToken,
                    accessTokenType = "Bearer",
                    accessTokenExpiresInSeconds = 3600L,
                    refreshToken = refreshedRefreshToken,
                ),
            )
        }

        request.method == HttpMethod.Put &&
            request.path == "/api/ciphers/delete" -> {
            val body = json.decodeFromString<CipherDeleteRequest>(request.body)
            body.ids.forEach { cipherId ->
                ciphers[cipherId]?.let { cipher ->
                    ciphers[cipherId] = cipher.copy(
                        revisionDate = T4,
                        deletedDate = T4,
                    )
                }
            }
            respondText("")
        }

        request.method == HttpMethod.Delete &&
            request.path == "/api/ciphers/" -> {
            val failure = nextCipherBulkDeleteFailure
            if (failure != null) {
                nextCipherBulkDeleteFailure = null
                respondApiError(
                    status = failure,
                    description = "cipher bulk delete failed",
                )
            } else {
                val body = json.decodeFromString<CipherDeleteRequest>(request.body)
                body.ids.forEach(ciphers::remove)
                respondText("")
            }
        }

        request.method == HttpMethod.Post &&
            request.path == "/api/ciphers/" -> {
            val body = json.decodeFromString<CipherRequest>(request.body)
            val cipherId = "cipher-created-${nextCipherIndex++}"
            val cipher =
                nextCipherCreateResponse
                    ?.also { nextCipherCreateResponse = null }
                    ?: cipherEntityFromRequest(cipherId, body)
            ciphers[cipherId] = cipher
            respondJson(cipher)
        }

        request.method == HttpMethod.Post &&
            request.path == "/api/ciphers/create" -> {
            val body = json.decodeFromString<CipherCreateRequest>(request.body)
            val cipherId = "cipher-created-${nextCipherIndex++}"
            val cipher =
                nextCipherCreateResponse
                    ?.also { nextCipherCreateResponse = null }
                    ?: cipherEntityFromRequest(cipherId, body.cipher)
            ciphers[cipherId] = cipher
            respondJson(cipher)
        }

        request.method == HttpMethod.Post &&
            request.path.endsWith("/attachment/v2") -> {
            val cipherId = request.path
                .removePrefix("/api/ciphers/")
                .removeSuffix("/attachment/v2")
            val body = json.decodeFromString<CipherAttachmentCreateRequest>(request.body)
            val reservationFailure = nextCipherAttachmentReservationFailure
            if (reservationFailure != null) {
                nextCipherAttachmentReservationFailure = null
                respondApiError(
                    status = reservationFailure.first,
                    description = reservationFailure.second,
                )
            } else {
                val attachmentId = "attachment-created-${nextAttachmentIndex++}"
                if (nextCipherAttachmentReservationWithoutRemoteAttachment) {
                    nextCipherAttachmentReservationWithoutRemoteAttachment = false
                } else {
                    upsertCipherAttachment(
                        cipherId = cipherId,
                        attachment = testAttachmentEntity(
                            id = attachmentId,
                            fileName = body.fileName,
                            key = body.key,
                            size = body.fileSize,
                        ),
                    )
                }
                respondJson(
                    cipherAttachmentReservation(cipherId, attachmentId).copy(
                        attachmentId =
                            if (nextCipherAttachmentReservationMissingId) {
                                nextCipherAttachmentReservationMissingId = false
                                null
                            } else {
                                attachmentId
                            },
                        cipherResponse =
                            if (nextCipherAttachmentReservationWithoutCipherResponse) {
                                nextCipherAttachmentReservationWithoutCipherResponse = false
                                null
                            } else {
                                cipherAttachmentReservation(cipherId, attachmentId).cipherResponse
                            },
                    ),
                )
            }
        }

        request.method == HttpMethod.Get &&
            request.path.endsWith("/renew") -> {
            val (cipherId, attachmentId) = parseCipherAttachmentPath(
                request.path.removeSuffix("/renew"),
            )
            if (attachmentId in renewNotFoundAttachmentIds) {
                renewNotFoundAttachmentIds -= attachmentId
                respondText(
                    content = """{"error":{"description":"attachment reservation not found"}}""",
                    status = HttpStatusCode.NotFound,
                    contentType = ContentType.Application.Json,
                )
            } else if (attachmentId in renewFailuresByAttachmentId) {
                val status = requireNotNull(renewFailuresByAttachmentId.remove(attachmentId))
                respondApiError(
                    status = status,
                    description = renewFailureMessagesByAttachmentId.remove(attachmentId)
                        ?: "attachment renew failed",
                )
            } else {
                respondJson(cipherAttachmentReservation(cipherId, attachmentId))
            }
        }

        request.method == HttpMethod.Post &&
            request.path.contains("/attachment/") &&
            request.path.endsWith("/data") -> {
            val (_, attachmentId) = parseCipherAttachmentDataPath(request.path)
            val failure = cipherAttachmentUploadFailuresById.remove(attachmentId)
                ?: nextCipherAttachmentUploadFailure
            if (failure != null) {
                nextCipherAttachmentUploadFailure = null
                respondApiError(
                    status = failure,
                    description = nextCipherAttachmentUploadFailureMessage,
                )
            } else {
                uploadedCipherAttachmentBodies[attachmentId] = request.body
                respondText("")
            }
        }

        request.method == HttpMethod.Put &&
            request.path.startsWith("/api/ciphers/") &&
            request.path.endsWith("/restore") -> {
            val cipherId = request.path
                .removePrefix("/api/ciphers/")
                .removeSuffix("/restore")
            val restored = requireNotNull(ciphers[cipherId]).copy(
                revisionDate = T4,
                deletedDate = null,
            )
            ciphers[cipherId] = restored
            respondJson(restored)
        }

        request.method == HttpMethod.Put &&
            request.path.startsWith("/api/ciphers/") &&
            request.path.endsWith("/delete") -> {
            nextCipherTrashException?.let {
                nextCipherTrashException = null
                throw it
            }
            val failure = nextCipherTrashFailure
            if (failure != null) {
                nextCipherTrashFailure = null
                respondText(
                    content = "",
                    status = failure,
                )
            } else {
                val cipherId = request.path
                    .removePrefix("/api/ciphers/")
                    .removeSuffix("/delete")
                ciphers[cipherId] = requireNotNull(ciphers[cipherId]).copy(
                    revisionDate = T4,
                    deletedDate = T4,
                )
                respondText("")
            }
        }

        request.method == HttpMethod.Put &&
            request.path.startsWith("/api/ciphers/") -> {
            val failure = nextCipherPutFailure
            if (failure != null) {
                nextCipherPutFailure = null
                respondText(
                    content = """{"error":{"description":"cipher update failed"}}""",
                    status = failure,
                    contentType = ContentType.Application.Json,
                )
            } else {
                val cipherId = request.path.removePrefix("/api/ciphers/")
                val updated =
                    nextCipherPutResponse
                        ?.also { nextCipherPutResponse = null }
                        ?: if (cipherPutAppliesRequestBody) {
                        val body = json.decodeFromString<CipherRequest>(request.body)
                        cipherEntityFromRequest(cipherId, body)
                    } else {
                        requireNotNull(ciphers[cipherId]).copy(
                            revisionDate = T4,
                            deletedDate = null,
                        )
                    }
                ciphers[cipherId] = updated
                respondJson(updated)
            }
        }

        request.method == HttpMethod.Delete &&
            request.path.startsWith("/api/ciphers/") &&
            !request.path.contains("/attachment/") -> {
            val cipherId = request.path.removePrefix("/api/ciphers/")
            val failure = cipherDeleteFailuresById.remove(cipherId)
            if (failure != null) {
                respondApiError(
                    status = failure,
                    description = "cipher delete failed",
                )
            } else {
                ciphers.remove(cipherId)
                respondText("")
            }
        }

        request.method == HttpMethod.Delete &&
            request.path.contains("/attachment/") -> {
            val (cipherId, attachmentId) = parseCipherAttachmentPath(request.path)
            deletedCipherAttachmentIds += attachmentId
            val failure = cipherAttachmentDeleteFailuresById.remove(attachmentId)
            if (failure != null) {
                respondText(
                    content = """{"error":{"description":"cipher attachment delete failed"}}""",
                    status = failure,
                    contentType = ContentType.Application.Json,
                )
            } else {
            ciphers[cipherId] =
                requireNotNull(ciphers[cipherId]).copy(
                    attachments = ciphers.getValue(cipherId).attachments.orEmpty()
                        .filterNot { it.id == attachmentId },
                )
            respondText("")
            }
        }

        request.method == HttpMethod.Get &&
            request.path.startsWith("/api/ciphers/") -> {
            val cipherId = request.path.removePrefix("/api/ciphers/")
            val delayedFailure = cipherGetFailureAfterSuccessfulGets
            val failure =
                if (delayedFailure != null) {
                    if (delayedFailure <= 0) {
                        cipherGetFailureAfterSuccessfulGets = null
                        nextCipherGetFailure
                    } else {
                        cipherGetFailureAfterSuccessfulGets = delayedFailure - 1
                        null
                    }
                } else {
                    nextCipherGetFailure
                }
            if (failure != null) {
                nextCipherGetFailure = null
                respondText(
                    content = """{"error":{"description":"cipher get failed"}}""",
                    status = failure,
                    contentType = ContentType.Application.Json,
                )
            } else {
                val cipher =
                    nextCipherGetResponse
                        ?.also {
                            nextCipherGetResponse = null
                            ciphers[cipherId] = it
                        }
                        ?: requireNotNull(ciphers[cipherId])
                if (corruptNextCipherGetResponse) {
                    corruptNextCipherGetResponse = false
                    respondJson(cipher.copy(name = "not an encrypted cipher name"))
                } else {
                    respondJson(cipher)
                }
            }
        }

        request.method == HttpMethod.Post &&
            request.path == "/api/sends/file/v2" -> {
            val body = json.decodeFromString<SendRequest>(request.body)
            val reservationFailure = nextSendFileReservationFailure
            if (reservationFailure != null) {
                nextSendFileReservationFailure = null
                respondApiError(
                    status = reservationFailure.first,
                    description = reservationFailure.second,
                )
            } else {
                val sendId = "send-created-${nextSendIndex++}"
                val fileId = "file-created-1"
                val send = testSendEntity(
                    id = sendId,
                    key = requireNotNull(body.key),
                    name = body.name,
                    notes = body.notes,
                    file = testSendFileEntity(
                        id = fileId,
                        fileName = requireNotNull(body.file?.fileName),
                        size = requireNotNull(body.fileLength),
                    ),
                )
                sends[sendId] = send
                respondJson(
                    SendFileUploadEntity(
                        fileUploadTypeCode = SendFileUploadType.Direct.code,
                        objectType = "send-fileUpload",
                        url = "/sends/$sendId/file/$fileId",
                        sendResponse = send,
                    ),
                )
            }
        }

        request.method == HttpMethod.Put &&
            request.path.startsWith("/api/sends/") -> {
            val sendId = request.path.removePrefix("/api/sends/")
            val body = json.decodeFromString<SendRequest>(request.body)
            val existing = requireNotNull(sends[sendId])
            val file = existing.file
            val updated = existing.copy(
                name = body.name,
                notes = body.notes,
                file = file,
                revisionDate = T4,
            )
            sends[sendId] = updated
            respondJson(updated)
        }

        request.method == HttpMethod.Get &&
            request.path.contains("/file/") -> {
            val (sendId, fileId) = parseSendFilePath(request.path)
            respondJson(
                SendFileUploadEntity(
                    fileUploadTypeCode = SendFileUploadType.Direct.code,
                    objectType = "send-fileUpload",
                    url = "/sends/$sendId/file/$fileId",
                    sendResponse = requireNotNull(sends[sendId]),
                ),
            )
        }

        request.method == HttpMethod.Post &&
            request.path.contains("/file/") -> {
            val (sendId, fileId) = parseSendFilePath(request.path)
            val failure = nextSendFileUploadFailure
            if (failure != null) {
                nextSendFileUploadFailure = null
                respondApiError(
                    status = failure,
                    description = nextSendFileUploadFailureMessage,
                )
            } else {
                val uploadedFileName = request.body.extractMultipartFilename()
                uploadedSendFileNames[fileId] = uploadedFileName
                val expectedFileName = sends[sendId]?.file?.fileName
                if (expectedFileName != null && uploadedFileName != expectedFileName) {
                    respondApiError(
                        status = HttpStatusCode.BadRequest,
                        description = "Send file name does not match.",
                    )
                } else {
                    uploadedSendFileBodies[fileId] = request.body
                    respondText("")
                }
            }
        }

        request.method == HttpMethod.Delete &&
            request.path.startsWith("/api/sends/") -> {
            val sendId = request.path.removePrefix("/api/sends/")
            deletedSendIds += sendId
            val failure = sendDeleteFailuresById.remove(sendId)
            if (failure != null) {
                respondText(
                    content = """{"error":{"description":"send delete failed"}}""",
                    status = failure,
                    contentType = ContentType.Application.Json,
                )
            } else {
                sends.remove(sendId)
                respondText("")
            }
        }

        request.method == HttpMethod.Get &&
            request.path.startsWith("/api/sends/") -> {
            val sendId = request.path.removePrefix("/api/sends/")
            val failure = nextSendGetFailure
            if (failure != null) {
                nextSendGetFailure = null
                respondText(
                    content = """{"error":{"description":"send not found"}}""",
                    status = failure,
                    contentType = ContentType.Application.Json,
                )
            } else {
                respondJson(requireNotNull(sends[sendId]))
            }
        }

        else -> respondText(
            content = """{"error":{"description":"not found: ${request.method.value} ${request.path}"}}""",
            status = HttpStatusCode.NotFound,
        )
    }

    private fun MockRequestHandleScope.respondApiError(
        status: HttpStatusCode,
        description: String,
    ) = respondText(
        content = json.encodeToString(mapOf("object" to "error", "message" to description)),
        status = status,
        contentType = ContentType.Application.Json,
    )

    private fun upsertCipherAttachment(
        cipherId: String,
        attachment: AttachmentEntity,
    ) {
        val cipher = requireNotNull(ciphers[cipherId])
        ciphers[cipherId] = cipher.copy(
            revisionDate = T4,
            attachments = cipher.attachments
                .orEmpty()
                .filterNot { it.id == attachment.id } + attachment,
        )
    }

    private fun cipherEntityFromRequest(
        cipherId: String,
        request: CipherRequest,
    ) = CipherEntity(
        id = cipherId,
        organizationId = request.organizationId,
        folderId = request.folderId,
        key = request.key,
        favorite = request.favorite,
        revisionDate = T4,
        type = request.type,
        name = request.name,
        notes = request.notes,
        login = request.login?.let { login ->
            LoginEntity(
                uris = login.uris.map { uri ->
                    LoginUriEntity(
                        uri = uri.uri,
                        uriChecksum = uri.uriChecksum,
                        match = uri.match,
                    )
                },
                username = login.username,
                password = login.password,
                passwordRevisionDate = login.passwordRevisionDate,
                totp = login.totp,
            )
        },
        fields = request.fields?.map { field ->
            FieldEntity(
                type = field.type,
                name = field.name,
                value = field.value,
                linkedId = field.linkedId,
            )
        },
        attachments = emptyList(),
        passwordHistory = request.passwordHistory?.map { history ->
            PasswordHistoryEntity(
                password = history.password,
                lastUsedDate = history.lastUsedDate,
            )
        },
        collectionIds = emptyList(),
        encryptedFor = request.encryptedFor,
        archivedDate = request.archivedDate,
        reprompt = request.reprompt,
    )

    private inline fun <reified T : Any> MockRequestHandleScope.respondJson(body: T) =
        respondText(
            content = json.encodeToString(body),
            contentType = ContentType.Application.Json,
        )

    private fun MockRequestHandleScope.respondText(
        content: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        contentType: ContentType = ContentType.Text.Plain,
    ) = respond(
        content = content,
        status = status,
        headers = headersOf(HttpHeaders.ContentType, contentType.toString()),
    )

    data class RecordedRequest(
        val method: HttpMethod,
        val url: String,
        val route: String?,
        val authorization: String?,
        val cacheControl: String?,
        val body: String,
    ) {
        val path: String
            get() = url.substringAfter(BASE_URL, url).substringBefore('?')

        fun bearerToken(): String? = authorization?.removePrefix("Bearer ")
    }

    companion object {
        const val BASE_URL = "https://vault.example.com"

        val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            isLenient = true
        }

        private val testProfile = ProfileEntity(
            culture = "en-US",
            email = "user@example.com",
            emailVerified = true,
            id = "profile-1",
            key = "profile-key",
            privateKey = "profile-private-key",
            obj = "profile",
            organizations = emptyList(),
            premium = true,
            securityStamp = "security-stamp-1",
            twoFactorEnabled = false,
        )
    }
}

internal class UploadTestPendingUploadCoordinator(
    uploaded: Set<PendingUploadFile> = emptySet(),
    private val isUploadedFailure: Throwable? = null,
) : PendingUploadCoordinator {
    private val uploaded = uploaded.toMutableSet()
    val markUploadedCalls = mutableListOf<PendingUploadFile>()
    val deleteCalls = mutableListOf<PendingUploadFile>()

    override suspend fun stage(
        target: PendingUploadTarget,
        sourceUri: String,
        fileKey: ByteArray,
    ): PendingUploadFile = error("unused")

    override suspend fun delete(pendingUpload: PendingUploadFile) {
        uploaded -= pendingUpload
        deleteCalls += pendingUpload
    }

    override suspend fun markUploaded(pendingUpload: PendingUploadFile) {
        uploaded += pendingUpload
        markUploadedCalls += pendingUpload
    }

    override suspend fun isUploaded(pendingUpload: PendingUploadFile): Boolean {
        isUploadedFailure?.let { throw it }
        return pendingUpload in uploaded
    }

    override suspend fun <T> persist(
        createdPendingUploads: Collection<PendingUploadFile>,
        removedPendingUploads: Collection<PendingUploadFile>,
        block: suspend () -> T,
    ): T = block()
}

internal suspend fun OutgoingContent.asText(): String = when (this) {
    is OutgoingContent.ByteArrayContent -> bytes().decodeToString()
    is MultiPartFormDataContent -> readString()
    is OutgoingContent.WriteChannelContent -> readString()
    is OutgoingContent.ReadChannelContent -> readFrom().readRemaining().readByteArray().decodeToString()
    is OutgoingContent.NoContent -> ""
    else -> error("Unsupported request body type: ${this::class}")
}

private suspend fun MultiPartFormDataContent.readString(): String =
    (this as OutgoingContent.WriteChannelContent).readString()

private suspend fun OutgoingContent.WriteChannelContent.readString(): String {
    val channel = ByteChannel()
    writeTo(channel)
    channel.close()
    return channel.readRemaining().readByteArray().decodeToString()
}

private fun String.extractMultipartFilename(): String? {
    val disposition = lineSequence()
        .firstOrNull { it.startsWith("Content-Disposition: ") }
        ?.removePrefix("Content-Disposition: ")
        ?: return null
    return ContentDisposition
        .parse(disposition)
        .parameter(ContentDisposition.Parameters.FileName)
}

internal inline fun withTempUploadFile(
    content: String,
    block: (File, PendingUploadFile) -> Unit,
) {
    val file = Files.createTempFile("keyguard-sync-v2-upload", ".bin").toFile()
    try {
        file.writeText(content)
        block(
            file,
            PendingUploadFile(
                path = file.absolutePath,
                plainSize = content.length.toLong(),
                encryptedSize = file.length(),
            ),
        )
    } finally {
        file.delete()
    }
}

internal fun testCipher(
    localId: String,
    remoteId: String,
    localRevisionDate: Instant,
    remoteRevisionDate: Instant,
    attachments: List<BitwardenCipher.Attachment>,
    remoteEntity: BitwardenCipher? = null,
) = BitwardenCipher(
    accountId = ACCOUNT_ID,
    cipherId = localId,
    revisionDate = localRevisionDate,
    service = BitwardenService(
        remote = BitwardenService.Remote(
            id = remoteId,
            revisionDate = remoteRevisionDate,
            deletedDate = null,
        ),
        version = BitwardenService.VERSION,
    ),
    remoteEntity = remoteEntity,
    keyBase64 = "cipher-key",
    name = "Cipher",
    notes = "",
    favorite = false,
    attachments = attachments,
    reprompt = BitwardenCipher.RepromptType.None,
    type = BitwardenCipher.Type.SecureNote,
    secureNote = BitwardenCipher.SecureNote(),
)

internal fun testCipherEntity(
    id: String,
    attachments: List<AttachmentEntity> = emptyList(),
) = CipherEntity(
    id = id,
    revisionDate = T0,
    type = CipherTypeEntity.SecureNote,
    name = "Cipher",
    notes = "",
    attachments = attachments,
)

internal fun BitwardenCipher.toEncryptedCipherEntity(
    crypto: BitwardenCrImpl,
    base64Service: Base64Service,
): CipherEntity {
    val globalCrypto =
        crypto.cta(
            env =
                BitwardenCrCta.BitwardenCrCtaEnv(
                    key =
                        if (organizationId != null) {
                            BitwardenCrKey.OrganizationToken(organizationId)
                        } else {
                            BitwardenCrKey.UserToken
                        },
                ),
            mode = BitwardenCrCta.Mode.ENCRYPT,
        )
    val itemKey = keyBase64?.let(base64Service::decode)
    val itemCrypto =
        if (itemKey != null) {
            crypto.cta(
                env =
                    BitwardenCrCta.BitwardenCrCtaEnv(
                        key =
                            BitwardenCrKey.CryptoKey(
                                symmetricCryptoKey = CryptoKey.decodeSymmetricOrThrow(itemKey),
                            ),
                    ),
                mode = BitwardenCrCta.Mode.ENCRYPT,
            )
        } else {
            globalCrypto
        }
    val encrypted = transform(
        itemCrypto = itemCrypto,
        globalCrypto = globalCrypto,
    )
    return CipherEntity(
        id = requireNotNull(encrypted.service.remote?.id),
        organizationId = encrypted.organizationId,
        collectionIds = encrypted.collectionIds.toList(),
        revisionDate = encrypted.service.remote.revisionDate,
        type = CipherTypeEntity.of(encrypted.type),
        key = encrypted.keyBase64,
        name = encrypted.name,
        notes = encrypted.notes,
        favorite = encrypted.favorite,
        login = encrypted.login?.let { login ->
            LoginEntity(
                uris = login.uris.map { uri ->
                    LoginUriEntity(
                        uri = uri.uri,
                        uriChecksum = uri.uriChecksumBase64,
                        match = uri.match?.let(UriMatchTypeEntity::of),
                    )
                },
                username = login.username,
                password = login.password,
                passwordRevisionDate = login.passwordRevisionDate,
                totp = login.totp,
            )
        },
        fields = encrypted.fields.map { field ->
            FieldEntity(
                type = FieldTypeEntity.of(field.type),
                name = field.name,
                value = field.value,
                linkedId = field.linkedId?.let(LinkedIdTypeEntity::of),
            )
        },
        attachments =
            encrypted.attachments
                .filterIsInstance<BitwardenCipher.Attachment.Remote>()
                .map {
                    AttachmentEntity(
                        id = it.id,
                        url = it.url,
                        fileName = it.fileName,
                        key = requireNotNull(it.keyBase64),
                        size = it.size.toString(),
                        sizeName = "${it.size} B",
                    )
                },
        deletedDate = encrypted.deletedDate,
        passwordHistory = encrypted.passwordHistory.map { history ->
            PasswordHistoryEntity(
                password = history.password,
                lastUsedDate = history.lastUsedDate,
            )
        },
        encryptedFor = encrypted.encryptedFor,
        archivedDate = encrypted.archivedDate,
    )
}

internal fun testAttachmentEntity(
    id: String,
    fileName: String = "cipher.bin",
    key: String = "attachment-key",
    size: Long,
) = AttachmentEntity(
    id = id,
    url = "https://vault.example.com/attachments/$id",
    fileName = fileName,
    key = key,
    size = size.toString(),
    sizeName = "$size B",
)

internal fun AttachmentEntity.toLocalRemoteAttachment() =
    BitwardenCipher.Attachment.Remote(
        id = id,
        url = url,
        fileName = fileName,
        keyBase64 = key,
        size = size.toLong(),
    )

internal fun testSend(
    localId: String,
    remoteId: String?,
    localRevisionDate: Instant,
    remoteRevisionDate: Instant?,
    file: BitwardenSend.File,
) = BitwardenSend(
    accountId = ACCOUNT_ID,
    sendId = localId,
    accessId = "access-$localId",
    revisionDate = localRevisionDate,
    createdDate = T0,
    deletedDate = T4,
    service = BitwardenService(
        remote = remoteId?.let {
            BitwardenService.Remote(
                id = it,
                revisionDate = requireNotNull(remoteRevisionDate),
                deletedDate = null,
            )
        },
        version = BitwardenService.VERSION,
    ),
    authType = BitwardenSend.AuthType.None,
    keyBase64 = "send-key",
    name = "Send",
    notes = "File send",
    accessCount = 0,
    type = BitwardenSend.Type.File,
    file = file,
)

internal fun testSendFile(
    id: String,
    fileName: String = "send.bin",
    pendingUpload: PendingUploadFile,
) = BitwardenSend.File(
    id = id,
    fileName = fileName,
    size = pendingUpload.encryptedSize,
    pendingUpload = pendingUpload,
)

internal fun testSendEntity(
    id: String,
    key: String = "send-key",
    name: String = "Send",
    notes: String? = "File send",
    file: SendFileEntity?,
) = SendEntity(
    id = id,
    accessId = "access-$id",
    key = key,
    type = SendTypeEntity.File,
    name = name,
    notes = notes,
    file = file,
    accessCount = 0,
    revisionDate = T0,
    deletionDate = T4,
    disabled = false,
)

internal fun testSendFileEntity(
    id: String,
    fileName: String = "send.bin",
    size: Long,
) = SendFileEntity(
    id = id,
    fileName = fileName,
    size = size.toString(),
    sizeName = "$size B",
)

internal fun SendEntity.toLocalSend(localId: String) = BitwardenSend(
    accountId = ACCOUNT_ID,
    sendId = localId,
    accessId = accessId,
    revisionDate = revisionDate,
    createdDate = creationDate,
    deletedDate = deletionDate,
    expirationDate = expirationDate,
    service = BitwardenService(
        remote = BitwardenService.Remote(
            id = id,
            revisionDate = revisionDate,
            deletedDate = null,
        ),
        version = BitwardenService.VERSION,
    ),
    authType = BitwardenSend.AuthType.None,
    keyBase64 = key,
    name = name,
    notes = notes,
    accessCount = accessCount,
    type = BitwardenSend.Type.File,
    file = file?.let {
        BitwardenSend.File(
            id = requireNotNull(it.id),
            fileName = requireNotNull(it.fileName),
            size = it.size?.toLong(),
            sizeName = it.sizeName,
            pendingUpload = null,
        )
    },
)

internal fun createUploadTestDatabase(): Database {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    Database.Schema.create(driver)
    return createUploadTestDatabase(driver)
}

internal fun createUploadTestDatabase(
    driver: JdbcSqliteDriver,
): Database {
    val json = UploadTestServer.json
    return Database(
        driver = driver,
        cipherUsageHistoryAdapter = CipherUsageHistory.Adapter(InstantToLongAdapter),
        sshUsageHistoryAdapter = SshUsageHistory.Adapter(
            requestAdapter = SshUsageHistoryRequestTypeToLongAdapter,
            responseAdapter = SshUsageHistoryResponseTypeToLongAdapter,
            createdAtAdapter = InstantToLongAdapter,
        ),
        cipherFilterAdapter = CipherFilter.Adapter(
            updatedAtAdapter = InstantToLongAdapter,
            createdAtAdapter = InstantToLongAdapter,
        ),
        generatorHistoryAdapter = GeneratorHistory.Adapter(InstantToLongAdapter),
        generatorWordlistAdapter = GeneratorWordlist.Adapter(InstantToLongAdapter),
        generatorEmailRelayAdapter = GeneratorEmailRelay.Adapter(InstantToLongAdapter),
        urlBlockAdapter = UrlBlock.Adapter(InstantToLongAdapter),
        urlOverrideAdapter = UrlOverride.Adapter(InstantToLongAdapter),
        cipherAdapter = Cipher.Adapter(
            updatedAtAdapter = InstantToLongAdapter,
            data_Adapter = ObjectToStringAdapter<BitwardenCipher>(json),
        ),
        sendAdapter = DbSend.Adapter(ObjectToStringAdapter<BitwardenSend>(json)),
        collectionAdapter = DbCollection.Adapter(ObjectToStringAdapter<BitwardenCollection>(json)),
        equivalentDomainsAdapter = EquivalentDomains.Adapter(ObjectToStringAdapter<BitwardenEquivalentDomain>(json)),
        folderAdapter = Folder.Adapter(ObjectToStringAdapter<BitwardenFolder>(json)),
        metaAdapter = Meta.Adapter(ObjectToStringAdapter<BitwardenMeta>(json)),
        organizationAdapter = Organization.Adapter(ObjectToStringAdapter<BitwardenOrganization>(json)),
        profileAdapter = Profile.Adapter(ObjectToStringAdapter<BitwardenProfile>(json)),
        accountAdapter = Account.Adapter(ObjectToStringAdapter<ServiceToken>(json)),
        passwordBreachAdapter = PasswordBreach.Adapter(
            updatedAtAdapter = InstantToLongAdapter,
        ),
        accountBreachAdapter = AccountBreach.Adapter(
            updatedAtAdapter = InstantToLongAdapter,
            data_Adapter = ObjectToStringAdapter<HibpBreachGroup>(json),
        ),
        watchtowerThreatAdapter = WatchtowerThreat.Adapter(
            reportedAtAdapter = InstantToLongAdapter,
        ),
        privilegedAppAdapter = PrivilegedApp.Adapter(
            createdAtAdapter = InstantToLongAdapter,
        ),
    )
}

internal fun parseCipherAttachmentPath(path: String): Pair<String, String> {
    val parts = path.removePrefix("/api/ciphers/").split("/")
    return parts[0] to parts[2]
}

internal fun parseCipherAttachmentDataPath(path: String): Pair<String, String> {
    val parts = path.removePrefix("/api/ciphers/").split("/")
    return parts[0] to parts[2]
}

internal fun parseSendFilePath(path: String): Pair<String, String> {
    val parts = path.removePrefix("/api/sends/").split("/")
    return parts[0] to parts[2]
}

internal fun createUploadTestCrypto(
    cryptoGenerator: CryptoGeneratorJvm,
    base64Service: Base64ServiceJvm,
) = BitwardenCrImpl(
    cipherEncryptor = CipherEncryptorImpl(
        cryptoGenerator = cryptoGenerator,
        base64Service = base64Service,
    ),
    cryptoGenerator = cryptoGenerator,
    base64Service = base64Service,
).apply {
    appendProfileToken2(
        keyData = ByteArray(64) { index -> (index + 11).toByte() },
        privateKey = ByteArray(1),
    )
}

internal fun createUploadTestUserAndProfile(
    server: UploadTestServer,
    cipherEncryptor: CipherEncryptor,
    base64Service: Base64ServiceJvm,
): Pair<BitwardenToken, ProfileEntity> {
    val encKey = ByteArray(32) { index -> (index + 41).toByte() }
    val macKey = ByteArray(32) { index -> (index + 73).toByte() }
    val profileKey = ByteArray(64) { index -> (index + 101).toByte() }
    val privateKey = ByteArray(1)
    val authKey = SymmetricCryptoKey2(encKey + macKey)
    val profileSymmetricKey = SymmetricCryptoKey2(profileKey)
    val profileKeyCipherText =
        cipherEncryptor.encode2(
            cipherType = CipherEncryptor.Type.AesCbc256_HmacSha256_B64,
            plainText = profileKey,
            symmetricCryptoKey = authKey,
            asymmetricCryptoKey = null,
        )
    val privateKeyCipherText =
        cipherEncryptor.encode2(
            cipherType = CipherEncryptor.Type.AesCbc256_HmacSha256_B64,
            plainText = privateKey,
            symmetricCryptoKey = profileSymmetricKey,
            asymmetricCryptoKey = null,
        )
    val user =
        BitwardenToken(
            id = ACCOUNT_ID,
            key =
                BitwardenToken.Key(
                    masterKeyBase64 = "",
                    passwordKeyBase64 = "",
                    encryptionKeyBase64 = base64Service.encodeToString(encKey),
                    macKeyBase64 = base64Service.encodeToString(macKey),
                ),
            token =
                BitwardenToken.Token(
                    refreshToken = "refresh-token",
                    accessToken = server.token,
                    expirationDate = Instant.parse("2099-01-01T00:00:00Z"),
                ),
            user = BitwardenToken.User(email = "user@example.com"),
            env = BitwardenToken.Environment.of(server.env),
        )
    val profile =
        ProfileEntity(
            culture = "en-US",
            email = "user@example.com",
            emailVerified = true,
            id = "profile-1",
            key = profileKeyCipherText,
            privateKey = privateKeyCipherText,
            obj = "profile",
            organizations = emptyList(),
            premium = true,
            securityStamp = "security-stamp-1",
            twoFactorEnabled = false,
            name = "Test User",
        )
    return user to profile
}

internal class UploadTestVaultDatabaseManager(
    private val database: Database,
) : VaultDatabaseManager {
    override fun get() = io(database)

    override fun <T> mutate(
        tag: String,
        block: suspend (Database) -> T,
    ) = ioEffect {
        block(database)
    }

    override fun changePassword(newMasterKey: MasterKey) = io(Unit)
}

internal object UploadTestWatchdog : Watchdog {
    override fun <T> track(
        accountIdSet: Set<AccountId>,
        accountTask: AccountTask,
        io: suspend () -> T,
    ) = io
}

internal object UploadTestPasswordStrength : GetPasswordStrength {
    override fun invoke(password: String) = io(
        PasswordStrength(
            crackTimeSeconds = 1_000_000L,
            version = 1L,
        ),
    )
}

internal object UploadTestLogRepository : LogRepository {
    override suspend fun add(
        tag: String,
        message: String,
        level: LogLevel,
    ) = Unit
}

internal object UploadTestBase64Service : Base64Service {
    override fun encode(bytes: ByteArray): ByteArray = bytes

    override fun decode(bytes: ByteArray): ByteArray = bytes
}

internal object UploadTestCryptoGenerator : CryptoGenerator {
    override fun hkdf(
        seed: ByteArray,
        salt: ByteArray?,
        info: ByteArray?,
        length: Int,
    ): ByteArray = error("unused")

    override fun pbkdf2(
        seed: ByteArray,
        salt: ByteArray,
        iterations: Int,
        length: Int,
    ): ByteArray = error("unused")

    override fun argon2(
        mode: Argon2Mode,
        seed: ByteArray,
        salt: ByteArray,
        iterations: Int,
        memoryKb: Int,
        parallelism: Int,
    ): ByteArray = error("unused")

    override fun seed(length: Int): ByteArray = "generated-key".encodeToByteArray()

    override fun hmac(
        key: ByteArray,
        data: ByteArray,
        algorithm: CryptoHashAlgorithm,
    ): ByteArray = error("unused")

    override fun hashSha1(data: ByteArray): ByteArray = error("unused")

    override fun hashSha256(data: ByteArray): ByteArray = error("unused")

    override fun hashMd5(data: ByteArray): ByteArray = error("unused")

    override fun uuid(): String = "generated-uuid"

    override fun random(): Int = 4

    override fun random(range: IntRange): Int = range.first
}
