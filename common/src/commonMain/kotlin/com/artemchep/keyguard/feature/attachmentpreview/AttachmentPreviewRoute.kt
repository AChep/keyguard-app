package com.artemchep.keyguard.feature.attachmentpreview

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.Route

data class AttachmentPreviewRoute(
    val args: Args,
) : Route {
    data class Args(
        val localCipherId: String,
        val remoteCipherId: String?,
        val attachmentId: String,
        val fileName: String,
        val encryptedSize: Long? = null,
    )

    @Composable
    override fun Content() {
        AttachmentPreviewScreen(args = args)
    }
}
