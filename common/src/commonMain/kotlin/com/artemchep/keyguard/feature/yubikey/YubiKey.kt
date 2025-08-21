package com.artemchep.keyguard.feature.yubikey

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Nfc
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import arrow.core.Either
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.common.model.map
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.DefaultEmphasisAlpha
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.ExpandedIfNotEmpty
import com.artemchep.keyguard.ui.KeyguardLoadingIndicator
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.theme.combineAlpha
import org.jetbrains.compose.resources.stringResource

typealias OnYubiKeyListener = (Either<Throwable, String>) -> Unit

@Composable
expect fun rememberYubiKey(
    send: OnYubiKeyListener?,
): YubiKeyState

@Composable
fun YubiKeyUsbCard(
    modifier: Modifier = Modifier,
    state: State<Loadable<YubiKeyUsbState>>,
) {
    val isEnabled by remember(state) {
        derivedStateOf {
            val isEnabled = state.value
                .map { it.enabled }
                .getOrNull()
            isEnabled == true
        }
    }
    val isActive by remember(state) {
        derivedStateOf {
            val isActive = state.value
                .map { it.devices.isNotEmpty() }
                .getOrNull()
            isActive == true
        }
    }
    YubiKeyCard(
        modifier = modifier
            .graphicsLayer {
                alpha = if (isEnabled) 1f else DisabledEmphasisAlpha
            },
        title = {
            Text(
                modifier = Modifier
                    .weight(1f),
                text = stringResource(Res.string.yubikey_usb_title),
            )
            Spacer(
                modifier = Modifier
                    .width(8.dp),
            )
            val colorTarget =
                if (isActive) MaterialTheme.colorScheme.primary else LocalContentColor.current
            val color by animateColorAsState(colorTarget)
            Icon(
                Icons.Outlined.Usb,
                modifier = Modifier
                    .align(Alignment.CenterVertically),
                contentDescription = null,
                tint = color,
            )
            Spacer(
                modifier = Modifier
                    .width(8.dp),
            )
        },
        text = stringResource(Res.string.yubikey_usb_text),
        content = {
            val isCapturing by remember(state) {
                derivedStateOf {
                    val isCapturing = state.value
                        .map { it.capturing }
                        .getOrNull()
                    isCapturing == true
                }
            }
            ExpandedIfNotEmpty(
                Unit.takeIf { isActive },
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        modifier = Modifier
                            .weight(1f),
                        text = stringResource(Res.string.yubikey_usb_touch_the_gold_sensor_note),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(
                        modifier = Modifier
                            .width(8.dp),
                    )
                    Crossfade(
                        modifier = Modifier
                            .size(24.dp),
                        targetState = isCapturing,
                    ) { capturing ->
                        if (capturing) {
                            KeyguardLoadingIndicator()
                        }
                    }
                    Spacer(
                        modifier = Modifier
                            .width(8.dp),
                    )
                }
            }
        },
    )
}

@Composable
fun YubiKeyNfcCard(
    modifier: Modifier = Modifier,
    state: State<Loadable<YubiKeyNfcState>>,
) {
    val isEnabled by remember(state) {
        derivedStateOf {
            val isEnabled = state.value
                .map { it.enabled }
                .getOrNull()
            isEnabled == true
        }
    }
    YubiKeyCard(
        modifier = modifier
            .graphicsLayer {
                alpha = if (isEnabled) 1f else DisabledEmphasisAlpha
            },
        title = {
            Text(
                modifier = Modifier
                    .weight(1f),
                text = stringResource(Res.string.yubikey_nfc_title),
            )
            Spacer(
                modifier = Modifier
                    .width(8.dp),
            )
            Icon(
                Icons.Outlined.Nfc,
                modifier = Modifier
                    .align(Alignment.CenterVertically),
                contentDescription = null,
            )
            Spacer(
                modifier = Modifier
                    .width(8.dp),
            )
        },
        text = stringResource(Res.string.yubikey_nfc_text),
    )
}

@Composable
private fun YubiKeyCard(
    modifier: Modifier = Modifier,
    title: @Composable RowScope.() -> Unit,
    text: String,
    content: (@Composable () -> Unit)? = null,
) {
    Box(
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .padding(8.dp),
        ) {
            val localEmphasis = DefaultEmphasisAlpha
            val localTextStyle = TextStyle(
                color = LocalContentColor.current
                    .combineAlpha(localEmphasis),
            )

            CompositionLocalProvider(
                LocalTextStyle provides MaterialTheme.typography.titleMedium
                    .merge(localTextStyle),
            ) {
                Row {
                    title()
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium
                    .merge(localTextStyle),
                color = LocalContentColor.current
                    .combineAlpha(localEmphasis)
                    .combineAlpha(MediumEmphasisAlpha),
            )
            if (content != null) {
                Spacer(modifier = Modifier.height(8.dp))
                content()
            }
        }
    }
}
