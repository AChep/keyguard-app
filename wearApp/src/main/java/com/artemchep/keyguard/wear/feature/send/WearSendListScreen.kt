package com.artemchep.keyguard.wear.feature.send

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.lazy.transformedHeight
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.LocalNavigationEntry
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.send.SendRoute
import com.artemchep.keyguard.feature.send.view.SendViewRouteFactory
import com.artemchep.keyguard.ui.CollectedEffect
import com.artemchep.keyguard.ui.skeleton.SkeletonItemAvatar
import com.artemchep.keyguard.ui.skeleton.skeletonItems
import com.artemchep.keyguard.wear.ui.WearScaffoldScreen
import com.artemchep.keyguard.wear.ui.skeletonItems
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance
import kotlin.collections.orEmpty

@Composable
fun WearSendListScreen(
    args: SendRoute.Args = SendRoute.Args(),
) {
    val state = wearSendListScreenState(
        args = args,
    )

    val routeFactory = with(localDI().direct) {
        instance<SendViewRouteFactory>()
    }
    val xd by rememberUpdatedState(LocalNavigationEntry.current.id)
    val controller by rememberUpdatedState(LocalNavigationController.current)
    CollectedEffect(state.sideEffects.showBiometricPromptFlow) {
        val route = routeFactory.create(
            sendId = it.id,
            accountId = it.accountId,
        )
        val intent = NavigationIntent.Composite(
            listOf(
                NavigationIntent.PopById(xd),
                NavigationIntent.NavigateToRoute(route),
            ),
        )
        controller.queue(intent)
//        bs.push(route)
    }

    WearScaffoldScreen(
        title = args.appBar?.title,
    ) { transformationSpec ->
        when (state.content) {
            is WearSendListState.Content.Skeleton -> {
                // Show a bunch of skeleton items, so it makes an impression of a
                // fully loaded screen.
                skeletonItems(
                    transformationSpec = transformationSpec,
                    count = 6,
                )
            }

            else -> {
                val list = (state.content as? WearSendListState.Content.Items)?.list.orEmpty()
                items(
                    items = list,
                    key = { model -> model.id },
                ) { model ->
                    WearSendListItem(
                        modifier = Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, transformationSpec),
                        item = model,
                        transformation = SurfaceTransformation(transformationSpec),
                    )
                }
            }
        }
    }
}
