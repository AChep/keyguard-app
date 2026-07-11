package com.artemchep.keyguard.common.service.agent

import com.artemchep.keyguard.common.util.toHex

/** Common shape of the caller identity attached to agent (SSH/GPG) requests. */
interface AgentCallerIdentity {
    val pid: Int
    val processName: String
    val executablePath: String
    val appName: String

    /** Package, bundle, or application path; provenance depends on the transport. */
    val appBundlePath: String
    val authorization: AgentCallerAuthorization?
}

/** Wire-level authorization evidence shared by the SSH and GPG agent protocols. */
interface AgentCallerAuthorization {
    val connectionFingerprint: ByteArray
    val subjects: List<AgentCallerAuthorizationSubject>
    val authorizationContextFingerprint: ByteArray
}

/** One independently verified candidate that an app-side cache policy may select. */
interface AgentCallerAuthorizationSubject {
    val kind: Int
    val evidenceSource: Int
    val fingerprint: ByteArray
}

/**
 * Chooses the widest caller subject an approval is allowed to reuse.
 *
 * [ApplicationAndTerminalSession] is the secure default: terminal and IDE
 * children are isolated to one tab/session, while non-terminal callers and
 * helpers reuse the verified owning-application subject.
 */
enum class AgentApprovalCachePolicy(
    val storageKey: String,
) {
    Connection("connection"),
    Process("process"),
    Application("application"),
    ApplicationAndTerminalSession("application_and_terminal_session"),
    ;

    companion object {
        val Default = ApplicationAndTerminalSession

        /**
         * Decodes a persisted policy without treating an unrecognized value as
         * the product default. A missing value means the user has not chosen a
         * policy yet, while a present but unknown value may have been corrupted
         * or written by an incompatible build and therefore fails closed.
         */
        fun fromStorageKey(value: String?): AgentApprovalCachePolicy = when (value) {
            null -> Default
            else -> entries.firstOrNull { it.storageKey == value }
                ?: Connection
        }
    }
}

/**
 * Validated identity used by approval caches.
 *
 * Display metadata such as an app name, icon, or executable path is
 * intentionally not represented here, so it cannot become authorization
 * state. Equality includes the verified protocol context independently of the
 * selected subject.
 */
data class AgentApprovalCacheIdentity(
    val cacheSubject: CacheSubject,
    val authorizationContextFingerprintHex: String?,
) {
    data class CacheSubject(
        val kind: Kind,
        val fingerprintHex: String,
        val evidenceSource: EvidenceSource,
    ) {
        enum class Kind {
            Connection,
            Process,
            ApplicationInstance,
            StableApplication,
            TerminalSession,
        }
    }

    val cacheScope: CacheSubject.Kind
        get() = cacheSubject.kind

    val evidenceSource: EvidenceSource
        get() = cacheSubject.evidenceSource

    enum class EvidenceSource {
        ConnectionRandom,
        LinuxPidfd,
        LinuxLsm,
        MacosAuditToken,
        MacosCodeSigning,
        WindowsNamedPipeToken,
        WindowsPackagedApp,
        WindowsTcpOwner,
        AndroidFrameworkPackage,
        LinuxApplicationAncestry,
        LinuxTerminalSession,
        MacosApplicationAncestry,
        MacosTerminalSession,
    }
}

object AgentCallerAuthorizationSchema {
    const val FINGERPRINT_SIZE = 32
    const val MAX_SUBJECT_COUNT = 4

    object SubjectKind {
        const val UNSPECIFIED = 0
        const val PROCESS = 1
        const val APPLICATION_INSTANCE = 2
        const val STABLE_APPLICATION = 3
        const val TERMINAL_SESSION = 4
    }

    object EvidenceSource {
        const val UNSPECIFIED = 0
        const val CONNECTION_RANDOM = 1
        const val LINUX_PIDFD = 2
        const val LINUX_LSM = 3
        const val MACOS_AUDIT_TOKEN = 4
        const val MACOS_CODE_SIGNING = 5
        const val WINDOWS_NAMED_PIPE_TOKEN = 6
        const val WINDOWS_PACKAGED_APP = 7
        const val WINDOWS_TCP_OWNER = 8
        const val ANDROID_FRAMEWORK_PACKAGE = 9
        const val LINUX_APPLICATION_ANCESTRY = 10
        const val LINUX_TERMINAL_SESSION = 11
        const val MACOS_APPLICATION_ANCESTRY = 12
        const val MACOS_TERMINAL_SESSION = 13
    }
}

