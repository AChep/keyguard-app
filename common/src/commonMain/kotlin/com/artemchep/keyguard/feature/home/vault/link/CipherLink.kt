package com.artemchep.keyguard.feature.home.vault.link

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.cipherlink.CipherLink
import com.artemchep.keyguard.common.service.cipherlink.canonicalizeCipherLinks

data class CipherLinkTarget(
    val link: CipherLink,
    val cipher: DSecret,
)

data class CipherRelations(
    /**
     * Targets linked by this cipher, with `null` placeholders for links that
     * can not be resolved.
     */
    val outgoingTargets: List<DSecret?>,
    /**
     * Ciphers of the same account that link to this one. Such a cipher is
     * always resolved, otherwise we would not know about the relation, and
     * appears once no matter how many times it links here.
     */
    val incomingSources: List<DSecret>,
)

/**
 * Returns the ciphers of the given account that a link may point at, keyed by
 * the canonical remote id of a [CipherLink]. A cipher that sits in the trash,
 * belongs to another account, or was never synced can not be a target.
 */
fun cipherLinkTargetsByRemoteId(
    ciphers: List<DSecret>,
    accountId: String?,
    excludedCipherId: String? = null,
): Map<String, CipherLinkTarget> = ciphers
    .asSequence()
    .filter { cipher ->
        cipher.accountId == accountId &&
                cipher.id != excludedCipherId &&
                cipher.deletedDate == null
    }
    .mapNotNull { cipher ->
        val link = cipher.service.remote?.id
            ?.let(CipherLink::of)
            ?: return@mapNotNull null
        CipherLinkTarget(
            link = link,
            cipher = cipher,
        )
    }
    .associateBy { target -> target.link.remoteCipherId }

fun resolveCipherRelations(
    cipher: DSecret,
    ciphers: List<DSecret>,
): CipherRelations {
    // Both a target and a source must be a live cipher of the same account,
    // and neither of them may be the cipher itself.
    val accountCiphers = ciphers
        .filter { candidate ->
            candidate.accountId == cipher.accountId &&
                    candidate.id != cipher.id &&
                    candidate.deletedDate == null
        }
    val targetsByRemoteId = cipherLinkTargetsByRemoteId(
        ciphers = accountCiphers,
        accountId = cipher.accountId,
    )

    val outgoingTargets = canonicalizeCipherLinks(
        cipher.links.map(DSecret.Link::remoteCipherId),
    ).map { link ->
        targetsByRemoteId[link.remoteCipherId]?.cipher
    }

    val currentRemoteId = cipher.service.remote?.id
        ?.let(CipherLink::of)
        ?.remoteCipherId
    val incomingSources = if (currentRemoteId == null) {
        emptyList()
    } else {
        accountCiphers
            .filter { source ->
                source.links.any { link ->
                    CipherLink.of(link.remoteCipherId)?.remoteCipherId == currentRemoteId
                }
            }
    }

    return CipherRelations(
        outgoingTargets = outgoingTargets,
        incomingSources = incomingSources,
    )
}
