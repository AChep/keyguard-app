package com.artemchep.keyguard.wear.ui

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.wear.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnState
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.CircularProgressIndicatorDefaults
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.artemchep.keyguard.ui.FabScope
import com.artemchep.keyguard.ui.FabState
import com.artemchep.keyguard.ui.MutableFabScope
import com.artemchep.keyguard.ui.ProvideScaffoldLocalValues
import com.artemchep.keyguard.ui.surface.LocalSurfaceColor
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.LocalExpressive
import com.google.android.horologist.compose.layout.ColumnItemType
import com.google.android.horologist.compose.layout.rememberResponsiveColumnPadding

private val WearScaffoldHorizontalPadding = 6.dp
private val WearScaffoldVerticalPadding = 56.dp

/**
 * A simple vertically scrolling scaffold for form-like or static Wear screens.
 *
 * This variant renders a centered [ListHeader] from [title], places the rest of the
 * UI inside a regular [Column], and reserves a bottom area for an optional floating
 * action. In this module it is used for screens that do not need
 * [TransformingLazyColumn]-based scaling behavior, such as login forms and static
 * information screens.
 *
 * [containerColor] is accepted for API consistency with other scaffolds, but this
 * implementation does not currently apply it directly to the rendered background.
 *
 * Example:
 * ```kotlin
 * WearScaffoldColumn(
 *     title = stringResource(Res.string.datasafety_header_title),
 * ) {
 *     DataSafetyScreenContent(
 *         row = { modifier, title, value ->
 *             WearDataSafetyRow(
 *                 modifier = modifier,
 *                 title = title,
 *                 value = value,
 *             )
 *         },
 *     )
 * }
 * ```
 */
@Composable
fun WearScaffoldColumn(
    title: String,
    floatingActionState: State<FabState?> = rememberUpdatedState(newValue = null),
    floatingActionButton: @Composable FabScope.() -> Unit = {},
    expressive: Boolean = LocalExpressive.current,
    containerColor: Color = LocalSurfaceColor.current,
    content: @Composable ColumnScope.() -> Unit,
) {
    val expandedState = remember {
        mutableStateOf(true)
    }
    ProvideScaffoldLocalValues(
        expressive = expressive,
    ) {
        AppScaffold() {

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        horizontal = WearScaffoldHorizontalPadding,
                        vertical = WearScaffoldVerticalPadding,
                    ),
            ) {
                ListHeader {
                    Text(
                        modifier = Modifier
                            .padding(horizontal = Dimens.textHorizontalPadding)
                            .fillMaxWidth(),
                        text = title,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(
                    modifier = Modifier
                        .height(16.dp),
                )

                ProxyMaterial3Styles {
                    content()
                }

                val scope = remember(
                    floatingActionState,
                    expandedState,
                ) {
                    MutableFabScope(
                        state = floatingActionState,
                        expanded = expandedState,
                    )
                }
                Spacer(
                    modifier = Modifier
                        .height(16.dp),
                )
                Box(
                    modifier = Modifier
                        .padding(horizontal = Dimens.textHorizontalPadding)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    floatingActionButton(scope)
                }
            }
        }
    }
}

/**
 * A Wear screen scaffold backed by [TransformingLazyColumn] with an automatically
 * generated header from [title] and optional [icon].
 *
 * This is the common entry point for list-style screens in the Wear app. Use it when
 * a standard centered title header is enough and the body should be expressed as lazy
 * list items that receive a [TransformationSpec] for Wear surface transformations.
 *
 * Prefer the overload that accepts [header] when the first list item needs a custom
 * layout or direct access to [TransformingLazyColumnItemScope].
 *
 * [containerColor] is accepted for API consistency with other scaffolds, but this
 * implementation does not currently apply it directly to the rendered background.
 *
 * Example:
 * ```kotlin
 * WearScaffoldScreen(
 *     title = stringResource(Res.string.addaccount_method_phone_title),
 *     floatingActionState = rememberUpdatedState(
 *         FabState(
 *             onClick = state.onActionClick,
 *             model = null,
 *         ),
 *     ),
 *     floatingActionButton = {
 *         DefaultEdgeButton(
 *             icon = {
 *                 Icon(
 *                     imageVector = Icons.Outlined.PhoneAndroid,
 *                     contentDescription = null,
 *                 )
 *             },
 *             text = {
 *                 Text(text = state.actionText)
 *             },
 *         )
 *     },
 * ) { transformationSpec ->
 *     item("status") {
 *         WearListLabel(
 *             modifier = Modifier
 *                 .transformedHeight(this, transformationSpec),
 *             text = textResource(state.statusText),
 *             transformation = SurfaceTransformation(transformationSpec),
 *         )
 *     }
 * }
 * ```
 */
