package com.artemchep.keyguard.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val FileSignIcon: ImageVector
    get() {
        if (_FileSign != null) {
            return _FileSign!!
        }
        _FileSign = ImageVector.Builder(
            name = "FileSign",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(19.7f, 12.9f)
                lineTo(14f, 18.6f)
                horizontalLineTo(11.7f)
                verticalLineTo(16.3f)
                lineTo(17.4f, 10.6f)
                lineTo(19.7f, 12.9f)
                moveTo(23.1f, 12.1f)
                curveTo(23.1f, 12.4f, 22.8f, 12.7f, 22.5f, 13f)
                lineTo(20f, 15.5f)
                lineTo(19.1f, 14.6f)
                lineTo(21.7f, 12f)
                lineTo(21.1f, 11.4f)
                lineTo(20.4f, 12.1f)
                lineTo(18.1f, 9.8f)
                lineTo(20.3f, 7.7f)
                curveTo(20.5f, 7.5f, 20.9f, 7.5f, 21.2f, 7.7f)
                lineTo(22.6f, 9.1f)
                curveTo(22.8f, 9.3f, 22.8f, 9.7f, 22.6f, 10f)
                curveTo(22.4f, 10.2f, 22.2f, 10.4f, 22.2f, 10.6f)
                curveTo(22.2f, 10.8f, 22.4f, 11f, 22.6f, 11.2f)
                curveTo(22.9f, 11.5f, 23.2f, 11.8f, 23.1f, 12.1f)
                moveTo(3f, 20f)
                verticalLineTo(4f)
                horizontalLineTo(10f)
                verticalLineTo(9f)
                horizontalLineTo(15f)
                verticalLineTo(10.5f)
                lineTo(17f, 8.5f)
                verticalLineTo(8f)
                lineTo(11f, 2f)
                horizontalLineTo(3f)
                curveTo(1.9f, 2f, 1f, 2.9f, 1f, 4f)
                verticalLineTo(20f)
                curveTo(1f, 21.1f, 1.9f, 22f, 3f, 22f)
                horizontalLineTo(15f)
                curveTo(16.1f, 22f, 17f, 21.1f, 17f, 20f)
                horizontalLineTo(3f)
                moveTo(11f, 17.1f)
                curveTo(10.8f, 17.1f, 10.6f, 17.2f, 10.5f, 17.2f)
                lineTo(10f, 15f)
                horizontalLineTo(8.5f)
                lineTo(6.4f, 16.7f)
                lineTo(7f, 14f)
                horizontalLineTo(5.5f)
                lineTo(4.5f, 19f)
                horizontalLineTo(6f)
                lineTo(8.9f, 16.4f)
                lineTo(9.5f, 18.7f)
                horizontalLineTo(10.5f)
                lineTo(11f, 18.6f)
                verticalLineTo(17.1f)
                close()
            }
        }.build()

        return _FileSign!!
    }

@Suppress("ObjectPropertyName")
private var _FileSign: ImageVector? = null
