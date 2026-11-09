package com.artemchep.keyguard.feature.passkeyhybrid.presentation

enum class HybridPhase { SCAN, PERMISSION, CONNECTING, WAITING, PICK, SAVE, VERIFY, SENDING, SUCCESS, ERROR }

enum class HybridError { QR, PERMISSION, NO_CREDENTIALS, WRITE, EXCLUDED, CONNECTION }

/** What the nearby browser asked for. Known once its request arrives. */
enum class HybridOperation { SIGN_IN, CREATE }

/** Display metadata and identifiers only. Keys and the scanned QR never enter screen state. */
data class HybridCandidate(
    val accountId: String,
    val cipherId: String,
    val credentialId: String,
    val title: String,
    val username: String,
)

data class HybridSessionState(
    val phase: HybridPhase = HybridPhase.SCAN,
    val error: HybridError? = null,
    val rpId: String? = null,
    val username: String? = null,
    val operation: HybridOperation? = null,
    val candidates: List<HybridCandidate> = emptyList(),
    val onScan: ((String) -> Unit)? = null,
    val onPermission: ((Boolean) -> Unit)? = null,
    val onPick: ((HybridCandidate) -> Unit)? = null,
    val onVerified: (() -> Unit)? = null,
    val onSaveTarget: ((HybridSaveTarget) -> Unit)? = null,
)
