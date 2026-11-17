package com.artemchep.keyguard.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardCapslock
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.isCapsLockOn
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.platform.input.isHardwareKeyboardInput
import com.artemchep.keyguard.platform.input.rememberKeyboardInputLanguage
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.tooltip.Tooltip
import com.artemchep.keyguard.ui.util.DividerColor
import org.jetbrains.compose.resources.stringResource

/**
 * Shows that Caps Lock is on or that the keyboard language is not
 * English, while the field is [focused] and the user types on a
 * hardware keyboard.
 */
@Composable
fun PasswordKeyboardHint(
    focused: Boolean,
    modifier: Modifier = Modifier,
) {
    val active = focused && isHardwareKeyboardInput()
    val windowInfo = LocalWindowInfo.current
    // Recompose on Caps Lock changes only, not on every modifier key.
    val capsLockState = remember(windowInfo) {
        derivedStateOf {
            windowInfo.keyboardModifiers.isCapsLockOn
        }
    }
    val capsLock = active && capsLockState.value
    val language = rememberKeyboardInputLanguage(active).value
        ?.takeIf { active && it != "en" }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val contentColor = LocalContentColor.current
            .combineAlpha(MediumEmphasisAlpha)
        ExpandedIfNotEmptyForRow(
            valueOrNull = Unit.takeIf { capsLock },
        ) {
            val text = stringResource(Res.string.password_keyboard_hint_caps_lock)
            PasswordKeyboardHintItem(
                text = text,
            ) {
                Icon(
                    modifier = Modifier
                        .size(18.dp),
                    imageVector = Icons.Outlined.KeyboardCapslock,
                    contentDescription = text,
                    tint = contentColor,
                )
            }
        }
        ExpandedIfNotEmptyForRow(
            valueOrNull = language,
        ) { language ->
            val code = language.uppercase()
            val text = stringResource(Res.string.password_keyboard_hint_language, code)
            PasswordKeyboardHintItem(
                text = text,
            ) {
                PasswordKeyboardHintBadge(
                    code = code,
                    contentDescription = text,
                    color = contentColor,
                )
            }
        }
    }
}

@Composable
private fun PasswordKeyboardHintBadge(
    code: String,
    contentDescription: String,
    color: Color,
) {
    Box(
        modifier = Modifier
            .clearAndSetSemantics {
                this.contentDescription = contentDescription
            }
            .border(Dp.Hairline, DividerColor, MaterialTheme.shapes.small)
            .padding(
                horizontal = 4.dp,
                vertical = 2.dp,
            ),
    ) {
        Text(
            text = code,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
        )
    }
}

@Composable
private fun PasswordKeyboardHintItem(
    text: String,
    content: @Composable () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tooltip(
            valueOrNull = text,
            tooltip = { t ->
                Text(t)
            },
            content = content,
        )
        Spacer(
            modifier = Modifier
                .width(8.dp),
        )
    }
}
