package com.artemchep.keyguard.android.coil3

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import androidx.core.graphics.createBitmap
import coil3.annotation.ExperimentalCoilApi
import coil3.asImage
import coil3.decode.DataSource
import coil3.decode.DecodeUtils
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import coil3.request.bitmapConfig
import coil3.request.maxBitmapSize
import coil3.size.Precision
import kotlin.math.roundToInt

private const val DEFAULT_APP_ICON_SIZE = 512

/** Renders a shareable app icon at the size resolved by Coil, including adaptive icon masks. */
@OptIn(ExperimentalCoilApi::class)
fun Drawable.toAppIconImage(options: Options): ImageFetchResult {
    val sourceBitmap = (this as? BitmapDrawable)?.bitmap
    // Same fallback as Coil's own drawable conversion: a square default keeps
    // the aspect ratio of drawables without an intrinsic size.
    val sourceWidth = (sourceBitmap?.width ?: intrinsicWidth).takeIf { it > 0 }
        ?: DEFAULT_APP_ICON_SIZE
    val sourceHeight = (sourceBitmap?.height ?: intrinsicHeight).takeIf { it > 0 }
        ?: DEFAULT_APP_ICON_SIZE
    val isRaster = isRaster()
    val destinationSize = DecodeUtils.computeDstSize(
        srcWidth = sourceWidth,
        srcHeight = sourceHeight,
        targetSize = options.size,
        scale = options.scale,
        maxSize = options.maxBitmapSize,
    )
    val multiplier = DecodeUtils.computeSizeMultiplier(
        srcWidth = sourceWidth,
        srcHeight = sourceHeight,
        dstWidth = destinationSize.first,
        dstHeight = destinationSize.second,
        scale = options.scale,
        maxSize = options.maxBitmapSize,
    ).let {
        // Enlarging a raster icon cannot recover detail. Scalable drawables should
        // still be rendered at the requested resolution.
        if (isRaster && options.precision == Precision.INEXACT) {
            it.coerceAtMost(1.0)
        } else {
            it
        }
    }
    val width = (sourceWidth * multiplier).roundToInt().coerceAtLeast(1)
    val height = (sourceHeight * multiplier).roundToInt().coerceAtLeast(1)
    val config = options.bitmapConfig
        .takeUnless { it == Bitmap.Config.HARDWARE }
        ?: Bitmap.Config.ARGB_8888
    val bitmap = createBitmap(width, height, config)
    val drawable = mutate()
    val originalBounds = Rect(drawable.bounds)
    try {
        // Draw the whole drawable: extracting/scaling a BitmapDrawable's bitmap
        // would bypass its tint, alpha, and other drawing attributes.
        val canvas = Canvas(bitmap)
        if (drawable is BitmapDrawable && drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0) {
            // A bitmap's gravity and tile mode only fill its intrinsic bounds,
            // so scale the canvas instead of the bounds.
            drawable.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
            canvas.scale(
                width / drawable.intrinsicWidth.toFloat(),
                height / drawable.intrinsicHeight.toFloat(),
            )
        } else {
            drawable.setBounds(0, 0, width, height)
        }
        drawable.draw(canvas)
    } finally {
        drawable.bounds = originalBounds
    }
    return ImageFetchResult(
        image = bitmap.asImage(),
        // Even at its nominal intrinsic size a scalable icon can provide
        // more detail. Let larger requests render it again instead of upscaling
        // a smaller cached bitmap.
        isSampled = !isRaster || width < sourceWidth || height < sourceHeight,
        dataSource = DataSource.DISK,
    )
}

/** Whether the drawable has no more detail than its intrinsic size. */
private fun Drawable.isRaster(): Boolean = when (this) {
    is BitmapDrawable, is ColorDrawable -> true
    is AdaptiveIconDrawable -> listOfNotNull(background, foreground).all { it.isRaster() }
    else -> false
}
