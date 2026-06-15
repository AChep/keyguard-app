package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.KgAction
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.urlblock.UrlBlockListRoute
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.icons.ChevronIcon
import kotlinx.coroutines.flow.flowOf
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.DirectDI
import org.kodein.di.instance

fun settingAutofillBlockUriProvider(
    directDI: DirectDI,
) = settingAutofillBlockUriProvider(
    windowCoroutineScope = directDI.instance(),
)

fun settingAutofillBlockUriProvider(
    windowCoroutineScope: WindowCoroutineScope,
): SettingComponent = run {
    val item = SettingIi(
        search = SettingIi.Search(
            group = "autofill",
            tokens = listOf(
                "autofill",
                "save",
            ),
        ),
    ) {
        val navigationController by rememberUpdatedState(LocalNavigationController.current)
        SettingAutofillBlockUri(
            onClick = {
                val intent = NavigationIntent.NavigateToRoute(
                    route = UrlBlockListRoute,
                )
                navigationController.queue(intent)
            },
        )
    }
    flowOf(item)
}

@Composable
private fun SettingAutofillBlockUri(
    onClick: (() -> Unit)?,
) {
    LocalSettingPaneComponents.current.KgAction(
        icon = Icons.Outlined.Block,
        title = stringResource(Res.string.pref_item_autofill_block_uri_title),
        text = stringResource(Res.string.pref_item_autofill_block_uri_text),
        trailing = {
            ChevronIcon()
        },
        onClick = onClick,
    )
}