@Composable
fun WearScaffoldScreen(
    icon: ImageVector? = null,
    title: String?,
    overlay: (@Composable BoxScope.() -> Unit)? = null,
    floatingActionState: State<FabState?> = rememberUpdatedState(newValue = null),
    floatingActionButton: @Composable EdgeButtonScope.() -> Unit = {},
    transformationSpec: TransformationSpec = rememberTransformationSpec(),
    expressive: Boolean = LocalExpressive.current,
    containerColor: Color = LocalSurfaceColor.current,
    contentPadding: PaddingValues = rememberResponsiveColumnPadding(
        first = ColumnItemType.ListHeader,
        last = ColumnItemType.Button,
    ),
    content: TransformingLazyColumnScope.(TransformationSpec) -> Unit,
) {
    WearScaffoldScreen(
        header = if (title != null) {
            // composable
            { spec ->
                ListHeader(
                    modifier = Modifier
                        .transformedHeight(this, spec),
                    transformation = SurfaceTransformation(spec),
                ) {
                    if (icon != null) {
                        Icon(
                            modifier = Modifier
                                .size(16.dp)
                                .align(Alignment.CenterVertically),
                            imageVector = icon,
                            contentDescription = null,
                        )
                        Spacer(
                            modifier = Modifier
                                .width(8.dp),
                        )
                    }
                    Text(
                        text = title,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            null
        },
        overlay = overlay,
        floatingActionState = floatingActionState,
        floatingActionButton = floatingActionButton,
        transformationSpec = transformationSpec,
        expressive = expressive,
        containerColor = containerColor,
        contentPadding = contentPadding,
        content = content,
    )
}

/**
 * A Wear screen scaffold backed by [TransformingLazyColumn] with a caller-provided
 * header item.
 *
 * Use this overload when the default title header is not sufficient and the header
 * needs direct access to [TransformingLazyColumnItemScope] and [TransformationSpec].
 * In this module it is used for custom header layouts such as confirmation screens,
 * account/send detail headers, and home screen header actions.
 *
 * The [content] lambda should apply Wear transformations to each list item, typically
 * by using `Modifier.transformedHeight(this, transformationSpec)` together with
 * [SurfaceTransformation].
 *
 * [containerColor] is accepted for API consistency with other scaffolds, but this
 * implementation does not currently apply it directly to the rendered background.
 *
 * Example:
 * ```kotlin
 * WearScaffoldScreen(
 *     header = { transformationSpec ->
 *         ListHeader(
 *             modifier = Modifier.transformedHeight(this, transformationSpec),
 *             transformation = SurfaceTransformation(transformationSpec),
 *         ) {
 *             Text(
 *                 text = title,
 *                 textAlign = TextAlign.Center,
 *             )
 *         }
 *     },
 *     overlay = {
 *         WearScaffoldLoader(visible = isLoading)
 *     },
 * ) { transformationSpec ->
 *     item("message") {
 *         WearListLabel(
 *             modifier = Modifier
 *                 .transformedHeight(this, transformationSpec),
 *             text = message,
 *             transformation = SurfaceTransformation(transformationSpec),
 *         )
 *     }
 * }
 * ```
 */
@Composable
fun WearScaffoldScreen(
    header: (@Composable TransformingLazyColumnItemScope.(TransformationSpec) -> Unit)? = null,
    overlay: (@Composable BoxScope.() -> Unit)? = null,
    floatingActionState: State<FabState?> = rememberUpdatedState(newValue = null),
    floatingActionButton: @Composable EdgeButtonScope.() -> Unit = {},
    transformationSpec: TransformationSpec = rememberTransformationSpec(),
    expressive: Boolean = LocalExpressive.current,
    containerColor: Color = LocalSurfaceColor.current,
    contentPadding: PaddingValues = rememberResponsiveColumnPadding(
        first = ColumnItemType.ListHeader,
        last = ColumnItemType.Button,
    ),
    content: TransformingLazyColumnScope.(TransformationSpec) -> Unit,
) {
    val listState = rememberTransformingLazyColumnState()
    ProvideScaffoldLocalValues(
        expressive = expressive,
    ) {
        val expandedState = remember {
            mutableStateOf(true)
        }
        val scope = remember(
            floatingActionState,
            expandedState,
        ) {
            MutableEdgeButtonScope(
                state = floatingActionState,
                expanded = expandedState,
                listState = listState,
            )
        }

        AppScaffold() {
            ProxyMaterial3Styles {
                if (scope.state.value != null) {
                    ScreenScaffold(
                        scrollState = listState,
                        // Define custom spacing between [EdgeButton]
                        // and [ScalingLazyColumn].
                        edgeButtonSpacing = 15.dp,
                        edgeButton = {
                            floatingActionButton(scope)
                        },
                        contentPadding = contentPadding,
                    ) { innerContentPadding ->
                        ScreenScaffoldContentLazyColumn(
                            state = listState,
                            contentPadding = innerContentPadding,
                            header = header,
                            transformationSpec = transformationSpec,
                            content = content,
                        )
                    }
                } else {
                    ScreenScaffold(
                        scrollState = listState,
                        contentPadding = contentPadding,
                    ) { innerContentPadding ->
                        ScreenScaffoldContentLazyColumn(
                            state = listState,
                            contentPadding = innerContentPadding,
                            header = header,
                            transformationSpec = transformationSpec,
                            content = content,
                        )
                    }
                }

                if (overlay != null) {
                    overlay()
                }
            }
        }
    }
}

/**
 * Draws a full-screen loading indicator inside a scaffold overlay.
 *
 * This helper is intended to be used from the [overlay] slot of [WearScaffoldScreen]
 * so loading state can be shown without replacing the underlying content tree. The
 * box fills the available space and applies Wear progress indicator padding; the
 * indicator itself is only rendered when [visible] is `true`.
 *
 * Example:
 * ```kotlin
 * WearScaffoldScreen(
 *     title = null,
 *     overlay = {
 *         WearScaffoldLoader(
 *             modifier = Modifier,
 *             visible = isLoading,
 *         )
 *     },
 * ) {
 *     // Keep content in place while progress is shown above it.
 * }
 * ```
 */
@Composable
fun BoxScope.WearScaffoldLoader(
    modifier: Modifier = Modifier,
    visible: Boolean = true,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(CircularProgressIndicatorDefaults.FullScreenPadding),
    ) {
        if (visible) {
            CircularProgressIndicator(
                modifier = Modifier
                    .fillMaxSize(),
            )
        }
    }
}


@Composable
private fun ScreenScaffoldContentLazyColumn(
    state: TransformingLazyColumnState,
    contentPadding: PaddingValues,
    header: (@Composable TransformingLazyColumnItemScope.(TransformationSpec) -> Unit)?,
    transformationSpec: TransformationSpec,
    content: TransformingLazyColumnScope.(TransformationSpec) -> Unit,
) {
    TransformingLazyColumn(
        modifier = Modifier
            .fillMaxSize(),
        state = state,
        contentPadding = contentPadding,
    ) {
        if (header != null) item("header") {
            header(transformationSpec)
        }
        content(transformationSpec)
    }
}

@Stable
interface EdgeButtonScope : FabScope {
    val listState: TransformingLazyColumnState
}

class MutableEdgeButtonScope(
    override val state: State<FabState?>,
    override val expanded: State<Boolean>,
    override val listState: TransformingLazyColumnState,
) : EdgeButtonScope

/**
 * Renders the standard Wear edge action button for a [WearScaffoldScreen].
 *
 * The button reads click handling and enabled state from the current [FabState]
 * exposed by the surrounding [EdgeButtonScope]. Callers usually provide only the
 * visual content through [icon] and [text], while the action comes from the
 * scaffold's `floatingActionState`.
 *
 * Example:
 * ```kotlin
 * WearScaffoldScreen(
 *     title = AppTitle,
 *     floatingActionState = rememberCreateVaultFabState(loadableState),
 *     floatingActionButton = {
 *         DefaultEdgeButton(
 *             icon = {
 *                 Icon(
 *                     imageVector = Icons.Outlined.Add,
 *                     contentDescription = null,
 *                 )
 *             },
 *             text = {
 *                 Text(text = stringResource(Res.string.setup_button_create_vault))
 *             },
 *         )
 *     },
 * ) { transformationSpec ->
 *     // Lazy content
 * }
 * ```
 */
@Composable
fun EdgeButtonScope.DefaultEdgeButton(
    icon: @Composable () -> Unit,
    text: @Composable () -> Unit,
) {
    val latestFabState = state.value
    val latestOnClick by rememberUpdatedState(latestFabState?.onClick)
    EdgeButton(
        onClick = {
            latestOnClick?.invoke()
        },
        enabled = latestOnClick != null,
        modifier =
            // In case user starts scrolling from the EdgeButton.
            Modifier.scrollable(
                listState,
                orientation = Orientation.Vertical,
                reverseDirection = true,
                // An overscroll effect should be applied to the EdgeButton for proper
                // scrolling behavior.
                overscrollEffect = rememberOverscrollEffect(),
            ),
    ) {
        Box(
            modifier = Modifier
                .sizeIn(
                    maxWidth = 16.dp,
                    maxHeight = 16.dp,
                ),
        ) {
            icon()
        }
        Spacer(
            modifier = Modifier
                .width(2.dp),
        )
        text()
    }
}
