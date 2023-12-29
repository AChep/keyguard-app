package com.artemchep.keyguard.ui.icons

import androidx.compose.material.icons.Icons
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import com.artemchep.keyguard.ui.rememberVectorPainterCustom
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.components.rememberImageComponent
import com.skydoves.landscapist.glide.GlideImage
import com.skydoves.landscapist.placeholder.placeholder.PlaceholderPlugin

@Composable
actual fun AttachmentIconImpl(
    uri: String?,
    modifier: Modifier,
) {
    // We can display a preview of a file.
    val fp = rememberVectorPainterCustom(
        Icons.Outlined.KeyguardAttachment,
        tintColor = LocalContentColor.current,
    )
    GlideImage(
        modifier = modifier,
        imageModel = { uri },
        imageOptions = ImageOptions(contentScale = ContentScale.Crop),
        component = rememberImageComponent {
            +PlaceholderPlugin.Failure(fp)
        },
    )
}
