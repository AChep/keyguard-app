package com.artemchep.keyguard.ui.tags

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.edit
import com.artemchep.keyguard.res.tag_remove_content_description
import com.artemchep.keyguard.ui.util.DividerColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** The initial velocity of a chip's shake, in dp per second. */
private const val CHIP_SHAKE_VELOCITY = 300f

/** Same as the theme's tinted containers, so chips stand out on a field. */
private const val CHIP_TINT_ALPHA = 0.20f

private val ChipPressedCornerRadius = 8.dp

/** The primary color tinted over the surface of a text field. */
internal val ColorScheme.tagChipContainer: Color
    get() = primary
        .copy(alpha = CHIP_TINT_ALPHA)
        .compositeOver(surfaceVariant)

/**
 * A tag entered into a [TagsField]: a pill that turns into a
 * rounded square while pressed, and red when armed for removal.
 */
@Composable
internal fun TagInputChip(
    modifier: Modifier = Modifier,
    text: String,
    armed: Boolean,
    shake: TagShake?,
    flyFrom: Offset?,
    onClick: (() -> Unit)?,
    onRemove: (() -> Unit)?,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    val colors = animateTagChipColors(armed = armed)
    // Morph from a pill to a rounded square while pressed,
    // like the expressive buttons do.
    val cornerRadius by animateDpAsState(
        targetValue = if (pressed || focused || armed) ChipPressedCornerRadius else ChipHeight / 2,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
    )
    val shape = RoundedCornerShape(cornerRadius)
    val motion = rememberTagChipMotion(
        flyFrom = flyFrom,
        shake = shake,
    )
    // A chip of a picked suggestion starts as the outlined
    // suggestion and fills in as it flies into place.
    val fill = motion.fill.value
    val outlineColor = DividerColor
    CompositionLocalProvider(
        LocalContentColor provides colors.content,
    ) {
        Row(
            modifier = modifier
                .onPlaced(motion::onPlaced)
                .graphicsLayer {
                    motion.applyTo(this)
                }
                .height(ChipHeight)
                .widthIn(max = ChipMaxWidth)
                .clip(shape)
                .background(colors.container.copy(alpha = colors.container.alpha * fill))
                .border(Dp.Hairline, outlineColor.copy(alpha = outlineColor.alpha * (1f - fill)), shape)
                .clickable(
                    interactionSource = interactionSource,
                    indication = LocalIndication.current,
                    enabled = onClick != null,
                    onClickLabel = stringResource(Res.string.edit),
                    role = Role.Button,
                    onClick = { onClick?.invoke() },
                )
                .padding(
                    start = 12.dp,
                    end = if (onRemove != null) 4.dp else 12.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TagInputChipContent(
                text = text,
                onRemove = onRemove,
            )
        }
    }
}

@Composable
private fun RowScope.TagInputChipContent(
    text: String,
    onRemove: (() -> Unit)?,
) {
    Text(
        modifier = Modifier
            .weight(1f, fill = false),
        text = text,
        style = MaterialTheme.typography.labelLarge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    if (onRemove != null) {
        Spacer(
            modifier = Modifier
                .width(4.dp),
        )
        TagRemoveButton(
            tag = text,
            onClick = onRemove,
        )
    }
}

@Composable
private fun TagRemoveButton(
    tag: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .clickable(
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            modifier = Modifier
                .size(18.dp),
            imageVector = Icons.Outlined.Close,
            contentDescription = stringResource(Res.string.tag_remove_content_description, tag),
        )
    }
}

private class TagChipColors(
    val container: Color,
    val content: Color,
)

@Composable
private fun animateTagChipColors(
    armed: Boolean,
): TagChipColors {
    val colorScheme = MaterialTheme.colorScheme
    val animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Color>()
    val container by animateColorAsState(
        targetValue = if (armed) colorScheme.errorContainer else colorScheme.tagChipContainer,
        animationSpec = animationSpec,
    )
    val content by animateColorAsState(
        targetValue = if (armed) colorScheme.onErrorContainer else colorScheme.onPrimaryContainer,
        animationSpec = animationSpec,
    )
    return TagChipColors(
        container = container,
        content = content,
    )
}

/** Asks the chip of the [tag] to shake; every instance shakes once. */
internal class TagShake(
    val tag: String,
)

/**
 * Moves a chip: flies it in from where its suggestion was,
 * and shakes it when the tag is entered again.
 */
@Stable
private class TagChipMotion(
    private val flyFrom: Offset?,
    private val scope: CoroutineScope,
    private val flightSpec: FiniteAnimationSpec<Offset>,
) {
    private val flight = Animatable(Offset.Zero, Offset.VectorConverter)
    private var flightPending = flyFrom != null

    /** The horizontal offset of the shake, in dp. */
    val shake = Animatable(0f)

    /** How filled in the chip is, outlined at zero. */
    val fill = Animatable(if (flyFrom != null) 0f else 1f)

    fun onPlaced(coordinates: LayoutCoordinates) {
        val origin = flyFrom
        if (!flightPending || origin == null) {
            return
        }
        flightPending = false
        val delta = origin - coordinates.positionInWindow()
        // Undispatched, so the chip never draws a
        // frame at its final position first.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            flight.snapTo(delta)
            flight.animateTo(Offset.Zero, flightSpec)
        }
    }

    fun applyTo(layer: GraphicsLayerScope) = with(layer) {
        translationX = flight.value.x + shake.value.dp.toPx()
        translationY = flight.value.y
    }
}

@Composable
private fun rememberTagChipMotion(
    flyFrom: Offset?,
    shake: TagShake?,
): TagChipMotion {
    val motionScheme = MaterialTheme.motionScheme
    val scope = rememberCoroutineScope()
    val motion = remember {
        TagChipMotion(
            flyFrom = flyFrom,
            scope = scope,
            flightSpec = motionScheme.defaultSpatialSpec(),
        )
    }
    LaunchedEffect(motion) {
        motion.fill.animateTo(1f, motionScheme.defaultEffectsSpec())
    }
    LaunchedEffect(shake) {
        if (shake == null) return@LaunchedEffect
        // A kick that a bouncy spring settles
        // reads as a shake of the head.
        motion.shake.animateTo(
            targetValue = 0f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioHighBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
            initialVelocity = CHIP_SHAKE_VELOCITY,
        )
    }
    return motion
}

internal class LingeringEntry(
    val key: String,
    val visibility: MutableTransitionState<Boolean>,
)

/**
 * Keeps removed items in the list until their exit animation
 * finishes, so they can animate out in place.
 */
@Composable
internal fun rememberLingeringList(
    items: List<String>,
): List<LingeringEntry> {
    val holder = remember { LingeringList() }
    return holder.update(items)
}

private class LingeringList {
    private var entries = emptyList<LingeringEntry>()
    private var initialized = false

    fun update(items: List<String>): List<LingeringEntry> {
        val oldEntries = entries.associateBy { it.key }
        val out = items.mapTo(ArrayList()) { item ->
            val entry = oldEntries[item]
                ?: LingeringEntry(
                    key = item,
                    // Items that are there from the start
                    // do not animate in.
                    visibility = MutableTransitionState(!initialized),
                )
            entry.visibility.targetState = true
            entry
        }
        val keys = items.toSet()
        entries.forEachIndexed { index, entry ->
            if (entry.key in keys) {
                return@forEachIndexed
            }
            val visibility = entry.visibility
            val gone = !visibility.currentState && visibility.isIdle
            if (gone) {
                return@forEachIndexed
            }
            visibility.targetState = false
            out.add(index.coerceAtMost(out.size), entry)
        }
        entries = out
        initialized = true
        return out
    }
}
