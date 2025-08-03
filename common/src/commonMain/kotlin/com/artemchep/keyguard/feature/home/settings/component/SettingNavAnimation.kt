package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Animation
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetNavAnimation
import com.artemchep.keyguard.common.usecase.GetNavAnimationVariants
import com.artemchep.keyguard.common.usecase.PutNavAnimation
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.vault.component.FlatDropdownSimpleExpressive
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.feature.localization.wrap
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatDropdown
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.FlatItemTextContent
import com.artemchep.keyguard.ui.icons.icon
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.flow.combine
import org.kodein.di.DirectDI
import org.kodein.di.instance

fun settingNavAnimationProvider(
    directDI: DirectDI,
) = settingNavAnimationProvider(
    getNavAnimation = directDI.instance(),
    getNavAnimationVariants = directDI.instance(),
    putNavAnimation = directDI.instance(),
    windowCoroutineScope = directDI.instance(),
    context = directDI.instance(),
)

fun settingNavAnimationProvider(
    getNavAnimation: GetNavAnimation,
    getNavAnimationVariants: GetNavAnimationVariants,
    putNavAnimation: PutNavAnimation,
    windowCoroutineScope: WindowCoroutineScope,
    context: LeContext,
): SettingComponent = combine(
    getNavAnimation(),
    getNavAnimationVariants(),
) { navAnimation, variants ->
    val text = textResource(navAnimation.title, context)
    val dropdown = variants
        .map { navAnimationVariant ->
            val actionSelected = navAnimationVariant == navAnimation
            val actionTitle = navAnimationVariant.title.wrap()
            FlatItemAction(
                title = actionTitle,
                selected = actionSelected,
                onClick = {
                    putNavAnimation(navAnimationVariant)
                        .launchIn(windowCoroutineScope)
                },
            )
        }

    SettingIi(
        search = SettingIi.Search(
            group = "nav_animation",
            tokens = listOf(
                "navigation",
                "animation",
                "crossfade",
            ),
        ),
    ) {
        SettingNavAnimation(
            text = text,
            dropdown = dropdown,
        )
    }
}

@Composable
private fun SettingNavAnimation(
    text: String,
    dropdown: List<FlatItemAction>,
) {
    FlatDropdownSimpleExpressive(
        leading = icon<RowScope>(Icons.Outlined.Animation),
        content = {
            FlatItemTextContent(
                title = {
                    Text(
                        text = stringResource(Res.string.pref_item_nav_animation_title),
                    )
                },
                text = {
                    Text(text)
                },
            )
        },
        dropdown = dropdown,
    )
}
