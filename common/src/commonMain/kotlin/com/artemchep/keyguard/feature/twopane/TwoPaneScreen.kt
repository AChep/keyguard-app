package com.artemchep.keyguard.feature.twopane

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalAbsoluteTonalElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.ui.util.VerticalDivider

@Composable
fun TwoPaneScreen(
    modifier: Modifier = Modifier,
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
        val scope = this
        Row(
            modifier = Modifier
                .fillMaxSize(),
        ) {
            val detailIsVisible = this@TwoPaneScaffold.tabletUi
            if (detailIsVisible) {
                val absoluteElevation = LocalAbsoluteTonalElevation.current + 1.dp
                CompositionLocalProvider(
                    LocalAbsoluteTonalElevation provides absoluteElevation,
                ) {
                    detail(
                        scope,
                        Modifier
                            .width(scope.masterPaneWidth),
                    )
                }
                VerticalDivider()
            }

            content(
                scope,
                Modifier,
                detailIsVisible,
            )
        }
    }
}
