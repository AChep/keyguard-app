package com.artemchep.keyguard.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.RenderVectorGroup
import androidx.compose.ui.graphics.vector.VectorPainter

/**
 * Create a [VectorPainter] with the given [ImageVector]. This will create a
 * sub-composition of the vector hierarchy given the tree structure in [ImageVector]
 *
 * @param [image] ImageVector used to create a vector graphic sub-composition
 */
@Composable
fun rememberVectorPainterCustom(
    image: ImageVector,
    tintColor: Color = Color.Unspecified,
) = androidx.compose.ui.graphics.vector.rememberVectorPainter(
    defaultWidth = image.defaultWidth,
    defaultHeight = image.defaultHeight,
    viewportWidth = image.viewportWidth,
    viewportHeight = image.viewportHeight,
    name = image.name,
    tintColor = tintColor.takeIf { it.isSpecified } ?: image.tintColor,
    tintBlendMode = image.tintBlendMode,
    autoMirror = image.autoMirror,
    content = { _, _ -> RenderVectorGroup(group = image.root) },
)
