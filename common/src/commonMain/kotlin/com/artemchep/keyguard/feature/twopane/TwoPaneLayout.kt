package com.artemchep.keyguard.feature.twopane

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalAbsoluteTonalElevation
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtMost
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.usecase.GetAllowTwoPanelLayoutInLandscape
import com.artemchep.keyguard.common.usecase.GetAllowTwoPanelLayoutInPortrait
import com.artemchep.keyguard.common.usecase.GetNavAnimation
import com.artemchep.keyguard.feature.home.HomeLayout
import com.artemchep.keyguard.feature.home.LocalHomeLayout
import com.artemchep.keyguard.feature.keyguard.setup.keyguardSpan
import com.artemchep.keyguard.feature.navigation.NavigationAnimation
import com.artemchep.keyguard.feature.navigation.NavigationAnimationType
import com.artemchep.keyguard.feature.navigation.transform
import com.artemchep.keyguard.platform.LocalAnimationFactor
import com.artemchep.keyguard.platform.leDisplayCutout
import com.artemchep.keyguard.platform.leNavigationBars
import com.artemchep.keyguard.platform.leStatusBars
import com.artemchep.keyguard.ui.scaffoldContentWindowInsets
import com.artemchep.keyguard.ui.screenMaxWidth
import com.artemchep.keyguard.ui.surface.LocalSurfaceColor
import com.artemchep.keyguard.ui.surface.LocalSurfaceElevation
import com.artemchep.keyguard.ui.surface.ReportSurfaceColor
import com.artemchep.keyguard.ui.surface.color
import com.artemchep.keyguard.ui.surface.splitHigh
import com.artemchep.keyguard.ui.surface.splitLow
import com.artemchep.keyguard.ui.surface.surfaceElevationColor
import com.artemchep.keyguard.ui.util.VerticalDivider
import org.kodein.di.compose.rememberInstance

@Composable
fun TwoPaneScaffold(
    modifier: Modifier = Modifier,
    masterPaneMinWidth: Dp = 310.dp,
    detailPaneMinWidth: Dp = masterPaneMinWidth,
    detailPaneMaxWidth: Dp = screenMaxWidth,
    ratio: Float = 0.5f,
    content: @Composable TwoPaneScaffoldScope.() -> Unit,
) {
    val getAllowTwoPanelLayoutInPortrait by rememberInstance<GetAllowTwoPanelLayoutInPortrait>()
    val allowTwoPanelLayoutInPortrait = remember(getAllowTwoPanelLayoutInPortrait) {
        getAllowTwoPanelLayoutInPortrait()
    }.collectAsState()

    val getAllowTwoPanelLayoutInLandscape by rememberInstance<GetAllowTwoPanelLayoutInLandscape>()
    val allowTwoPanelLayoutInLandscape = remember(getAllowTwoPanelLayoutInLandscape) {
        getAllowTwoPanelLayoutInLandscape()
    }.collectAsState()

    check(ratio <= 0.5f) {
        "Ratios larger than 0.5 are not supported!"
    }

    BoxWithConstraints(
        modifier = modifier,
    ) {
        val masterPaneWidth = (maxWidth * ratio)
            .coerceAtLeast(detailPaneMinWidth)
            .coerceAtMost(detailPaneMaxWidth)
        val totalPaneWidth = masterPaneWidth + masterPaneMinWidth
        val tabletUi = when (LocalHomeLayout.current) {
            is HomeLayout.Horizontal -> allowTwoPanelLayoutInLandscape.value &&
                    totalPaneWidth <= maxWidth

            is HomeLayout.Vertical -> allowTwoPanelLayoutInPortrait.value &&
                    totalPaneWidth * 1.25f <= maxWidth
        }
        val scope = TwoPaneScaffoldScopeImpl(
            parent = this,
            tabletUi = tabletUi,
            masterPaneWidth = masterPaneWidth,
        )
        content(scope)
    }
}

@Stable
interface TwoPaneScaffoldScope : BoxScope {
    val tabletUi: Boolean
    val masterPaneWidth: Dp
}

private data class TwoPaneScaffoldScopeImpl(
    private val parent: BoxScope,
    override val tabletUi: Boolean,
    override val masterPaneWidth: Dp,
) : TwoPaneScaffoldScope, BoxScope by parent

@Composable
fun TwoPaneScaffoldScope.TwoPaneLayout(
    detailPane: (@Composable BoxScope.() -> Unit)? = null,
    masterPane: @Composable BoxScope.() -> Unit,
) {
    val surfaceElevation = LocalSurfaceElevation.current
    val getNavAnimation by rememberInstance<GetNavAnimation>()
    Row(
        modifier = Modifier
            .background(surfaceElevationColor(surfaceElevation.from)),
    ) {
        if (this@TwoPaneLayout.tabletUi) {
            val elevation = surfaceElevation.splitLow()
            CompositionLocalProvider(
                LocalSurfaceElevation provides elevation,
                LocalSurfaceColor provides surfaceElevationColor(elevation.to),
                LocalHasDetailPane provides true,
            ) {
                ReportSurfaceColor()

                val horizontalInsets = scaffoldContentWindowInsets
                    .only(WindowInsetsSides.Horizontal)
                PaneLayout(
                    modifier = Modifier
                        .consumeWindowInsets(horizontalInsets)
                        .widthIn(max = this@TwoPaneLayout.masterPaneWidth),
                ) {
                    masterPane(this)
                }
            }
        }
        key("movable-pane") {
            val elevation = if (this@TwoPaneLayout.tabletUi) {
                surfaceElevation.splitHigh()
            } else {
                surfaceElevation
            }
            CompositionLocalProvider(
                LocalSurfaceElevation provides elevation,
                LocalSurfaceColor provides surfaceElevationColor(elevation.to),
                LocalHasDetailPane provides false,
            ) {
                ReportSurfaceColor()

                PaneLayout(
                    modifier = Modifier
                        .background(surfaceElevation.color),
                ) {
                    val pane = detailPane ?: masterPane.takeUnless { this@TwoPaneLayout.tabletUi }
                    val updatedAnimationScale by rememberUpdatedState(LocalAnimationFactor)
                    AnimatedContent(
                        modifier = Modifier
                            .fillMaxSize(),
                        targetState = pane,
                        transitionSpec = {
                            val animationType = getNavAnimation().value
                            val transitionType = kotlin.run {
                                if (
                                    initialState == null ||
                                    targetState == null
                                ) {
                                    return@run NavigationAnimationType.SWITCH
                                }

                                val isForward = targetState === detailPane
                                if (isForward) {
                                    NavigationAnimationType.GO_FORWARD
                                } else {
                                    NavigationAnimationType.GO_BACKWARD
                                }
                            }

                            NavigationAnimation.transform(
                                scale = updatedAnimationScale,
                                animationType = animationType,
                                transitionType = transitionType,
                            )
                        },
                        label = "",
                    ) { foo ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize(),
                        ) {
                            if (foo != null) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize(),
                                ) {
                                    foo()
                                }
                            } else {
                                Row(
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .alpha(0.035f),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(
                                        modifier = Modifier
                                            .size(48.dp),
                                        imageVector = Icons.Outlined.Lock,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onBackground,
                                    )
                                    Text(
                                        text = remember {
                                            keyguardSpan()
                                        },
                                        textAlign = TextAlign.Center,
                                        style = MaterialTheme.typography.displayLarge,
                                        color = MaterialTheme.colorScheme.onBackground,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PaneLayout(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            // Don't allow shadows and stuff to leak out of the
            // component's bounds.
            .clipToBounds(),
    ) {
        content()
    }
}
