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

val KeyguardIcons.TwoFactorAuthentication: ImageVector
    get() {
        if (_twoFactorAuthentication != null) {
            return _twoFactorAuthentication!!
        }
        _twoFactorAuthentication = Builder(
            name = "Two-factor-authentication",
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
                moveTo(2.0f, 7.0f)
                verticalLineTo(9.0f)
                horizontalLineTo(6.0f)
                verticalLineTo(11.0f)
                horizontalLineTo(4.0f)
                arcTo(2.0f, 2.0f, 0.0f, false, false, 2.0f, 13.0f)
                verticalLineTo(17.0f)
                horizontalLineTo(8.0f)
                verticalLineTo(15.0f)
                horizontalLineTo(4.0f)
                verticalLineTo(13.0f)
                horizontalLineTo(6.0f)
                arcTo(2.0f, 2.0f, 0.0f, false, false, 8.0f, 11.0f)
                verticalLineTo(9.0f)
                curveTo(8.0f, 7.89f, 7.1f, 7.0f, 6.0f, 7.0f)
                horizontalLineTo(2.0f)
                moveTo(9.0f, 7.0f)
                verticalLineTo(17.0f)
                horizontalLineTo(11.0f)
                verticalLineTo(13.0f)
                horizontalLineTo(14.0f)
                verticalLineTo(11.0f)
                horizontalLineTo(11.0f)
                verticalLineTo(9.0f)
                horizontalLineTo(15.0f)
                verticalLineTo(7.0f)
                horizontalLineTo(9.0f)
                moveTo(18.0f, 7.0f)
                arcTo(2.0f, 2.0f, 0.0f, false, false, 16.0f, 9.0f)
                verticalLineTo(17.0f)
                horizontalLineTo(18.0f)
                verticalLineTo(14.0f)
                horizontalLineTo(20.0f)
                verticalLineTo(17.0f)
                horizontalLineTo(22.0f)
                verticalLineTo(9.0f)
                arcTo(2.0f, 2.0f, 0.0f, false, false, 20.0f, 7.0f)
                horizontalLineTo(18.0f)
                moveTo(18.0f, 9.0f)
                horizontalLineTo(20.0f)
                verticalLineTo(12.0f)
                horizontalLineTo(18.0f)
                verticalLineTo(9.0f)
                close()
            }
        }
            .build()
        return _twoFactorAuthentication!!
    }

private var _twoFactorAuthentication: ImageVector? = null
