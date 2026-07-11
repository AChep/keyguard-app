package com.artemchep.keyguard.common.model

import androidx.compose.ui.graphics.painter.Painter

sealed interface LinkInfoAndroid : LinkInfo {
    val platform: LinkInfoPlatform.Android

    data class Installed(
        val label: String,
        val icon: Painter,
        val signingCertificates: SigningCertificates? = null,
        override val platform: LinkInfoPlatform.Android,
    ) : LinkInfoAndroid

    data class NotInstalled(
        override val platform: LinkInfoPlatform.Android,
    ) : LinkInfoAndroid

    data class SigningCertificates(
        val current: Set<String>,
        val history: Set<String>,
        val hasMultipleSigners: Boolean,
    )
}
