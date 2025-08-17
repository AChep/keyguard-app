package com.artemchep.keyguard.ui.icons

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.artemchep.keyguard.common.util.hash.FNV
import com.artemchep.keyguard.feature.home.vault.component.rememberSecretAccentColor
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.theme.combineAlpha
import java.util.Locale

@Composable
fun AttachmentIcon(
    uri: String? = null,
    name: String,
    encrypted: Boolean,
) {
    val accentColors = remember(name) {
        generateAccentColors(name)
    }
    val accentColor = rememberSecretAccentColor(
        accentLight = accentColors.light,
        accentDark = accentColors.dark,
    )

    val ext = remember(name) {
        val a = name.indexOfLast { it == '/' }
        val b = name.indexOfLast { it == '.' }
        if (b != -1 && b > a && b < name.length - 1) {
            return@remember name.substring(b + 1)
                .lowercase(Locale.ENGLISH)
        }

        null
    }

    if (uri != null && !encrypted) {
        when (ext) {
            "png",
            "jpg",
            "jpeg",
            "gif",
            "bmp",
            "webp",
            "heic",
            "heif",
            -> {
                // We can display a preview of a file.
                AttachmentIconImpl(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(accentColor),
                    uri = uri,
                )
                return
            }
        }
    }

    if (ext != null && ext.length in 2..3) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(accentColor),
        ) {
            Text(
                modifier = Modifier
                    .align(Alignment.Center),
                text = ext.uppercase(),
                color = Color.Black
                    .combineAlpha(MediumEmphasisAlpha),
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp,
                textAlign = TextAlign.Center,
            )
        }
    } else {
        Icon(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(accentColor)
                .padding(3.dp),
            imageVector = Icons.Outlined.KeyguardAttachment,
            contentDescription = null,
            tint = Color.Black
                .combineAlpha(MediumEmphasisAlpha),
        )
    }
}

@Composable
expect fun AttachmentIconImpl(
    uri: String? = null,
    modifier: Modifier = Modifier,
)

const val DEFAULT_HUE = 0.8f

@Immutable
data class AccentColors(
    val light: Color,
    val dark: Color,
)

fun generateAccentColorsByAccountId(
    accountId: String,
) = generateAccentColors(
    seed = accountId,
    saturation = 0.7f, // similar to what Bitwarden has
)

fun generateAccentColors(
    seed: String,
    saturation: Float = DEFAULT_HUE,
): AccentColors {
    val hue = run {
        val r = FNV.fnv1_32(seed).rem(60)
        r.toFloat() * 6f // hue
    }
    return generateAccentColors(
        hue = hue,
        saturation = saturation,
    )
}

fun generateAccentColors(
    hue: Float,
    saturation: Float = DEFAULT_HUE,
): AccentColors {
    val accentLight = Color.hsv(
        hue = hue,
        saturation = saturation,
        value = 0.8f,
    )
    val accentDark = Color.hsv(
        hue = hue,
        saturation = saturation,
        value = 0.8f,
    )
    return AccentColors(
        light = accentLight,
        dark = accentDark,
    )
}
