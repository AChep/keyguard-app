package com.artemchep.keyguard.common.service.sshagent.bookmarks.impl

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.agent.isEligibleForAgent
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.crypto.SshCertificateParser
import com.artemchep.keyguard.common.service.sshagent.bookmarks.SshBookmarkCertificate
import com.artemchep.keyguard.common.service.sshagent.bookmarks.SshBookmarkEntry
import com.artemchep.keyguard.common.service.sshagent.bookmarks.SshBookmarkIdentity
import com.artemchep.keyguard.common.service.sshagent.bookmarks.SshBookmarks
import com.artemchep.keyguard.common.service.sshagent.bookmarks.SshBookmarksSource
import com.artemchep.keyguard.common.service.sshagent.bookmarks.parseSshBookmark
import com.artemchep.keyguard.common.service.sshagent.isSshUserCertificateOf
import com.artemchep.keyguard.common.service.sshagent.parseSshAgentPublicKeyMaterial
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetSshAgentCiphers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * Reads the bookmarks from the `ssh://` fields of the SSH key items, each
 * with the key of its item if the agent offers it.
 */
class SshBookmarksSourceImpl(
    private val getCiphers: GetCiphers,
    private val getSshAgentCiphers: GetSshAgentCiphers,
    private val sshCertificateParser: SshCertificateParser,
    private val cryptoGenerator: CryptoGenerator,
    private val base64Service: Base64Service,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : SshBookmarksSource {
    override fun get(): Flow<SshBookmarks> = combine(
        getCiphers()
            .map(::findBookmarkedCiphers)
            .distinctUntilChanged(),
        getSshAgentCiphers()
            .map { ciphers -> ciphers.mapTo(mutableSetOf()) { it.id } }
            .distinctUntilChanged(),
    ) { bookmarkedCiphers, servedCipherIds ->
        // Only the keys that the agent offers can be pinned.
        val bookmarkedCipherIds = bookmarkedCiphers.mapTo(mutableSetOf()) { it.id }
        bookmarkedCiphers to servedCipherIds.intersect(bookmarkedCipherIds)
    }
        // Parse the keys only when the bookmarked items change.
        .distinctUntilChanged()
        .map { (bookmarkedCiphers, servedCipherIds) ->
            mapCiphersToBookmarks(
                ciphers = bookmarkedCiphers,
                servedCipherIds = servedCipherIds,
            )
        }
        .distinctUntilChanged()
        .flowOn(defaultDispatcher)

    /** The items with bookmarks. */
    private fun findBookmarkedCiphers(
        ciphers: List<DSecret>,
    ): List<DSecret> = ciphers.filter { cipher ->
        // Items in the trash or the archive keep their bookmarks
        // quietly, like the agent keeps quiet about their keys.
        cipher.isEligibleForAgent() &&
            !cipher.sshKey?.bookmarks.isNullOrEmpty()
    }

    private fun mapCiphersToBookmarks(
        ciphers: List<DSecret>,
        servedCipherIds: Set<String>,
    ): SshBookmarks {
        var skipped = 0
        val entries = mutableListOf<SshBookmarkEntry>()
        ciphers.forEach { cipher ->
            val bookmarks = cipher.sshKey?.bookmarks.orEmpty()
            val identity = cipher
                .takeIf { it.id in servedCipherIds }
                ?.let(::createIdentity)
            bookmarks.forEach { value ->
                val bookmark = parseSshBookmark(value.url)
                if (bookmark == null || identity == null) {
                    skipped += 1
                    return@forEach
                }
                entries += SshBookmarkEntry(
                    bookmark = bookmark,
                    identity = identity,
                    sortKey = cipher.name + "\u0000" + cipher.id,
                )
            }
        }
        return SshBookmarks(
            entries = entries,
            skipped = skipped,
        )
    }

    private fun createIdentity(cipher: DSecret): SshBookmarkIdentity? {
        val publicKey = cipher.sshKey
            ?.publicKey
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return parseSshAgentPublicKeyMaterial(
            publicKey = publicKey,
            cryptoGenerator = cryptoGenerator,
            base64Service = base64Service,
        )?.let { material ->
            SshBookmarkIdentity(
                keySha256 = material.publicKeyBlobSha256,
                publicKey = material.publicKey,
                certificate = createCertificate(
                    certificate = cipher.sshKey?.certificate,
                    publicKey = publicKey,
                ),
            )
        }
    }

    /**
     * The [certificate] if it is a user certificate of the key. Like
     * the agent, leaves the validity window to the server.
     */
    private fun createCertificate(
        certificate: String?,
        publicKey: String,
    ): SshBookmarkCertificate? = certificate
        ?.trim()
        ?.takeIf {
            isSshUserCertificateOf(
                certificate = it,
                publicKey = publicKey,
                parser = sshCertificateParser,
            )
        }
        ?.let {
            parseSshAgentPublicKeyMaterial(
                publicKey = it,
                cryptoGenerator = cryptoGenerator,
                base64Service = base64Service,
            )
        }
        ?.let { material ->
            SshBookmarkCertificate(
                sha256 = material.publicKeyBlobSha256,
                certificate = material.publicKey,
            )
        }
}
