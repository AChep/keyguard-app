package com.artemchep.keyguard.common.usecase.impl

import app.keemobile.kotpass.database.modifiers.binaries
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.io.toIO
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.DownloadAttachmentRequest
import com.artemchep.keyguard.common.model.DownloadAttachmentRequestData
import com.artemchep.keyguard.common.service.crypto.CipherEncryptor
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.common.service.keepass.KeePassUtil
import com.artemchep.keyguard.common.service.keepass.openKeePassDatabase
import com.artemchep.keyguard.common.service.keepass.parseAttachmentUrl
import com.artemchep.keyguard.common.service.text.Base32Service
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.usecase.DownloadAttachmentMetadata
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.BitwardenToken
import com.artemchep.keyguard.core.store.bitwarden.KeePassToken
import com.artemchep.keyguard.provider.bitwarden.api.builder.api
import com.artemchep.keyguard.provider.bitwarden.api.builder.get
import com.artemchep.keyguard.provider.bitwarden.crypto.BitwardenCrCta
import com.artemchep.keyguard.provider.bitwarden.crypto.BitwardenCrImpl
import com.artemchep.keyguard.provider.bitwarden.crypto.BitwardenCrKey
import com.artemchep.keyguard.provider.bitwarden.crypto.appendOrganizationToken2
import com.artemchep.keyguard.provider.bitwarden.crypto.appendProfileToken2
import com.artemchep.keyguard.provider.bitwarden.crypto.appendUserToken
import com.artemchep.keyguard.provider.bitwarden.crypto.encrypted
import com.artemchep.keyguard.provider.bitwarden.crypto.transform
import com.artemchep.keyguard.provider.bitwarden.repository.BitwardenCipherRepository
import com.artemchep.keyguard.provider.bitwarden.repository.BitwardenOrganizationRepository
import com.artemchep.keyguard.provider.bitwarden.repository.BitwardenProfileRepository
import com.artemchep.keyguard.provider.bitwarden.repository.ServiceTokenRepository
import com.artemchep.keyguard.provider.bitwarden.usecase.util.withRefreshableAccessToken
import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json
import org.kodein.di.DirectDI
import org.kodein.di.instance
import java.net.UnknownHostException

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

    private class AttachmentData(
        val source: DownloadAttachmentRequestData.Source,
        val name: String,
        /**
         * Encryption key, if the attachment needs to be decrypted,,
         * `null` otherwise.
         */
        val encryptionKey: ByteArray? = null,
    )

    private fun getLatestAttachmentData(
        localCipherId: String,
        remoteCipherId: String?,
        attachmentId: String,
    ): IO<AttachmentData> = ioEffect {
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
                localCipherId = localCipherId,
                remoteCipherId = remoteCipherId,
                attachmentId = attachmentId,
                token = token,
                attachment = attachment,
            )

            is BitwardenToken -> getLatestAttachmentDataBitwarden(
                localCipherId = localCipherId,
                remoteCipherId = remoteCipherId,
                attachmentId = attachmentId,
                token = token,
                cipher = cipher,
                attachment = attachment,
            )
        }.bind()
    }

    private fun getLatestAttachmentDataKeePass(
        localCipherId: String,
        remoteCipherId: String?,
        attachmentId: String,
        // pre-loaded
        token: KeePassToken,
        attachment: BitwardenCipher.Attachment.Remote,
    ): IO<AttachmentData> = ioEffect {
        val keePassDb = openKeePassDatabase(
            token = token,
            fileService = fileService,
            base64Service = base64Service,
        )

        val hash = KeePassUtil.parseAttachmentUrl(
            url = requireNotNull(attachment.url),
            base32Service = base32Service,
        )

        val data = keePassDb.binaries
            .entries
            .firstNotNullOfOrNull { entry ->
                val entryHash = entry.value.getContent()
                    .let(cryptoGenerator::hashSha256)
                entry.value.takeIf { entryHash.contentEquals(hash) }
            }
            // failed to find the attachment data
            ?: kotlin.run {
                val msg = "Could not requested attachment data!"
                throw RuntimeException(msg)
            }
        val source = DownloadAttachmentRequestData.DirectSource(
            data = data.rawContent,
        )
        AttachmentData(
            source = source,
            name = attachment.fileName,
        )
    }

    private fun getLatestAttachmentDataBitwarden(
        localCipherId: String,
        remoteCipherId: String,
        attachmentId: String,
        // pre-loaded
        token: BitwardenToken,
        cipher: BitwardenCipher,
        attachment: BitwardenCipher.Attachment.Remote,
    ): IO<AttachmentData> = ioEffect {
        val accountId = AccountId(token.id)
        val profile = profileRepository.getById(id = accountId).toIO().bind()
        requireNotNull(profile)
        val organizations = organizationRepository.getByAccountId(id = accountId).bind()

        // Build cryptography model.
        val builder = BitwardenCrImpl(
            cipherEncryptor = cipherEncryptor,
            cryptoGenerator = cryptoGenerator,
            base64Service = base64Service,
        ).apply {
            // We need user keys to decrypt the
            // profile key.
            appendUserToken(
                encKey = base64Service.decode(token.key.encryptionKeyBase64),
                macKey = base64Service.decode(token.key.macKeyBase64),
            )
            appendProfileToken2(
                keyData = base64Service.decode(profile.keyBase64),
                privateKey = base64Service.decode(profile.privateKeyBase64),
            )

            organizations.forEach { organization ->
                appendOrganizationToken2(
                    id = organization.organizationId,
                    keyData = base64Service.decode(organization.keyBase64),
                )
            }
        }
        val cr = builder.build()
        val envEncryptionType = CipherEncryptor.Type.AesCbc256_HmacSha256_B64
        val organizationId: String? = cipher.organizationId
        val env = if (organizationId != null) {
            val key = BitwardenCrKey.OrganizationToken(organizationId)
            BitwardenCrCta.BitwardenCrCtaEnv(
                key = key,
                encryptionType = envEncryptionType,
            )
        } else {
            val key = BitwardenCrKey.UserToken
            BitwardenCrCta.BitwardenCrCtaEnv(
                key = key,
                encryptionType = envEncryptionType,
            )
        }
        val cta = cr.cta(env, BitwardenCrCta.Mode.DECRYPT)

        //
        kotlin.runCatching {
            val entity = withRefreshableAccessToken(
                base64Service = base64Service,
                httpClient = httpClient,
                json = json,
                db = databaseManager,
                user = token,
            ) { latestUser ->
                val accessToken = requireNotNull(latestUser.token?.accessToken)
                latestUser.env.back().api
                    .ciphers.focus(id = remoteCipherId)
                    .attachments.focus(id = attachmentId)
                    .get(
                        httpClient = httpClient,
                        env = latestUser.env.back(),
                        token = accessToken,
                    )
            }
            val model = BitwardenCipher.Attachment
                .encrypted(attachment = entity)
                .transform(crypto = cta)
            val source = DownloadAttachmentRequestData.UrlSource(
                url = requireNotNull(model.url),
                urlIsOneTime = true,
            )
            AttachmentData(
                source = source,
                name = model.fileName,
                encryptionKey = model.keyBase64?.let(base64Service::decode),
            )
        }.getOrElse {
            // TODO: Throw properly!
            if (it is UnknownHostException) {
                throw it
            }
            it.printStackTrace()
            val source = DownloadAttachmentRequestData.UrlSource(
                url = requireNotNull(attachment.url),
                urlIsOneTime = false,
            )
            AttachmentData(
                source = source,
                name = attachment.fileName,
                encryptionKey = attachment.keyBase64?.let(base64Service::decode),
            )
        }
    }
}
