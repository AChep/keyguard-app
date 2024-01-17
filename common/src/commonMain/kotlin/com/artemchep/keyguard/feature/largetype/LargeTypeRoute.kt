package com.artemchep.keyguard.feature.largetype

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.DialogRoute
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.icons.KeyguardLargeType
import com.artemchep.keyguard.ui.icons.icon
import com.artemchep.keyguard.ui.icons.iconSmall

data class LargeTypeRoute(
    val args: Args,
) : DialogRoute {
    companion object {
        fun showInLargeTypeActionOrNull(
            translator: TranslatorScope,
            text: String,
            colorize: Boolean = false,
            split: Boolean = false,
            navigate: (NavigationIntent) -> Unit,
        ) = if (text.length > 128) {
            null
        } else {
            showInLargeTypeAction(
                translator = translator,
                text = text,
                colorize = colorize,
                split = split,
                navigate = navigate,
            )
        }

        fun showInLargeTypeAction(
            translator: TranslatorScope,
            text: String,
            colorize: Boolean = false,
            split: Boolean = false,
            navigate: (NavigationIntent) -> Unit,
        ) = FlatItemAction(
            leading = icon(Icons.Outlined.KeyguardLargeType),
            title = translator.translate(Res.strings.largetype_action_show_in_large_type_title),
            onClick = {
                val route = LargeTypeRoute(
                    args = Args(
                        phrases = if (split) text.split(" ") else listOf(text),
                        colorize = colorize,
                    ),
                )
                val intent = NavigationIntent.NavigateToRoute(route)
                navigate(intent)
            },
        )

        fun showInLargeTypeActionAndLockOrNull(
            translator: TranslatorScope,
            text: String,
            colorize: Boolean = false,
            split: Boolean = false,
            navigate: (NavigationIntent) -> Unit,
        ) = if (text.length > 128 || CurrentPlatform !is Platform.Mobile) {
            null
        } else {
            showInLargeTypeAndLockAction(
                translator = translator,
                text = text,
                colorize = colorize,
                split = split,
                navigate = navigate,
            )
        }

        fun showInLargeTypeAndLockAction(
            translator: TranslatorScope,
            text: String,
            colorize: Boolean = false,
            split: Boolean = false,
            navigate: (NavigationIntent) -> Unit,
        ) = FlatItemAction(
            leading = iconSmall(Icons.Outlined.KeyguardLargeType, Icons.Filled.Lock),
            title = translator.translate(Res.strings.largetype_action_show_in_large_type_and_lock_title),
            onClick = {
                val intent = NavigationIntent.NavigateToLargeType(
                    phrases = if (split) text.split(" ") else listOf(text),
                    colorize = colorize,
                )
                navigate(intent)
            },
        )
    }

    data class Args(
        val phrases: List<String>,
        val colorize: Boolean,
    )

    @Composable
    override fun Content() {
        LargeTypeScreen(
            args = args,
        )
    }
}
