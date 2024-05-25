package com.artemchep.keyguard.ui.pulltosearch

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.pullrefresh.PullRefreshState
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.theme.combineAlpha
import org.jetbrains.compose.resources.stringResource
import kotlin.math.log10

@Composable
fun PullToSearch(
    modifier: Modifier = Modifier,
    pullRefreshState: PullRefreshState,
) {
    Box(
        modifier = modifier
            .fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        val willSearch by remember(pullRefreshState) {
            derivedStateOf {
                pullRefreshState.progress >= 1f
            }
        }
        Row(
            modifier = Modifier
                .graphicsLayer {
                    val progress =
                        log10(pullRefreshState.progress.coerceAtLeast(0f) * 10 + 1)
                    alpha = progress.coerceIn(0f..1f)
                    val scale = progress
                        .coerceAtLeast(0.1f)
                        .coerceAtMost(1f)
                    scaleX = scale
                    scaleY = scale
                    // move down a bit
                    translationY = (progress - 1f) * 32f * density
                }
                .padding(
                    vertical = 16.dp,
                    horizontal = 16.dp,
                ),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val tintTarget =
                if (willSearch) {
                    MaterialTheme.colorScheme.primary
                } else {
                    LocalContentColor.current.combineAlpha(DisabledEmphasisAlpha)
                }
            val tint by animateColorAsState(tintTarget)
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = null,
                tint = tint,
            )
            Text(
                text = stringResource(Res.string.pull_to_search),
                color = tint,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