/**
 * Parses untrusted wire evidence and selects a cache-safe subject.
 *
 * Missing or malformed identities, duplicate subject kinds or fingerprints,
 * unknown evidence, and source/kind mismatches all return null. Callers must
 * then require a fresh approval and must not construct a metadata-based key.
 */
fun AgentCallerIdentity?.toApprovalCacheIdentity(
    policy: AgentApprovalCachePolicy = AgentApprovalCachePolicy.Default,
): AgentApprovalCacheIdentity? {
    val authorization = this?.authorization
        ?: return null
    if (authorization.connectionFingerprint.size != AgentCallerAuthorizationSchema.FINGERPRINT_SIZE) {
        return null
    }
    if (
        authorization.authorizationContextFingerprint.isNotEmpty() &&
        authorization.authorizationContextFingerprint.size != AgentCallerAuthorizationSchema.FINGERPRINT_SIZE
    ) {
        return null
    }
    if (authorization.subjects.size > AgentCallerAuthorizationSchema.MAX_SUBJECT_COUNT) {
        return null
    }

    val connectionFingerprintHex = authorization.connectionFingerprint.toHex()
    val fingerprints = mutableSetOf(connectionFingerprintHex)
    val subjects = mutableMapOf<AgentApprovalCacheIdentity.CacheSubject.Kind, AgentApprovalCacheIdentity.CacheSubject>()
    for (wireSubject in authorization.subjects) {
        val kind = wireSubject.kind.toValidatedSubjectKind()
            ?: return null
        val evidenceSource = wireSubject.evidenceSource.toValidatedEvidenceSource()
            ?: return null
        if (!evidenceSource.proves(kind)) {
            return null
        }
        if (wireSubject.fingerprint.size != AgentCallerAuthorizationSchema.FINGERPRINT_SIZE) {
            return null
        }
        val fingerprintHex = wireSubject.fingerprint.toHex()
        if (!fingerprints.add(fingerprintHex)) {
            return null
        }
        val subject = AgentApprovalCacheIdentity.CacheSubject(
            kind = kind,
            fingerprintHex = fingerprintHex,
            evidenceSource = evidenceSource,
        )
        if (subjects.put(kind, subject) != null) {
            return null
        }
    }

    val connectionSubject = AgentApprovalCacheIdentity.CacheSubject(
        kind = AgentApprovalCacheIdentity.CacheSubject.Kind.Connection,
        fingerprintHex = connectionFingerprintHex,
        evidenceSource = AgentApprovalCacheIdentity.EvidenceSource.ConnectionRandom,
    )
    val selectedSubject = policy.selectionOrder
        .firstNotNullOfOrNull(subjects::get)
        ?: connectionSubject
    return AgentApprovalCacheIdentity(
        cacheSubject = selectedSubject,
        authorizationContextFingerprintHex = authorization.authorizationContextFingerprint
            .takeIf(ByteArray::isNotEmpty)
            ?.toHex(),
    )
}

private val AgentApprovalCachePolicy.selectionOrder
    get() = when (this) {
        AgentApprovalCachePolicy.Connection -> emptyList()
        AgentApprovalCachePolicy.Process -> listOf(
            AgentApprovalCacheIdentity.CacheSubject.Kind.Process,
        )
        AgentApprovalCachePolicy.Application -> listOf(
            AgentApprovalCacheIdentity.CacheSubject.Kind.StableApplication,
            AgentApprovalCacheIdentity.CacheSubject.Kind.ApplicationInstance,
            AgentApprovalCacheIdentity.CacheSubject.Kind.TerminalSession,
            AgentApprovalCacheIdentity.CacheSubject.Kind.Process,
        )
        AgentApprovalCachePolicy.ApplicationAndTerminalSession -> listOf(
            AgentApprovalCacheIdentity.CacheSubject.Kind.TerminalSession,
            AgentApprovalCacheIdentity.CacheSubject.Kind.StableApplication,
            AgentApprovalCacheIdentity.CacheSubject.Kind.ApplicationInstance,
            AgentApprovalCacheIdentity.CacheSubject.Kind.Process,
        )
    }

private fun Int.toValidatedSubjectKind(): AgentApprovalCacheIdentity.CacheSubject.Kind? =
    when (this) {
        AgentCallerAuthorizationSchema.SubjectKind.PROCESS ->
            AgentApprovalCacheIdentity.CacheSubject.Kind.Process
        AgentCallerAuthorizationSchema.SubjectKind.APPLICATION_INSTANCE ->
            AgentApprovalCacheIdentity.CacheSubject.Kind.ApplicationInstance
        AgentCallerAuthorizationSchema.SubjectKind.STABLE_APPLICATION ->
            AgentApprovalCacheIdentity.CacheSubject.Kind.StableApplication
        AgentCallerAuthorizationSchema.SubjectKind.TERMINAL_SESSION ->
            AgentApprovalCacheIdentity.CacheSubject.Kind.TerminalSession
        else -> null
    }

