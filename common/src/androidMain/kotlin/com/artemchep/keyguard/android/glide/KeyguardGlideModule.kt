package com.artemchep.keyguard.android.glide

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import com.artemchep.keyguard.android.glide.app.AppIconLoader
import com.artemchep.keyguard.android.glide.gravatar.GravatarLoader
import com.artemchep.keyguard.android.glide.qr.QrLoader
import com.artemchep.keyguard.android.glide.website.FaviconLoader
import com.artemchep.keyguard.common.model.BarcodeImageRequest
import com.artemchep.keyguard.feature.favicon.AppIconUrl
import com.artemchep.keyguard.feature.favicon.FaviconUrl
import com.artemchep.keyguard.feature.favicon.GravatarUrl
import com.bumptech.glide.Glide
import com.bumptech.glide.Registry
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.module.AppGlideModule
import org.kodein.di.android.closestDI
import org.kodein.di.direct
import org.kodein.di.instance
import java.io.InputStream

@GlideModule
class KeyguardGlideModule : AppGlideModule() {
    override fun registerComponents(context: Context, glide: Glide, registry: Registry) {
        val di by closestDI { context }

        registry.prepend(
            AppIconUrl::class.java,
            Bitmap::class.java,
            AppIconLoader.Factory(context.packageManager),
        )
        registry.prepend(
            BarcodeImageRequest::class.java,
            Drawable::class.java,
            QrLoader.Factory(
                context = context,
                getBarcodeImage = di.direct.instance(),
            ),
        )
        registry.prepend(FaviconUrl::class.java, InputStream::class.java, FaviconLoader.Factory())
        registry.prepend(GravatarUrl::class.java, InputStream::class.java, GravatarLoader.Factory())
    }
}
