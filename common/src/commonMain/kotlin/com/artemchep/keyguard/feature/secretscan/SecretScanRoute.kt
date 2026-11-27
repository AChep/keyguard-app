package com.artemchep.keyguard.feature.secretscan

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.service.secretscan.SecretScanKind
import com.artemchep.keyguard.feature.navigation.Route

/**
 * Finds plaintext files of the [kind] on disk and imports them into the
 * vault. Opened only where a
 * [com.artemchep.keyguard.common.service.secretscan.PlaintextSecretScanner]
 * is bound.
 */
data class SecretScanRoute(
    val kind: SecretScanKind,
) : Route {
    @Composable
    override fun Content() {
        SecretScanScreen(
            kind = kind,
        )
    }
}
