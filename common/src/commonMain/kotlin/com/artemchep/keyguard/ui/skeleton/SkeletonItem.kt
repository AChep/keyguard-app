package com.artemchep.keyguard.ui.skeleton

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.ShapeState
import com.artemchep.keyguard.feature.home.vault.component.FlatItemSimpleExpressive
import com.artemchep.keyguard.ui.Avatar
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.shimmer.shimmer
import com.artemchep.keyguard.ui.theme.combineAlpha

private const val defaultTitleWidth: Float = 0.45f

private const val defaultTextWidth: Float = 0.33f

enum class SkeletonItemAvatar {
    NONE,
    SMALL,
    LARGE,
}

@Composable
fun SkeletonItem(
    avatar: SkeletonItemAvatar = SkeletonItemAvatar.NONE,
    shapeState: Int = ShapeState.ALL,
    titleWidthFraction: Float = defaultTitleWidth,
    textWidthFraction: Float? = defaultTextWidth,
) {
    val contentColor = LocalContentColor.current
        .combineAlpha(DisabledEmphasisAlpha)
    FlatItemSimpleExpressive(
        shapeState = shapeState,
        leading = when (avatar) {
            SkeletonItemAvatar.LARGE -> {
                // composable
                {
                    Avatar(
                        modifier = Modifier
                            .shimmer(),
                        color = contentColor,
                    ) {
                    }
                }
            }
            SkeletonItemAvatar.SMALL -> {
                // composable
                {
                    Box(
                        Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(contentColor.copy(alpha = 0.2f))
                            .shimmer(),
                    )
                }
            }
            SkeletonItemAvatar.NONE -> null
        },
        title = {
            SkeletonText(
                modifier = Modifier
                    .fillMaxWidth(titleWidthFraction),
            )
        },
        text = if (textWidthFraction != null) {
            // composable
            {
                SkeletonText(
                    modifier = Modifier
                        .fillMaxWidth(textWidthFraction),
                )
            }
        } else {
            null
        },
        enabled = true,
    )
}

fun LazyListScope.skeletonItems(
    avatar: SkeletonItemAvatar = SkeletonItemAvatar.NONE,
    titleWidthFraction: Float = defaultTitleWidth,
    textWidthFraction: Float? = defaultTextWidth,
    count: Int = 3,
) = skeletonItemsPlacer(
    count = count,
) { index, shapeState ->
    item("skeleton.$index") {
        SkeletonItem(
            avatar = avatar,
            titleWidthFraction = titleWidthFraction,
            textWidthFraction = textWidthFraction,
            shapeState = shapeState,
        )
    }
}

inline fun skeletonItemsPlacer(
    count: Int = 3,
    place: (Int, Int) -> Unit,
) {
    for (i in 1..count) {
        val shapeState = if (count > 1) {
            when (i) {
                1 -> ShapeState.START
                count -> ShapeState.END
                else -> ShapeState.CENTER
            }
        } else ShapeState.ALL
        place(i, shapeState)
    }
}