private fun Int.toValidatedEvidenceSource(): AgentApprovalCacheIdentity.EvidenceSource? =
    when (this) {
        AgentCallerAuthorizationSchema.EvidenceSource.LINUX_PIDFD ->
            AgentApprovalCacheIdentity.EvidenceSource.LinuxPidfd
        AgentCallerAuthorizationSchema.EvidenceSource.LINUX_LSM ->
            AgentApprovalCacheIdentity.EvidenceSource.LinuxLsm
        AgentCallerAuthorizationSchema.EvidenceSource.MACOS_AUDIT_TOKEN ->
            AgentApprovalCacheIdentity.EvidenceSource.MacosAuditToken
        AgentCallerAuthorizationSchema.EvidenceSource.MACOS_CODE_SIGNING ->
            AgentApprovalCacheIdentity.EvidenceSource.MacosCodeSigning
        // Named-pipe PIDs and package names are not yet bound to the actual
        // client I/O. Keep the wire values reserved, but reject them until the
        // Windows transport supplies an authenticated client token/handle.
        AgentCallerAuthorizationSchema.EvidenceSource.WINDOWS_NAMED_PIPE_TOKEN,
        AgentCallerAuthorizationSchema.EvidenceSource.WINDOWS_PACKAGED_APP,
        AgentCallerAuthorizationSchema.EvidenceSource.WINDOWS_TCP_OWNER,
        AgentCallerAuthorizationSchema.EvidenceSource.CONNECTION_RANDOM,
        -> null
        AgentCallerAuthorizationSchema.EvidenceSource.ANDROID_FRAMEWORK_PACKAGE ->
            AgentApprovalCacheIdentity.EvidenceSource.AndroidFrameworkPackage
        AgentCallerAuthorizationSchema.EvidenceSource.LINUX_APPLICATION_ANCESTRY ->
            AgentApprovalCacheIdentity.EvidenceSource.LinuxApplicationAncestry
        AgentCallerAuthorizationSchema.EvidenceSource.LINUX_TERMINAL_SESSION ->
            AgentApprovalCacheIdentity.EvidenceSource.LinuxTerminalSession
        AgentCallerAuthorizationSchema.EvidenceSource.MACOS_APPLICATION_ANCESTRY ->
            AgentApprovalCacheIdentity.EvidenceSource.MacosApplicationAncestry
        AgentCallerAuthorizationSchema.EvidenceSource.MACOS_TERMINAL_SESSION ->
            AgentApprovalCacheIdentity.EvidenceSource.MacosTerminalSession
        else -> null
    }

private fun AgentApprovalCacheIdentity.EvidenceSource.proves(
    kind: AgentApprovalCacheIdentity.CacheSubject.Kind,
): Boolean = when (this) {
    AgentApprovalCacheIdentity.EvidenceSource.LinuxPidfd,
    AgentApprovalCacheIdentity.EvidenceSource.MacosAuditToken,
    -> kind == AgentApprovalCacheIdentity.CacheSubject.Kind.Process
    AgentApprovalCacheIdentity.EvidenceSource.LinuxLsm,
    AgentApprovalCacheIdentity.EvidenceSource.MacosCodeSigning,
    AgentApprovalCacheIdentity.EvidenceSource.AndroidFrameworkPackage,
    -> kind == AgentApprovalCacheIdentity.CacheSubject.Kind.StableApplication
    AgentApprovalCacheIdentity.EvidenceSource.LinuxApplicationAncestry,
    AgentApprovalCacheIdentity.EvidenceSource.MacosApplicationAncestry,
    -> kind == AgentApprovalCacheIdentity.CacheSubject.Kind.ApplicationInstance
    AgentApprovalCacheIdentity.EvidenceSource.LinuxTerminalSession,
    AgentApprovalCacheIdentity.EvidenceSource.MacosTerminalSession,
    -> kind == AgentApprovalCacheIdentity.CacheSubject.Kind.TerminalSession
    AgentApprovalCacheIdentity.EvidenceSource.ConnectionRandom,
    AgentApprovalCacheIdentity.EvidenceSource.WindowsNamedPipeToken,
    AgentApprovalCacheIdentity.EvidenceSource.WindowsPackagedApp,
    AgentApprovalCacheIdentity.EvidenceSource.WindowsTcpOwner,
    -> false
}
