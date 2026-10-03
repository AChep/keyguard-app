package com.artemchep.keyguard.util.io.artifact

import kotlin.uuid.Uuid

const val KEYGUARD_TEMPORARY_ARTIFACT_PREFIX = ".kg-tmp-"

enum class TemporaryArtifactRole(
    val token: String,
) {
    New("n"),
    Previous("o"),
    Scratch("s"),
}

fun temporaryArtifactName(
    role: TemporaryArtifactRole,
    nonce: String,
): String {
    require(nonce.isCanonicalVersion4Uuid()) {
        "Temporary artifact nonce must be a canonical RFC 9562 version-4 UUID."
    }
    return "$KEYGUARD_TEMPORARY_ARTIFACT_PREFIX$UNCOORDINATED_V1_TOKEN${role.token}-$nonce.tmp"
}

fun newTemporaryArtifactName(
    role: TemporaryArtifactRole,
): String = temporaryArtifactName(
    role = role,
    nonce = Uuid.random().toString(),
)

internal fun String.isCanonicalVersion4Uuid(): Boolean {
    val parsed = runCatching {
        Uuid.parse(this)
    }.getOrNull()
    return parsed != null &&
        parsed.toString() == this &&
        this[UUID_VERSION_INDEX] == '4' &&
        this[UUID_VARIANT_INDEX] in "89ab"
}

/**
 * Returns whether [name] belongs to Keyguard's reserved temporary namespace.
 *
 * Malformed and unknown future names remain reserved but are never assumed
 * safe to delete.
 */
fun isReservedTemporaryArtifactName(
    name: String,
): Boolean = name.startsWith(KEYGUARD_TEMPORARY_ARTIFACT_PREFIX)

private const val UNCOORDINATED_V1_TOKEN = "v1u-"
private const val UUID_VERSION_INDEX = 14
private const val UUID_VARIANT_INDEX = 19
