package com.artemchep.keyguard.ui.icons.custom

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType.Companion.NonZero
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap.Companion.Butt
import androidx.compose.ui.graphics.StrokeJoin.Companion.Miter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.ImageVector.Builder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.ui.icons.KeyguardIcons

val KeyguardIcons.FormatLetterCaseLower: ImageVector
    get() {
        if (_formatLetterCaseLower != null) {
            return _formatLetterCaseLower!!
        }
        _formatLetterCaseLower = Builder(
            name = "Format-letter-case-lower",
            defaultWidth =
            24.0.dp,
            defaultHeight = 24.0.dp, viewportWidth = 24.0f,
            viewportHeight =
            24.0f,
        ).apply {
            path(
                fill = SolidColor(Color(0xFF000000)), stroke = null, strokeLineWidth = 0.0f,
                strokeLineCap = Butt, strokeLineJoin = Miter, strokeLineMiter = 4.0f,
                pathFillType = NonZero,
            ) {
                moveTo(20.06f, 18.0f)
                curveTo(20.0f, 17.83f, 19.91f, 17.54f, 19.86f, 17.11f)
                curveTo(19.19f, 17.81f, 18.38f, 18.16f, 17.45f, 18.16f)
                curveTo(16.62f, 18.16f, 15.93f, 17.92f, 15.4f, 17.45f)
                curveTo(14.87f, 17.0f, 14.6f, 16.39f, 14.6f, 15.66f)
                curveTo(14.6f, 14.78f, 14.93f, 14.1f, 15.6f, 13.61f)
                curveTo(16.27f, 13.12f, 17.21f, 12.88f, 18.43f, 12.88f)
                horizontalLineTo(19.83f)
                verticalLineTo(12.24f)
                curveTo(19.83f, 11.75f, 19.68f, 11.36f, 19.38f, 11.07f)
                curveTo(19.08f, 10.78f, 18.63f, 10.64f, 18.05f, 10.64f)
                curveTo(17.53f, 10.64f, 17.1f, 10.76f, 16.75f, 11.0f)
                curveTo(16.4f, 11.25f, 16.23f, 11.54f, 16.23f, 11.89f)
                horizontalLineTo(14.77f)
                curveTo(14.77f, 11.46f, 14.92f, 11.05f, 15.22f, 10.65f)
                curveTo(15.5f, 10.25f, 15.93f, 9.94f, 16.44f, 9.71f)
                curveTo(16.95f, 9.5f, 17.5f, 9.36f, 18.13f, 9.36f)
                curveTo(19.11f, 9.36f, 19.87f, 9.6f, 20.42f, 10.09f)
                curveTo(20.97f, 10.58f, 21.26f, 11.25f, 21.28f, 12.11f)
                verticalLineTo(16.0f)
                curveTo(21.28f, 16.8f, 21.38f, 17.42f, 21.58f, 17.88f)
                verticalLineTo(18.0f)
                horizontalLineTo(20.06f)
                moveTo(17.66f, 16.88f)
                curveTo(18.11f, 16.88f, 18.54f, 16.77f, 18.95f, 16.56f)
                curveTo(19.35f, 16.35f, 19.65f, 16.07f, 19.83f, 15.73f)
                verticalLineTo(14.16f)
                horizontalLineTo(18.7f)
                curveTo(16.93f, 14.16f, 16.04f, 14.63f, 16.04f, 15.57f)
                curveTo(16.04f, 16.0f, 16.19f, 16.3f, 16.5f, 16.53f)
                curveTo(16.8f, 16.76f, 17.18f, 16.88f, 17.66f, 16.88f)
                moveTo(5.46f, 13.71f)
                horizontalLineTo(9.53f)
                lineTo(7.5f, 8.29f)
                lineTo(5.46f, 13.71f)
                moveTo(6.64f, 6.0f)
                horizontalLineTo(8.36f)
                lineTo(13.07f, 18.0f)
                horizontalLineTo(11.14f)
                lineTo(10.17f, 15.43f)
                horizontalLineTo(4.82f)
                lineTo(3.86f, 18.0f)
                horizontalLineTo(1.93f)
                lineTo(6.64f, 6.0f)
                moveTo(22.0f, 20.0f)
                verticalLineTo(22.0f)
                horizontalLineTo(14.5f)
                verticalLineTo(20.0f)
                horizontalLineTo(22.0f)
                close()
            }
        }
            .build()
        return _formatLetterCaseLower!!
    }

private var _formatLetterCaseLower: ImageVector? = null
