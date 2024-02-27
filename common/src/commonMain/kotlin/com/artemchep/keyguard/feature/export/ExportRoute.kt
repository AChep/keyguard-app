package com.artemchep.keyguard.feature.export

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.DFilter
import com.artemchep.keyguard.feature.confirmation.elevatedaccess.ElevatedAccessResult
import com.artemchep.keyguard.feature.confirmation.elevatedaccess.ElevatedAccessRoute
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.feature.navigation.registerRouteResultReceiver
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.icons.Stub
import com.artemchep.keyguard.ui.icons.icon

data class ExportRoute(
    val args: Args,
) : Route {
    companion object {
        fun actionOrNull(
            translator: TranslatorScope,
            accountId: AccountId,
            individual: Boolean,
            navigate: (NavigationIntent) -> Unit,
        ) = action(
            translator = translator,
            accountId = accountId,
            individual = individual,
            navigate = navigate,
        )

        fun action(
            translator: TranslatorScope,
            accountId: AccountId,
            individual: Boolean,
            navigate: (NavigationIntent) -> Unit,
        ): FlatItemAction {
            val title = kotlin.run {
                val res = if (individual) {
                    Res.strings.account_action_export_individual_vault_title
                } else {
                    Res.strings.account_action_export_vault_title
                }
                translator.translate(res)
            }
            return FlatItemAction(
                leading = kotlin.run {
                    val res = if (individual) {
                        Icons.Outlined.SaveAlt
                    } else {
                        Icons.Stub
                    }
                    icon(res)
                },
                title = title,
                onClick = {
                    val accountFilter = DFilter.ById(
                        id = accountId.id,
                        what = DFilter.ById.What.ACCOUNT,
                    )
                    val filter = if (individual) {
                        val orgFilter = DFilter.ById(
                            id = null,
                            what = DFilter.ById.What.ORGANIZATION,
                        )
                        DFilter.And(
                            filters = listOf(
                                accountFilter,
                                orgFilter,
                            ),
                        )
                    } else {
                        accountFilter
                    }
                    val route = ExportRoute(
                        args = Args(
                            title = title,
                            filter = filter,
                        ),
                    )
                    val intent = NavigationIntent.NavigateToRoute(route)
                    navigate(
                        intent = intent,
                        navigate = navigate,
                    )
                },
            )
        }

        fun navigate(
            intent: NavigationIntent,
            navigate: (NavigationIntent) -> Unit,
        ) {
            val elevatedRoute = registerRouteResultReceiver(
                route = ElevatedAccessRoute(),
            ) { result ->
                if (result is ElevatedAccessResult.Allow) {
                    navigate(intent)
                }
            }
            val elevatedIntent = NavigationIntent.NavigateToRoute(elevatedRoute)
            navigate(elevatedIntent)
        }
    }

    data class Args(
        val title: String? = null,
        val filter: DFilter? = null,
    )

    @Composable
    override fun Content() {
        ExportScreen(
            args = args,
        )
    }
}
