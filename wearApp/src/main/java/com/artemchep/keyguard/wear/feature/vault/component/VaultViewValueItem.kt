package com.artemchep.keyguard.wear.feature.vault.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.wear.compose.material3.LocalContentColor
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import com.artemchep.keyguard.feature.auth.common.VisibilityToggle
import com.artemchep.keyguard.feature.home.vault.component.rememberVisibilityState
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.feature.home.vault.model.Visibility
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.empty_value
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.animatedConcealedText
import com.artemchep.keyguard.ui.animation.animateContentHeight
import com.artemchep.keyguard.ui.colorizePassword
import com.artemchep.keyguard.ui.theme.monoFontFamily
import com.artemchep.keyguard.wear.feature.value.WearValueViewRoute
import com.artemchep.keyguard.wear.ui.ProxyMaterial3Styles
import com.artemchep.keyguard.wear.ui.WearListCard
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock

@Composable
fun WearVaultViewValueItem(
    modifier: Modifier = Modifier,
    item: VaultViewItem.Value,
    transformation: SurfaceTransformation? = null,
) {
    val contentColor = LocalContentColor.current

    val title = item.title.orEmpty()
    val value = remember(item.value, item.colorize, contentColor) {
        if (item.colorize) {
            colorizePassword(
                password = item.value,
                contentColor = contentColor,
            )
        } else {
            AnnotatedString(item.value)
        }
    }

    val visibilityConfig = item.visibility
    val visibilityState = rememberVisibilityState(
        visibilityConfig,
    )

    val navigationController by rememberUpdatedState(LocalNavigationController.current)
    val updatedVisibilityConfig by rememberUpdatedState(visibilityConfig)
    WearListCard(
        modifier = modifier
            .fillMaxWidth(),
        icon = item.leading
            ?.let { leading ->
                // composable
                {
                    ProxyMaterial3Styles {
                        leading()
                    }
                }
            },
        title = if (title.isNotEmpty()) {
            // composable
            {
                Text(
                    text = title,
                )
            }
        } else {
            null
        },
        text = {
            val shownValue = animatedConcealedText(
                text = value,
                concealed = !visibilityState.value.value || visibilityConfig.hidden,
            )
            Box(
                modifier = Modifier
                    .animateContentHeight(),
            ) {
                val fontFamily = if (item.monospace) monoFontFamily else null
                if (shownValue.isNotBlank()) {
                    Text(
                        text = shownValue,
                        fontFamily = fontFamily,
                        maxLines = item.maxLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Text(
                        modifier = Modifier
                            .alpha(MediumEmphasisAlpha),
                        text = stringResource(Res.string.empty_value),
                        fontFamily = fontFamily,
                    )
                }
            }
        },
        trailing = {
            if (visibilityConfig.concealed && !visibilityConfig.hidden) {
                val visible = visibilityState.value.value
                VisibilityToggle(
                    visible = visible,
                    onVisibleChange = { possibleNewValue ->
                        updatedVisibilityConfig.transformUserEvent(possibleNewValue) { newValue ->
                            visibilityState.value = Visibility.Event(
                                value = newValue,
                                timestamp = Clock.System.now(),
                            )
                        }
                    },
                )
            }
        },
        onClick = {
            val route = WearValueViewRoute(
                title = item.title,
                value = item.value,
                visibility = item.visibility,
                monospace = item.monospace,
                colorize = item.colorize,
                actions = item.dropdown,
            )
            navigationController.queue(
                NavigationIntent.NavigateToRoute(route),
            )
        },
        transformation = transformation,
    )
}
