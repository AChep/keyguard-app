package com.artemchep.keyguard.feature.send.action

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Share
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.platform.util.isRelease
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.icons.ChevronIcon
import com.artemchep.keyguard.ui.icons.icon

fun createSendActionOrNull(
    text: String,
    navigate: (NavigationIntent) -> Unit,
) = if (isRelease) {
    null
} else {
    createSendAction(
        text = text,
        navigate = navigate,
    )
}

fun createSendAction(
    text: String,
    navigate: (NavigationIntent) -> Unit,
) = FlatItemAction(
    leading = icon(Icons.Outlined.Send),
    title = "Send…",
    onClick = {
    },
)

fun createShareAction(
    translator: TranslatorScope,
    text: String,
    navigate: (NavigationIntent) -> Unit,
) = FlatItemAction(
    leading = icon(Icons.Outlined.Share),
    trailing = {
        ChevronIcon()
    },
    title = translator.translate(Res.strings.text_action_share_with_title),
    onClick = {
        val intent = NavigationIntent.NavigateToShare(
            text = text,
        )
        navigate(intent)
    },
)
