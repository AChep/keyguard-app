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
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.usecase.DownloadAttachmentMetadata
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.BitwardenToken
import com.artemchep.keyguard.provider.bitwarden.api.builder.api
import com.artemchep.keyguard.provider.bitwarden.api.builder.get
import com.artemchep.keyguard.provider.bitwarden.crypto.BitwardenCrCta
import com.artemchep.keyguard.provider.bitwarden.crypto.BitwardenCrImpl
import com.artemchep.keyguard.provider.bitwarden.crypto.BitwardenCrKey
import com.artemchep.keyguard.provider.bitwarden.crypto.CryptoKey
import com.artemchep.keyguard.provider.bitwarden.crypto.appendOrganizationToken2
import com.artemchep.keyguard.provider.bitwarden.crypto.appendProfileToken2
import com.artemchep.keyguard.provider.bitwarden.crypto.appendUserToken
import com.artemchep.keyguard.provider.bitwarden.crypto.decodeSymmetricOrThrow
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

internal class AttachmentMetadataData(
    val source: DownloadAttachmentRequestData.Source,
    val name: String,
    /**
     * Encryption key, if the attachment needs to be decrypted,
     * `null` otherwise.
     */
    val encryptionKey: ByteArray? = null,
)

/**
 * Resolves attachment metadata (download URL + file encryption key) for
 * Bitwarden accounts. This is the multiplatform subset of the JVM
 * [DownloadAttachmentMetadataImpl2], which additionally supports KeePass
 * vaults; platforms without KeePass support use this implementation directly.
 */
class DownloadAttachmentMetadataBitwardenImpl(
    private val tokenRepository: ServiceTokenRepository,
    private val cipherRepository: BitwardenCipherRepository,
    private val profileRepository: BitwardenProfileRepository,
    private val organizationRepository: BitwardenOrganizationRepository,
    private val databaseManager: VaultDatabaseManager,
    private val cipherEncryptor: CipherEncryptor,
    private val cryptoGenerator: CryptoGenerator,
    private val base64Service: Base64Service,
    private val json: Json,
    private val httpClient: HttpClient,
    /** Lets the JVM caller preserve its UnknownHostException rethrow. */
    private val shouldRethrow: (Throwable) -> Boolean = { false },
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
    ): IO<DownloadAttachmentRequestData> = when (request) {
        is DownloadAttachmentRequest.ByLocalCipherAttachment -> getLatestAttachmentData(
            localCipherId = request.localCipherId,
            remoteCipherId = request.remoteCipherId,
            attachmentId = request.attachmentId,
        )
            .map { data ->
                DownloadAttachmentRequestData(
                    localCipherId = request.localCipherId,
                    remoteCipherId = request.remoteCipherId,
                    attachmentId = request.attachmentId,
                    // data
                    source = data.source,
                    name = data.name,
                    encryptionKey = data.encryptionKey,
                )
            }
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
        require(token is BitwardenToken) {
            "Attachment metadata resolution is only supported for Bitwarden accounts."
        }

        getLatestAttachmentDataBitwarden(
            remoteCipherId = remoteCipherId,
            attachmentId = attachmentId,
            token = token,
            cipher = cipher,
            attachment = attachment,
        ).bind()
    }

    internal fun getLatestAttachmentDataBitwarden(
        remoteCipherId: String,
        attachmentId: String,
        // pre-loaded
        token: BitwardenToken,
        cipher: BitwardenCipher,
        attachment: BitwardenCipher.Attachment.Remote,
    ): IO<AttachmentMetadataData> = ioEffect {
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
        val globalCta = cr.cta(env, BitwardenCrCta.Mode.DECRYPT)
        val itemCta = cipher.keyBase64
            ?.let(base64Service::decode)
            ?.let(CryptoKey.Companion::decodeSymmetricOrThrow)
            ?.let { symmetricCryptoKey ->
                val cryptoKey = BitwardenCrKey.CryptoKey(
                    symmetricCryptoKey = symmetricCryptoKey,
                )
                val cryptoKeyEnv = BitwardenCrCta.BitwardenCrCtaEnv(
                    key = cryptoKey,
                    encryptionType = envEncryptionType,
                )
                cr.cta(
                    env = cryptoKeyEnv,
                    mode = BitwardenCrCta.Mode.DECRYPT,
                )
            }
        val attachmentCtas = itemCta
            ?.let { listOf(it, globalCta) }
            ?: listOf(globalCta)

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
            val encryptedAttachment = BitwardenCipher.Attachment
                .encrypted(attachment = entity)
            val model = BitwardenCipher.Attachment
                .decryptMetadata(
                    attachment = encryptedAttachment,
                    cryptoCandidates = attachmentCtas,
                )
            val source = DownloadAttachmentRequestData.UrlSource(
                url = requireNotNull(model.url),
                urlIsOneTime = true,
            )
            AttachmentMetadataData(
                source = source,
                name = model.fileName,
                encryptionKey = model.keyBase64?.let(base64Service::decode),
            )
        }.getOrElse {
            // TODO: Throw properly!
            if (shouldRethrow(it)) {
                throw it
            }
            it.printStackTrace()
            val source = DownloadAttachmentRequestData.UrlSource(
                url = requireNotNull(attachment.url),
                urlIsOneTime = false,
            )
            AttachmentMetadataData(
                source = source,
                name = attachment.fileName,
                encryptionKey = attachment.keyBase64?.let(base64Service::decode),
            )
        }
    }
}

internal fun BitwardenCipher.Attachment.Companion.decryptMetadata(
    attachment: BitwardenCipher.Attachment.Remote,
    cryptoCandidates: List<BitwardenCrCta>,
): BitwardenCipher.Attachment.Remote {
    var lastError: Throwable? = null
    cryptoCandidates.forEach { crypto ->
        runCatching {
            return attachment.transform(crypto = crypto)
        }.onFailure { e ->
            lastError = e
        }
    }
    throw lastError ?: IllegalStateException("Could not decrypt attachment metadata.")
}
