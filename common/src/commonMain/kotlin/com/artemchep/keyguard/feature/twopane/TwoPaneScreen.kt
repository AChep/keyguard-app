package com.artemchep.keyguard.feature.twopane

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.platform.leDisplayCutout
import com.artemchep.keyguard.platform.leNavigationBars
import com.artemchep.keyguard.platform.leStatusBars
import com.artemchep.keyguard.platform.leSystemBars
import com.artemchep.keyguard.ui.surface.LocalSurfaceElevation
import com.artemchep.keyguard.ui.surface.ProvideSurfaceColor
import com.artemchep.keyguard.ui.surface.splitLow
import com.artemchep.keyguard.ui.surface.surfaceElevationColor
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.horizontalPaddingHalf

@Composable
fun TwoPaneScreen(
    modifier: Modifier = Modifier,
    header: @Composable ColumnScope.(Modifier) -> Unit,
    detail: @Composable TwoPaneScaffoldScope.(Modifier) -> Unit,
    content: @Composable TwoPaneScaffoldScope.(Modifier, Boolean) -> Unit,
) {
    TwoPaneScaffold(
        modifier = modifier,
        masterPaneMinWidth = 280.dp,
        detailPaneMinWidth = 180.dp,
        detailPaneMaxWidth = 320.dp,
        ratio = 0.4f,
    ) {
        val surfaceElevation = LocalSurfaceElevation.current

        val scope = this
        // In the tablet mode we "spill" the lower background color
        // between the detail and content panels.
        val color = kotlin.run {
            val elevation = if (tabletUi) {
                surfaceElevation.splitLow()
                    .to
            } else {
                surfaceElevation.to
            }
            surfaceElevationColor(elevation)
        }
        ProvideSurfaceColor(color) {
            val detailIsVisible = this@TwoPaneScaffold.tabletUi
            val insetsModifier = if (detailIsVisible) {
                val insetsTop = WindowInsets.leSystemBars
                    .only(WindowInsetsSides.Top)
                val insetsEnd = WindowInsets.leStatusBars
                    .union(WindowInsets.leNavigationBars)
                    .union(WindowInsets.leDisplayCutout)
                    .only(WindowInsetsSides.End)
                Modifier
                    .windowInsetsPadding(insetsTop)
                    .windowInsetsPadding(insetsEnd)
            } else {
                Modifier
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(color)
                    .then(insetsModifier),
            ) {
                if (detailIsVisible) {
                    header.invoke(this, Modifier)
                }
                Row(
                    modifier = Modifier
                        .fillMaxSize(),
                ) {
                    if (detailIsVisible) {
                        detail(
                            scope,
                            Modifier
                                .width(scope.masterPaneWidth),
                        )
                    }
                    val contentModifier = if (detailIsVisible) {
                        val shapeModifier = kotlin.run {
                            val shape = MaterialTheme.shapes.large
                                .copy(
                                    bottomStart = ZeroCornerSize,
                                    bottomEnd = ZeroCornerSize,
                                )
                            Modifier
                                .clip(shape)
                        }
                        val paddingModifier = Modifier
                            .padding(end = Dimens.horizontalPaddingHalf)
                        Modifier
                            .then(paddingModifier)
                            .then(shapeModifier)
                    } else {
                        Modifier
                    }
                    val detailSurfaceColor = surfaceElevationColor(surfaceElevation.to)
                    ProvideSurfaceColor(detailSurfaceColor) {
                        content(
                            scope,
                            contentModifier,
                            detailIsVisible,
                        )
                    }
                }
            }
        }
    }
}
