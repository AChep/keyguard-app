package com.artemchep.keyguard.common.service.imports.mapping

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.create.CreateRequest
import com.artemchep.keyguard.common.service.imports.ImportSkipReason
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.feature.environments.EnvironmentFormat
import com.artemchep.keyguard.feature.environments.EnvironmentPayload
import com.artemchep.keyguard.feature.environments.EnvironmentValueSource
import com.artemchep.keyguard.feature.environments.isWritable
import com.artemchep.keyguard.feature.environments.withoutReferences
import com.artemchep.keyguard.provider.bitwarden.crypto.BitwardenTransportFields
import com.artemchep.keyguard.provider.bitwarden.mapper.toDomain

/**
 * An item read back into a type only Keyguard has. Where a format has no such
 * type — the Bitwarden wire, Keyguard's own exports, CXF — Keyguard writes the
 * item as a secure note that carries it in custom fields.
 *
 * @property clearedReferenceCount The variables of the [environment] whose
 *   item reference was cleared, see [ImportSkipReason.EnvironmentReference].
 */
internal data class ImportedKeyguardType(
    val type: DSecret.Type,
    val fields: List<BitwardenCipher.Field>,
    val gpgKey: CreateRequest.GpgKey = CreateRequest.GpgKey(),
    val environment: EnvironmentPayload.Decoded? = null,
    val clearedReferenceCount: Int = 0,
)

/**
 * Reads the Keyguard type of an item of the given [type], or returns `null` for
 * an environment the vault would not write, see [ImportSkipReason.Environment].
 * Only a secure note can carry one; other types keep user fields with matching
 * names untouched.
 */
internal fun importedKeyguardTypeOrNull(
    type: BitwardenCipher.Type,
    fields: List<BitwardenCipher.Field>,
): ImportedKeyguardType? = importedKeyguardTypeOrNull(
    decoded = BitwardenTransportFields.decodeType(type, fields),
)

/**
 * The Keyguard type of an item whose transport fields [decoded] to it, see
 * [importedKeyguardTypeOrNull].
 */
internal fun importedKeyguardTypeOrNull(
    decoded: BitwardenTransportFields.Decoded,
): ImportedKeyguardType? {
    val environment = decoded.environment
    val gpgKey = decoded.gpgKey
    return when {
        environment != null -> importedEnvironmentOrNull(environment, decoded.fields)

        gpgKey != null -> ImportedKeyguardType(
            type = DSecret.Type.GpgKey,
            fields = decoded.fields,
            gpgKey = CreateRequest.GpgKey(
                privateKeyArmored = gpgKey.privateKeyArmored,
                publicKeyArmored = gpgKey.publicKeyArmored,
                fingerprint = gpgKey.fingerprint,
            ),
        )

        else -> ImportedKeyguardType(decoded.type.toDomain(), decoded.fields)
    }
}

private fun importedEnvironmentOrNull(
    environment: BitwardenCipher.Environment,
    fields: List<BitwardenCipher.Field>,
): ImportedKeyguardType? {
    // A reference holds a provider id of the source account, so it is cleared,
    // as when an environment is copied to another account. A document this
    // version can not fully read might hide one, so it is not imported at all.
    // Checked before the references are cleared too, as clearing them encodes
    // the document again, which throws for one larger than Keyguard writes.
    val decoded = environment.document?.let(EnvironmentFormat::decode)
    val payload = decoded
        ?.takeIf { it.isWritable() }
        .withoutReferences()
        ?.takeIf { it.isWritable() }
        ?: return null
    return ImportedKeyguardType(
        type = DSecret.Type.Environment,
        fields = fields,
        environment = payload,
        clearedReferenceCount = decoded?.definition?.variables
            ?.count { it.source is EnvironmentValueSource.Reference }
            ?: 0,
    )
}
