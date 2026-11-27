package com.artemchep.keyguard.feature.secretscan

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FindInPage
import androidx.compose.ui.graphics.vector.ImageVector
import com.artemchep.keyguard.common.service.secretscan.SecretScanKind
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.icons.KeyguardSshKey
import org.jetbrains.compose.resources.StringResource

/** How a [SecretScanKind] is shown: on its screen and in the settings. */
internal class SecretScanKindUi(
    val title: StringResource,
    val icon: ImageVector,
    /** What the scan looks for. */
    val intro: StringResource,
    /** What an import does. */
    val introImport: StringResource,
    val searchTokens: List<String>,
)

internal val SecretScanKind.ui: SecretScanKindUi
    get() = when (this) {
        SecretScanKind.SshKey -> SecretScanKindUi(
            title = Res.string.secret_scan_ssh_title,
            icon = Icons.Outlined.KeyguardSshKey,
            intro = Res.string.secret_scan_ssh_intro,
            introImport = Res.string.secret_scan_ssh_intro_import,
            searchTokens = listOf("plaintext", "secret", "ssh", "key", "scan"),
        )

        SecretScanKind.DotEnv -> SecretScanKindUi(
            title = Res.string.secret_scan_dotenv_title,
            icon = Icons.Outlined.FindInPage,
            intro = Res.string.secret_scan_dotenv_intro,
            introImport = Res.string.secret_scan_dotenv_intro_import,
            searchTokens = listOf("plaintext", "secret", "env", ".env", "scan"),
        )
    }
