package com.artemchep.keyguard.feature.attachmentpreview

import com.artemchep.keyguard.feature.navigation.Route

interface AttachmentPreviewRouteFactory {
    fun create(
        args: AttachmentPreviewRoute.Args,
    ): Route
}

object AttachmentPreviewRouteFactoryDefault : AttachmentPreviewRouteFactory {
    override fun create(
        args: AttachmentPreviewRoute.Args,
    ): Route {
        return AttachmentPreviewRoute(
            args = args,
        )
    }
}
