package com.artemchep.keyguard.feature.auth.keyguard

import com.artemchep.keyguard.core.store.bitwarden.P2pNetwork

/** Who a joining device pairs as: its name, and how it reaches the other devices. */
data class KeyguardPairingIdentity(
    val label: String,
    val network: P2pNetwork,
)
