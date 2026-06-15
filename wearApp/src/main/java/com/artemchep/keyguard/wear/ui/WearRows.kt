package com.artemchep.keyguard.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Text
import androidx.wear.compose.material3.ListSubHeader
import androidx.wear.compose.material3.LocalContentColor
import androidx.wear.compose.material3.SurfaceTransformation
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.theme.combineAlpha

enum class WearSectionHeaderEmptyBehavior {
    Spacer4,
    NoOp,
}

@Composable
fun WearSectionHeader(
    title: String?,
    modifier: Modifier = Modifier,
    emptyBehavior: WearSectionHeaderEmptyBehavior = WearSectionHeaderEmptyBehavior.Spacer4,
    transformation: SurfaceTransformation? = null,
) {
    if (title.isNullOrEmpty()) {
        when (emptyBehavior) {
            WearSectionHeaderEmptyBehavior.Spacer4 -> {
                Spacer(
                    modifier = modifier
                        .height(4.dp),
                )
            }

            WearSectionHeaderEmptyBehavior.NoOp -> Unit
        }
        return
    }

    ListSubHeader(
        modifier = modifier,
        transformation = transformation,
    ) {
        Text(title)
    }
}

@Composable
fun WearDotsDivider(
    modifier: Modifier = Modifier,
    transformation: SurfaceTransformation? = null,
) {
    Row(
        modifier = modifier
            .padding(vertical = 8.dp)
            .surfaceTransformation(transformation),
        horizontalArrangement = Arrangement.spacedBy(8.dp, alignment = Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) {
            Box(
                modifier = Modifier
                    .size(3.dp)
                    .background(
                        LocalContentColor.current.combineAlpha(DisabledEmphasisAlpha),
                        shape = CircleShape,
                    ),
            )
        }
    }
}
