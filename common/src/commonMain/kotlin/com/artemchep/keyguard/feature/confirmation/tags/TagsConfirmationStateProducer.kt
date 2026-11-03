package com.artemchep.keyguard.feature.confirmation.tags

import androidx.compose.runtime.Composable
import arrow.core.andThen
import arrow.core.partially1
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.navigatePopSelf
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.feature.tags.tagUsage
import com.artemchep.keyguard.feature.tags.tagsFieldFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.koin.compose.currentKoinScope

@Composable
fun tagsConfirmationState(
    args: TagsConfirmationRoute.Args,
    transmitter: RouteResultTransmitter<TagsConfirmationResult>,
): TagsConfirmationState = with(currentKoinScope()) {
    tagsConfirmationState(
        args = args,
        transmitter = transmitter,
        windowCoroutineScope = get(),
        getCiphers = get(),
    )
}

@Composable
fun tagsConfirmationState(
    args: TagsConfirmationRoute.Args,
    transmitter: RouteResultTransmitter<TagsConfirmationResult>,
    windowCoroutineScope: WindowCoroutineScope,
    getCiphers: GetCiphers,
): TagsConfirmationState = produceScreenState(
    key = "tags_confirmation",
    initial = TagsConfirmationState(),
    args = arrayOf(
        windowCoroutineScope,
        getCiphers,
    ),
) {
    tagsConfirmationStateProducer(
        args = args,
        transmitter = transmitter,
        getCiphers = getCiphers,
    )
}

fun RememberStateFlowScope.tagsConfirmationStateProducer(
    args: TagsConfirmationRoute.Args,
    transmitter: RouteResultTransmitter<TagsConfirmationResult>,
    getCiphers: GetCiphers,
): Flow<TagsConfirmationState> = tagsFieldFlow(
    key = "tags",
    initial = args.initialTags,
    catalogFlow = getCiphers()
        .map(::tagUsage)
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default),
)
    .map { tags ->
        val onConfirm = transmitter
            .partially1(
                TagsConfirmationResult.Confirm(
                    tags = tags.value,
                ),
            )
            .andThen {
                navigatePopSelf()
            }
        TagsConfirmationState(
            tags = tags,
            onDeny = {
                transmitter(TagsConfirmationResult.Deny)
                navigatePopSelf()
            },
            onConfirm = onConfirm,
        )
    }
