package com.artemchep.keyguard.feature.gpgkey

import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyInfo
import com.artemchep.keyguard.common.service.crypto.GpgPublicSubKeyInfo
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.gpg_key_capability_encrypt_decrypt
import com.artemchep.keyguard.res.gpg_keys_capability_sign

internal suspend fun TranslatorScope.translateGpgKeyCapabilities(
    canSign: Boolean,
    canEncrypt: Boolean,
): List<String> = buildList {
    if (canSign) {
        this += translate(Res.string.gpg_keys_capability_sign)
    }
    if (canEncrypt) {
        this += translate(Res.string.gpg_key_capability_encrypt_decrypt)
    }
}

internal fun GpgPublicKeyInfo.formatGpgAlgorithm(): String? = formatGpgAlgorithm(
    algorithm = algorithm,
    bitStrength = bitStrength,
)

internal fun GpgPublicSubKeyInfo.formatGpgAlgorithm(): String? = formatGpgAlgorithm(
    algorithm = algorithm,
    bitStrength = bitStrength,
)

internal fun formatGpgAlgorithm(
    algorithm: String?,
    bitStrength: Int?,
): String? {
    val values = listOfNotNull(
        algorithm?.takeIf { it.isNotBlank() },
        bitStrength?.let { "$it-bit" },
    )
    return values
        .joinToString(separator = " ")
        .takeIf { it.isNotBlank() }
}
