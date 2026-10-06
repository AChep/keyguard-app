package com.artemchep.keyguard.util.io.artifact

import kotlin.uuid.Uuid

// Kotlin mirror of the native lease-aware naming, used by golden vectors and
// sweep tests. Production lease-aware artifacts are created and swept by the
// native implementation, which holds the protocol's required lease for the
// artifact lifetime.

/**
 * Coordination protocol encoded in a temporary artifact name.
 *
 * Readers must understand a protocol before writers are allowed to emit it.
 */
enum class TemporaryArtifactProtocol {
    /**
     * The writer holds a lease on the staged data file.
     */
    FileLeaseV1,

    /**
     * The writer holds a shared lease on the containing directory.
     */
    DirectoryLeaseV1,

    /**
     * The data entry is paired with a locked, owner-only sidecar.
     */
    SidecarLeaseV1,
}

/**
 * Entry represented by a parsed temporary-artifact name.
 */
enum class TemporaryArtifactEntryKind {
    Data,
    Lease,
}

/**
 * A strictly parsed temporary-artifact name.
 */
data class TemporaryArtifactName(
    val protocol: TemporaryArtifactProtocol,
    val role: TemporaryArtifactRole,
    val nonce: String,
    val entryKind: TemporaryArtifactEntryKind,
)

/**
 * Constructs a native lease-aware artifact name.
 */
internal fun leaseAwareTemporaryArtifactName(
    protocol: TemporaryArtifactProtocol,
    role: TemporaryArtifactRole,
    nonce: String,
): String {
    require(nonce.isCanonicalVersion4Uuid()) {
        "Temporary artifact nonce must be a canonical RFC 9562 version-4 UUID."
    }
    val protocolToken = when (protocol) {
        TemporaryArtifactProtocol.FileLeaseV1 -> FILE_LEASE_V1_TOKEN
        TemporaryArtifactProtocol.DirectoryLeaseV1 -> DIRECTORY_LEASE_V1_TOKEN
        TemporaryArtifactProtocol.SidecarLeaseV1 -> SIDECAR_LEASE_V1_TOKEN
    }
    return "$KEYGUARD_TEMPORARY_ARTIFACT_PREFIX$protocolToken${role.token}-$nonce.tmp"
}

/**
 * Generates a native file-lease artifact name for sweep tests.
 */
internal fun newFileLeaseTemporaryArtifactName(
    role: TemporaryArtifactRole,
): String = leaseAwareTemporaryArtifactName(
    protocol = TemporaryArtifactProtocol.FileLeaseV1,
    role = role,
    nonce = Uuid.random().toString(),
)

/**
 * Parses a canonical version-1 lease-aware temporary-artifact name.
 *
 * Uncoordinated and unknown names stay in the reserved namespace but are not
 * returned as native sweep candidates.
 */
fun parseTemporaryArtifactName(
    name: String,
): TemporaryArtifactName? =
    name.removePrefixOrNull(KEYGUARD_TEMPORARY_ARTIFACT_PREFIX)
        ?.parseTemporaryArtifactBody()

private fun String.parseTemporaryArtifactBody(): TemporaryArtifactName? {
    val (protocol, protocolBody) = when {
        startsWith(FILE_LEASE_V1_TOKEN) ->
            TemporaryArtifactProtocol.FileLeaseV1 to
                removePrefix(FILE_LEASE_V1_TOKEN)

        startsWith(DIRECTORY_LEASE_V1_TOKEN) ->
            TemporaryArtifactProtocol.DirectoryLeaseV1 to
                removePrefix(DIRECTORY_LEASE_V1_TOKEN)

        startsWith(SIDECAR_LEASE_V1_TOKEN) ->
            TemporaryArtifactProtocol.SidecarLeaseV1 to
                removePrefix(SIDECAR_LEASE_V1_TOKEN)

        else -> return null
    }
    val entryKind = when {
        protocolBody.endsWith(DATA_SUFFIX) -> TemporaryArtifactEntryKind.Data

        protocol == TemporaryArtifactProtocol.SidecarLeaseV1 &&
            protocolBody.endsWith(LEASE_SUFFIX) -> TemporaryArtifactEntryKind.Lease

        else -> null
    }
    return entryKind?.let { kind ->
        val suffix = when (kind) {
            TemporaryArtifactEntryKind.Data -> DATA_SUFFIX
            TemporaryArtifactEntryKind.Lease -> LEASE_SUFFIX
        }
        protocolBody
            .removeSuffix(suffix)
            .parseTemporaryArtifactPayload()
            ?.takeIf { payload -> payload.nonce.isCanonicalVersion4Uuid() }
            ?.let { payload ->
                TemporaryArtifactName(
                    protocol = protocol,
                    role = payload.role,
                    nonce = payload.nonce,
                    entryKind = kind,
                )
            }
    }
}

private fun String.parseTemporaryArtifactPayload(): TemporaryArtifactPayload? {
    val separator = indexOf('-')
    val role = takeIf { separator == ROLE_SEPARATOR_INDEX }
        ?.substring(startIndex = 0, endIndex = separator)
        ?.let { roleToken ->
            TemporaryArtifactRole.entries
                .singleOrNull { role -> role.token == roleToken }
        }
    return role?.let {
        TemporaryArtifactPayload(
            role = it,
            nonce = substring(separator + 1),
        )
    }
}

private data class TemporaryArtifactPayload(
    val role: TemporaryArtifactRole,
    val nonce: String,
)

private const val FILE_LEASE_V1_TOKEN = "v1f-"
private const val DIRECTORY_LEASE_V1_TOKEN = "v1d-"
private const val SIDECAR_LEASE_V1_TOKEN = "v1s-"
private const val DATA_SUFFIX = ".tmp"
private const val LEASE_SUFFIX = ".lease"
private const val ROLE_SEPARATOR_INDEX = 1

private fun String.removePrefixOrNull(
    prefix: String,
): String? = takeIf { startsWith(prefix) }?.removePrefix(prefix)
