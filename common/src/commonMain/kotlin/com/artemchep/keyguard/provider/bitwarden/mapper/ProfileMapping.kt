package com.artemchep.keyguard.provider.bitwarden.mapper

import androidx.compose.ui.graphics.Color
import com.artemchep.keyguard.common.model.DProfile
import com.artemchep.keyguard.common.util.RGBToHSL
import com.artemchep.keyguard.common.util.toColorInt
import com.artemchep.keyguard.core.store.bitwarden.BitwardenProfile
import com.artemchep.keyguard.ui.icons.generateAccentColors

fun BitwardenProfile.toDomain(
    accountHost: String,
): DProfile {
    val accentColor = run {
        val avatarColor = runCatching {
            avatarColor
                ?.toColorInt()
                ?.let(::Color)
                ?.let { color ->
                    val hsl = FloatArray(3)
                    RGBToHSL(
                        rf = color.red,
                        gf = color.green,
                        bf = color.blue,
                        outHsl = hsl,
                    )
                    generateAccentColors(
                        hue = hsl[0],
                    )
                }
        }.getOrNull()
        avatarColor
            ?: run {
                val colors = generateAccentColors(profileId)
                colors
            }
    }
    return DProfile(
        accountId = accountId,
        profileId = profileId,
        email = email,
        emailVerified = emailVerified,
        keyBase64 = keyBase64,
        privateKeyBase64 = privateKeyBase64,
        accentColor = accentColor,
        accountHost = accountHost,
        name = name,
        description = description.orEmpty(),
        premium = premium,
        hidden = hidden,
        securityStamp = securityStamp,
        twoFactorEnabled = twoFactorEnabled,
        masterPasswordHint = masterPasswordHint,
        masterPasswordHintEnabled = masterPasswordHintEnabled,
        unofficialServer = unofficialServer,
        serverVersion = serverVersion,
    )
}
