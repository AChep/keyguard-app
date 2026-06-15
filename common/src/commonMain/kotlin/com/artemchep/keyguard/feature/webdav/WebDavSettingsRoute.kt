package com.artemchep.keyguard.feature.webdav

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.RouteForResult
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter

data class WebDavSettingsRoute(
    val args: Args = Args(),
) : RouteForResult<WebDavSettingsResult> {
    data class Args(
        val url: String = "",
        val username: String = "",
        val password: String = "",
    )

    @Composable
    override fun Content(
        transmitter: RouteResultTransmitter<WebDavSettingsResult>,
    ) {
        WebDavSettingsScreen(
            route = this,
            transmitter = transmitter,
        )
    }
}

data class WebDavSettingsResult(
    val url: String,
    val username: String?,
    val password: String?,
)
