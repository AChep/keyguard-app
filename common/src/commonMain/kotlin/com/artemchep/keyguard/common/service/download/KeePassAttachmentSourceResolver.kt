package com.artemchep.keyguard.common.service.download

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.DownloadAttachmentRequestData
import com.artemchep.keyguard.common.service.keepass.KeePassUtil
import com.artemchep.keyguard.common.service.keepass.parseAttachmentUrl
import com.artemchep.keyguard.common.service.text.Base32Service
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.KeePassToken
import com.artemchep.keyguard.provider.bitwarden.repository.BitwardenCipherRepository
import com.artemchep.keyguard.provider.bitwarden.repository.ServiceTokenRepository

internal interface KeePassAttachmentSourceResolver {
    suspend fun resolve(
        request: DownloadAttachmentRequestData,
        source: DownloadAttachmentRequestData.KeePassSource,
    ): ResolvedKeePassAttachmentSource
}

internal class ResolvedKeePassAttachmentSource(
    val token: KeePassToken,
    val contentHash: ByteArray,
    val expectedSize: Long,
) : AutoCloseable {
    override fun close() {
        contentHash.fill(0)
    }
}

internal class KeePassAttachmentSourceResolverImpl(
    private val tokenRepository: ServiceTokenRepository,
    private val cipherRepository: BitwardenCipherRepository,
    private val base32Service: Base32Service,
) : KeePassAttachmentSourceResolver {
    override suspend fun resolve(
        request: DownloadAttachmentRequestData,
        source: DownloadAttachmentRequestData.KeePassSource,
    ): ResolvedKeePassAttachmentSource {
        require(request.encryptionKey == null) {
            "KeePass attachment sources must not have a download encryption key."
        }
        val cipher = cipherRepository
            .getById(request.localCipherId)
            .bind()
        requireNotNull(cipher) {
            "Could not find the KeePass item for this attachment."
        }
        require(cipher.service.remote?.id == request.remoteCipherId) {
            "KeePass attachment item revision no longer matches."
        }
        val attachment = cipher.attachments
            .asSequence()
            .filterIsInstance<BitwardenCipher.Attachment.Remote>()
            .firstOrNull { attachment -> attachment.id == request.attachmentId }
            ?: error("Could not find the current KeePass attachment.")
        require(attachment.url == source.hashRef) {
            "KeePass attachment reference changed before download."
        }
        source.expectedSize?.let { expectedSize ->
            require(attachment.size == expectedSize) {
                "KeePass attachment size changed before download."
            }
        }

        val token = tokenRepository
            .getById(AccountId(cipher.accountId))
            .bind()
        require(token is KeePassToken) {
            "KeePass attachment source belongs to a non-KeePass account."
        }
        val contentHash = KeePassUtil.parseAttachmentUrl(
            url = source.hashRef,
            base32Service = base32Service,
        )
        return ResolvedKeePassAttachmentSource(
            token = token,
            contentHash = contentHash,
            expectedSize = attachment.size,
        )
    }
}
