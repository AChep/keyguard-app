package com.artemchep.keyguard.feature.confirmation.tags

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.DialogRouteForResult
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter

class TagsConfirmationRoute(
    val args: Args,
) : DialogRouteForResult<TagsConfirmationResult> {
    data class Args(
        val initialTags: List<String> = emptyList(),
    )

    @Composable
    override fun Content(
        transmitter: RouteResultTransmitter<TagsConfirmationResult>,
    ) {
        TagsConfirmationScreen(
            args = args,
            transmitter = transmitter,
        )
    }
}
