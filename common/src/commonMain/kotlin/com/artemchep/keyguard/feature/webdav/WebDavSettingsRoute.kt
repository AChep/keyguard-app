package com.artemchep.keyguard.feature.webdav

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.WebDavLocation
import com.artemchep.keyguard.feature.navigation.RouteForResult
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter

data class WebDavSettingsRoute(
    val args: Args = Args(),
) : RouteForResult<WebDavSettingsResult> {
    data class Args(
        val url: String = "",
        val username: String = "",
        val password: String = "",
        val purpose: Purpose = Purpose.Collection,
    )

    enum class Purpose {
        Collection,
        KeePassDatabase,
    }

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
    val location: WebDavLocation,
) {
    val url: String get() = location.url
    val username: String? get() = location.credentials?.username
    val password: String? get() = location.credentials?.password?.value
}
