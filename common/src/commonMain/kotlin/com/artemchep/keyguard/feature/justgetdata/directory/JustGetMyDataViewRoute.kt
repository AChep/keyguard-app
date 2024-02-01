package com.artemchep.keyguard.feature.justgetdata.directory

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBox
import androidx.compose.material.icons.outlined.Dataset
import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.service.justgetmydata.JustGetMyDataServiceInfo
import com.artemchep.keyguard.feature.navigation.DialogRoute
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.icons.iconSmall

data class JustGetMyDataViewRoute(
    val args: Args,
) : DialogRoute {
    companion object {
        fun justGetMyDataActionOrNull(
            translator: TranslatorScope,
            justGetMyData: JustGetMyDataServiceInfo,
            navigate: (NavigationIntent) -> Unit,
        ) = justGetMyDataAction(
            translator = translator,
            justGetMyData = justGetMyData,
            navigate = navigate,
        )

        fun justGetMyDataAction(
            translator: TranslatorScope,
            justGetMyData: JustGetMyDataServiceInfo,
            navigate: (NavigationIntent) -> Unit,
        ) = FlatItemAction(
            leading = iconSmall(Icons.Outlined.AccountBox, Icons.Outlined.Dataset),
            title = translator.translate(Res.strings.uri_action_get_my_data_account_title),
            onClick = {
                val route = JustGetMyDataViewRoute(
                    args = Args(
                        model = justGetMyData,
                    ),
                )
                val intent = NavigationIntent.NavigateToRoute(route)
                navigate(intent)
            },
        )
    }

    data class Args(
        val model: JustGetMyDataServiceInfo,
    )

    @Composable
    override fun Content() {
        JustGetMyDataViewScreen(
            args = args,
        )
    }
}
