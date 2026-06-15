package com.artemchep.keyguard.wear.feature.navigation

import com.artemchep.keyguard.feature.attachmentpreview.AttachmentPreviewRoute
import com.artemchep.keyguard.feature.attachmentpreview.AttachmentPreviewRouteFactory
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.wear.feature.attachmentpreview.WearAttachmentPreviewRoute

object AttachmentPreviewRouteFactoryWear : AttachmentPreviewRouteFactory {
    override fun create(
        args: AttachmentPreviewRoute.Args,
    ): Route {
        return WearAttachmentPreviewRoute(
            args = args,
        )
    }
}
