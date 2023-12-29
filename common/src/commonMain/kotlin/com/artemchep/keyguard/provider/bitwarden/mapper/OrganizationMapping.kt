package com.artemchep.keyguard.provider.bitwarden.mapper

import androidx.compose.ui.graphics.Color
import com.artemchep.keyguard.common.model.DOrganization
import com.artemchep.keyguard.common.util.RGBToHSL
import com.artemchep.keyguard.common.util.toColorInt
import com.artemchep.keyguard.core.store.bitwarden.BitwardenOrganization
import com.artemchep.keyguard.ui.icons.generateAccentColors

fun BitwardenOrganization.toDomain(): DOrganization {
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
                val colors = generateAccentColors(organizationId)
                colors
            }
    }
    return DOrganization(
        id = organizationId,
        accountId = accountId,
        revisionDate = revisionDate,
        keyBase64 = keyBase64,
        name = name,
        accentColor = accentColor,
        selfHost = selfHost,
    )
}
