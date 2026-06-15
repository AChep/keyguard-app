package com.artemchep.keyguard.wear.feature.navigation

import com.artemchep.keyguard.feature.attachmentpreview.AttachmentPreviewRoute
import com.artemchep.keyguard.wear.feature.attachmentpreview.WearAttachmentPreviewRoute
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AttachmentPreviewRouteFactoryWearTest {
    @Test
    fun `wear attachment preview factory returns wear route`() {
        val args = AttachmentPreviewRoute.Args(
            localCipherId = "local-cipher",
            remoteCipherId = "remote-cipher",
            attachmentId = "attachment",
            fileName = "preview.txt",
            encryptedSize = 1L,
        )

        val route = AttachmentPreviewRouteFactoryWear.create(args)

        val wearRoute = assertIs<WearAttachmentPreviewRoute>(route)
        assertEquals(args, wearRoute.args)
    }
}
