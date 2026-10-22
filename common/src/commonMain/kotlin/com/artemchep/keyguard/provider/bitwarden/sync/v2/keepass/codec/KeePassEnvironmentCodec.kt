package com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.codec

import app.keemobile.kotpass.models.Entry
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.feature.environments.EnvironmentFormat

/**
 * Parser contract for Keyguard environment custom fields. KDBX has no field
 * size limit and checks its own integrity, so the document is not split.
 *
 * Fields consumed or written:
 *
 * | KeePass field      | Direction | Parser use                               |
 * |--------------------|-----------|------------------------------------------|
 * | `environment_json` | both      | Environment document, written concealed. |
 */
internal class KeePassEnvironmentCodec {
    fun encode(environment: BitwardenCipher.Environment): List<KeePassFieldWrite> = buildList {
        addConcealed(KeePassFieldKey.ENVIRONMENT_JSON, environment.document)
    }

    /**
     * Returns `null` if the entry has no environment field, or a blank one: other KeePass
     * apps may clear it instead of removing it. The raw value is the document, with or
     * without protection: users may turn it off in other KeePass apps.
     */
    fun decode(scope: DecodeToCipherScope): BitwardenCipher.Environment? = scope
        .consumeFieldAndReturnContent(KeePassFieldKey.ENVIRONMENT_JSON)
        ?.takeIf { it.isNotBlank() }
        ?.let { document -> BitwardenCipher.Environment(document = document) }

    /** A user's own field of the same name must not turn a secure note into an environment. */
    fun detects(remote: Entry): Boolean = remote.fields[KeePassFieldKey.ENVIRONMENT_JSON]
        ?.content
        ?.let(EnvironmentFormat::decode) != null
}
