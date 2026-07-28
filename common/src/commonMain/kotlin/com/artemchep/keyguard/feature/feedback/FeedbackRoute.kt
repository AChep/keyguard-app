package com.artemchep.keyguard.feature.feedback

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.feature.navigation.RouteDescriptor

object FeedbackRoute : Route {
    override val descriptor get() = RouteDescriptor.Feedback

    @Composable
    override fun Content() {
        FeedbackScreen()
    }
}
