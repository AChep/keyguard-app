package com.artemchep.keyguard.feature.filter

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.localization.wrap
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.icons.ChevronIcon
import com.artemchep.keyguard.ui.icons.KeyguardCipherFilter
import com.artemchep.keyguard.ui.icons.iconSmall

object CipherFiltersRoute : Route {
    const val ROUTER_NAME = "cipher_filters"

    fun actionOrNull(
        translator: TranslatorScope,
        navigate: (NavigationIntent) -> Unit,
    ) = action(
        translator = translator,
        navigate = navigate,
    )

    fun action(
        translator: TranslatorScope,
        navigate: (NavigationIntent) -> Unit,
    ) = FlatItemAction(
        leading = iconSmall(Icons.Outlined.KeyguardCipherFilter),
        title = Res.string.customfilters_header_title.wrap(),
        trailing = {
            ChevronIcon()
        },
        onClick = {
            val route = CipherFiltersRoute
            val intent = NavigationIntent.NavigateToRoute(route)
            navigate(intent)
        },
    )

    @Composable
    override fun Content() {
        CipherFiltersScreen()
    }
}
