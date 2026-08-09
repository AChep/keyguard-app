package com.artemchep.keyguard.feature.webdav

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.RouteForResult
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter

data class WebDavPickerRoute(
    val args: Args,
) : RouteForResult<WebDavPickerResult> {
    data class Args(
        val rootUrl: String,
        val username: String = "",
        val password: String = "",
        val mode: Mode,
        val initialPath: String = "",
        val initialFileName: String = "",
    )

    enum class Mode {
        SelectCollection,
        OpenKeePassDatabase,
        CreateKeePassDatabase,
    }

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
