package com.artemchep.keyguard.feature.attachments

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.feature.navigation.RouteDescriptor

class AttachmentsRoute : Route {
    override val descriptor get() = RouteDescriptor.Downloads

    @Composable
    override fun Content() {
        AttachmentsScreen()
    }
}
