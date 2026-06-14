package com.artemchep.keyguard.wear.feature.attachmentpreview

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.attachmentpreview.AttachmentPreviewRoute
import com.artemchep.keyguard.feature.navigation.Route

data class WearAttachmentPreviewRoute(
    val args: AttachmentPreviewRoute.Args,
) : Route {
    @Composable
    override fun Content() {
        WearAttachmentPreviewScreen(args = args)
    }
}
