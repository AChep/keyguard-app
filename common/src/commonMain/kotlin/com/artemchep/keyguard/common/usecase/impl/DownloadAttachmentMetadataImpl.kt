package com.artemchep.keyguard.common.usecase.impl

import app.keemobile.kotpass.database.BinaryIndex
import app.keemobile.kotpass.database.modifiers.binaries
import com.artemchep.keyguard.common.exception.isUnknownHostException
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.DownloadAttachmentRequest
import com.artemchep.keyguard.common.model.DownloadAttachmentRequestData
import com.artemchep.keyguard.common.service.crypto.CipherEncryptor
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.common.service.keepass.KeePassUtil
import com.artemchep.keyguard.common.service.keepass.openKeePassDatabase
import com.artemchep.keyguard.common.service.keepass.parseAttachmentUrl
import com.artemchep.keyguard.common.service.webdav.KtorWebDavClientFactory
import com.artemchep.keyguard.common.service.text.Base32Service
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.usecase.DownloadAttachmentMetadata
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.BitwardenToken
import com.artemchep.keyguard.core.store.bitwarden.KeePassToken
import com.artemchep.keyguard.provider.bitwarden.repository.BitwardenCipherRepository
import com.artemchep.keyguard.provider.bitwarden.repository.BitwardenOrganizationRepository
import com.artemchep.keyguard.provider.bitwarden.repository.BitwardenProfileRepository
import com.artemchep.keyguard.provider.bitwarden.repository.ServiceTokenRepository
import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class DownloadAttachmentMetadataImpl2(
    private val tokenRepository: ServiceTokenRepository,
    private val cipherRepository: BitwardenCipherRepository,
    private val profileRepository: BitwardenProfileRepository,
    private val organizationRepository: BitwardenOrganizationRepository,
    private val databaseManager: VaultDatabaseManager,
    private val cipherEncryptor: CipherEncryptor,
    private val cryptoGenerator: CryptoGenerator,
    private val base32Service: Base32Service,
    private val base64Service: Base64Service,
    private val fileService: FileService,
    private val json: Json,
    private val httpClient: HttpClient,
) : DownloadAttachmentMetadata {
    constructor(directDI: DirectDI) : this(
        tokenRepository = directDI.instance(),
        cipherRepository = directDI.instance(),
        profileRepository = directDI.instance(),
        organizationRepository = directDI.instance(),
        databaseManager = directDI.instance(),
        cipherEncryptor = directDI.instance(),
        cryptoGenerator = directDI.instance(),
        base32Service = directDI.instance(),
        base64Service = directDI.instance(),
        fileService = directDI.instance(),
        json = directDI.instance(),
        httpClient = directDI.instance(),
    )

    private val bitwardenDelegate = DownloadAttachmentMetadataBitwardenImpl(
        tokenRepository = tokenRepository,
        cipherRepository = cipherRepository,
        profileRepository = profileRepository,
        organizationRepository = organizationRepository,
        databaseManager = databaseManager,
        cipherEncryptor = cipherEncryptor,
        cryptoGenerator = cryptoGenerator,
        base64Service = base64Service,
        json = json,
        httpClient = httpClient,
        shouldRethrow = { it.isUnknownHostException() },
    )

    override fun invoke(
        request: DownloadAttachmentRequest,
    ): IO<DownloadAttachmentRequestData> = request
        .foo()

    private fun DownloadAttachmentRequest.foo(): IO<DownloadAttachmentRequestData> = when (this) {
        is DownloadAttachmentRequest.ByLocalCipherAttachment -> foo()
    }

    private fun DownloadAttachmentRequest.ByLocalCipherAttachment.foo() = getLatestAttachmentData(
        localCipherId = localCipherId,
        remoteCipherId = remoteCipherId,
        attachmentId = attachmentId,
    )
        .map { data ->
            DownloadAttachmentRequestData(
                localCipherId = localCipherId,
                remoteCipherId = remoteCipherId,
                attachmentId = attachmentId,
                // data
                source = data.source,
                name = data.name,
                encryptionKey = data.encryptionKey,
            )
        }

    private fun getLatestAttachmentData(
        localCipherId: String,
        remoteCipherId: String?,
        attachmentId: String,
    ): IO<AttachmentMetadataData> = ioEffect {
        val cipher = cipherRepository.getById(id = localCipherId).bind()
        requireNotNull(cipher)
        requireNotNull(remoteCipherId) // can only get attachment info from remote cipher
        // Check if actual remote cipher ID matches given
        // remote cipher ID.
        require(cipher.service.remote?.id == remoteCipherId)

        val attachment = cipher.attachments
            .asSequence()
            .mapNotNull { it as? BitwardenCipher.Attachment.Remote }
            .firstOrNull { it.id == attachmentId }
        requireNotNull(attachment)

        val accountId = AccountId(cipher.accountId)
        val token = tokenRepository.getById(id = accountId).bind()
        requireNotNull(token)

        when (token) {
            is KeePassToken -> getLatestAttachmentDataKeePass(
                token = token,
                attachment = attachment,
            )

            is BitwardenToken -> bitwardenDelegate.getLatestAttachmentDataBitwarden(
                remoteCipherId = remoteCipherId,
                attachmentId = attachmentId,
                token = token,
                cipher = cipher,
                attachment = attachment,
            )
        }.bind()
    }

    private fun getLatestAttachmentDataKeePass(
        token: KeePassToken,
        attachment: BitwardenCipher.Attachment.Remote,
    ): IO<AttachmentMetadataData> = ioEffect {
        val keePassDb = openKeePassDatabase(
            token = token,
            fileService = fileService,
            base64Service = base64Service,
            webDavClientFactory = KtorWebDavClientFactory(
                httpClient = httpClient,
            ),
        )

        val hash = KeePassUtil.parseAttachmentUrl(
            url = requireNotNull(attachment.url),
            base32Service = base32Service,
        )

        val data = BinaryIndex(keePassDb.binaries)
            .findByContentSha256(hash)
            ?.data
            // failed to find the attachment data
            ?: kotlin.run {
                val msg = "Could not requested attachment data!"
                throw RuntimeException(msg)
            }
        val source = DownloadAttachmentRequestData.DirectSource(
            data = data.getContent(),
        )
        AttachmentMetadataData(
            source = source,
            name = attachment.fileName,
        )
    }
}
