package com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.codec

import app.keemobile.kotpass.models.Entry
import com.artemchep.keyguard.common.service.cipherlink.CipherLinkFields
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher

/**
 * Parser contract for Keyguard cipher-link custom fields.
 *
 * Fields consumed or written:
 *
 * | KeePass field       | Direction | Parser use                                |
 * |---------------------|-----------|-------------------------------------------|
 * | `keyguard.link.<n>` | both      | `keyguard://cipher/<uuid>`, plain text.   |
 *
 * Decode consumes a field only when the ordinal is a positive integer and the
 * value parses as a canonical cipher link; anything else is left untouched, so
 * the orchestrator keeps it as a regular custom field. The decoded links are
 * ordered by their ordinal and duplicate targets collapse to their first
 * occurrence.
 */
internal class KeePassCipherLinkCodec {
    fun encode(links: List<BitwardenCipher.Link>): List<KeePassFieldWrite> = buildList {
        CipherLinkFields
            .format(links.map(BitwardenCipher.Link::remoteCipherId))
            .forEach { (key, value) -> addPlain(key, value) }
    }

    fun decode(
        scope: DecodeToCipherScope,
        remote: Entry,
    ): List<BitwardenCipher.Link> {
        val parsedFields = remote.fields
            .mapNotNull { (key, value) ->
                val field = CipherLinkFields.parse(
                    name = key,
                    value = value.content,
                )
                    ?: return@mapNotNull null
                key to field
            }
        parsedFields.forEach { (key, _) ->
            scope.consumeField(key)
        }
        return CipherLinkFields
            .decode(parsedFields.map { (_, field) -> field })
            .map { link ->
                BitwardenCipher.Link(
                    remoteCipherId = link.remoteCipherId,
                )
            }
    }
}
