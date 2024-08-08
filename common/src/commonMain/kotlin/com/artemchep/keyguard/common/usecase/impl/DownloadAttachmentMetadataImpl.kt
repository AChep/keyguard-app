package com.artemchep.keyguard.common.usecase.impl

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
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.usecase.DownloadAttachmentMetadata
import com.artemchep.keyguard.core.store.DatabaseManager
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
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
import com.artemchep.keyguard.provider.bitwarden.repository.BitwardenTokenRepository
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
    private val tokenRepository: BitwardenTokenRepository,
    private val cipherRepository: BitwardenCipherRepository,
    private val profileRepository: BitwardenProfileRepository,
    private val organizationRepository: BitwardenOrganizationRepository,
    private val databaseManager: DatabaseManager,
    private val cipherEncryptor: CipherEncryptor,
    private val cryptoGenerator: CryptoGenerator,
    private val base64Service: Base64Service,
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
        base64Service = directDI.instance(),
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
                url = data.url,
                urlIsOneTime = data.urlIsOneTime,
                name = data.name,
                encryptionKey = data.encryptionKey,
            )
        }

    private class AttachmentData(
        val url: String,
        val urlIsOneTime: Boolean,
        val name: String,
        val encryptionKey: ByteArray,
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
            AttachmentData(
                url = requireNotNull(model.url),
                urlIsOneTime = true,
                name = model.fileName,
                encryptionKey = base64Service.decode(model.keyBase64),
            )
        }.getOrElse {
            // TODO: Throw properly!
            if (it is UnknownHostException) {
                throw it
            }
            it.printStackTrace()
            AttachmentData(
                url = requireNotNull(attachment.url),
                urlIsOneTime = false,
                name = attachment.fileName,
                encryptionKey = base64Service.decode(attachment.keyBase64),
            )
        }
    }
}
