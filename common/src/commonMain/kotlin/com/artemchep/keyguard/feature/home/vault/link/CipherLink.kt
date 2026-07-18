package com.artemchep.keyguard.feature.home.vault.link

import com.artemchep.keyguard.common.model.DSecret
import kotlin.uuid.Uuid

class CipherLink private constructor(
    val remoteCipherId: String,
) {
    override fun toString(): String = "$SCHEME_PREFIX$remoteCipherId"

    companion object {
        const val SCHEME_PREFIX = "keyguard://cipher/"

        fun of(remoteCipherId: String): CipherLink? = kotlin.runCatching {
            CipherLink(Uuid.parse(remoteCipherId).toString())
        }.getOrNull()

        fun parse(value: String?): CipherLink? {
            val normalizedValue = value?.trim().orEmpty()
            if (!normalizedValue.startsWith(SCHEME_PREFIX)) {
                return null
            }
            val remoteCipherId = normalizedValue.removePrefix(SCHEME_PREFIX)
            if (
                remoteCipherId.isEmpty() ||
                remoteCipherId.any { it == '/' || it == '?' || it == '#' }
            ) {
                return null
            }
            return of(remoteCipherId)
        }
    }
}

data class CipherRelation(
    val fieldIndex: Int,
    val label: String,
    val link: CipherLink,
    val cipher: DSecret?,
)

data class CipherRelations(
    val outgoing: List<CipherRelation>,
    val incoming: List<CipherRelation>,
)

fun resolveCipherRelations(
    cipher: DSecret,
    ciphers: List<DSecret>,
): CipherRelations {
    val accountCiphers = ciphers
        .asSequence()
        .filter { it.accountId == cipher.accountId && it.deletedDate == null }
        .toList()
    val targetsByRemoteId = accountCiphers
        .asSequence()
        .filter { it.id != cipher.id }
        .mapNotNull { target ->
            target.service.remote?.id
                ?.let(CipherLink::of)
                ?.remoteCipherId
                ?.let { it to target }
        }
        .toMap()

    val outgoing = cipher.fields.mapIndexedNotNull { fieldIndex, field ->
        if (field.type != DSecret.Field.Type.Text) {
            return@mapIndexedNotNull null
        }
        val link = CipherLink.parse(field.value)
            ?: return@mapIndexedNotNull null
        CipherRelation(
            fieldIndex = fieldIndex,
            label = field.name.orEmpty(),
            link = link,
            cipher = targetsByRemoteId[link.remoteCipherId],
        )
    }

    val currentRemoteId = cipher.service.remote?.id
        ?.let(CipherLink::of)
        ?.remoteCipherId
    val incoming = if (currentRemoteId == null) {
        emptyList()
    } else {
        accountCiphers
            .asSequence()
            .filter { it.id != cipher.id }
            .flatMap { source ->
                source.fields
                    .asSequence()
                    .mapIndexedNotNull { fieldIndex, field ->
                        if (field.type != DSecret.Field.Type.Text) {
                            return@mapIndexedNotNull null
                        }
                        val link = CipherLink.parse(field.value)
                            ?.takeIf { it.remoteCipherId == currentRemoteId }
                            ?: return@mapIndexedNotNull null
                        CipherRelation(
                            fieldIndex = fieldIndex,
                            label = field.name.orEmpty(),
                            link = link,
                            cipher = source,
                        )
                    }
            }
            .toList()
    }

    return CipherRelations(
        outgoing = outgoing,
        incoming = incoming,
    )
}
