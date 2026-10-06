package com.artemchep.keyguard.feature.webdav

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.RouteForResult
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.feature.remotepicker.RemotePickerMode

data class WebDavPickerRoute(
    val args: Args,
) : RouteForResult<WebDavPickerResult> {
    data class Args(
        val rootUrl: String,
        val username: String = "",
        val password: String = "",
        val mode: RemotePickerMode,
        val initialPath: String = "",
        val initialFileName: String = "",
    )

    @Composable
    override fun Content(
        transmitter: RouteResultTransmitter<WebDavPickerResult>,
    ) {
        WebDavPickerScreen(
            route = this,
            transmitter = transmitter,
        )
    }
}

data class WebDavPickerResult(
    val url: String,
)
