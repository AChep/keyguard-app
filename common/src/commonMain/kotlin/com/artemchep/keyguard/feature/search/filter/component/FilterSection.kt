package com.artemchep.keyguard.feature.search.filter.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.artemchep.keyguard.ui.FlatItem
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.theme.combineAlpha

@Composable
fun FilterSectionComposable(
    modifier: Modifier = Modifier,
    expandable: Boolean,
    expanded: Boolean,
    title: String,
    onClick: (() -> Unit)?,
) {
    FlatItem(
        modifier = modifier,
        paddingValues = PaddingValues(
            horizontal = 0.dp,
            vertical = 0.dp,
        ),
        trailing = {
            if (!expandable) {
                return@FlatItem
            }

            val targetRotationX =
                if (expanded) {
                    0f
                } else {
                    180f
                }
            val rotationX by animateFloatAsState(targetRotationX)
            Icon(
                modifier = Modifier
                    .graphicsLayer(
                        rotationX = rotationX,
                        rotationZ = -90f,
                    ),
                tint = LocalContentColor.current
                    .combineAlpha(MediumEmphasisAlpha),
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
            )
        },
        title = {
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.labelLarge,
                fontSize = 12.sp,
                fontWeight = FontWeight.Black,
                color = LocalContentColor.current
                    .combineAlpha(MediumEmphasisAlpha),
            )
        },
        onClick = onClick,
        enabled = true,
    )
}
