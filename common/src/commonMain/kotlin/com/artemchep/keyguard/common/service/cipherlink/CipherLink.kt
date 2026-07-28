package com.artemchep.keyguard.common.service.cipherlink

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

/**
 * Returns valid links with duplicate canonical remote ids removed while
 * preserving the order of their first occurrence.
 */
fun canonicalizeCipherLinks(
    remoteCipherIds: Iterable<String>,
): List<CipherLink> = remoteCipherIds
    .mapNotNull(CipherLink::of)
    .distinctBy(CipherLink::remoteCipherId)

fun canonicalizeCipherLinkIds(
    remoteCipherIds: Iterable<String>,
): List<String> = canonicalizeCipherLinks(remoteCipherIds)
    .map(CipherLink::remoteCipherId)
