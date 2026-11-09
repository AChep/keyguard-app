@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.artemchep.keyguard.feature.auth.keyguard.pairing

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.provider.keyguard.keyguardPlatformName
import com.artemchep.keyguard.ui.theme.ok
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private val StageHeight = 184.dp

private val NodeSize = 76.dp

private val MergedNodeSize = 112.dp

private val MaxHalfDistance = 112.dp

private val NodeIconSize = 32.dp

private val CheckIconSize = 44.dp

private val WaveAmplitude = 9.dp

private val ConfettiSize = 14.dp

private const val HALF_DISTANCE_RATIO = 0.27f

private const val LOOP_MILLIS = 2400

private const val SPIN_MILLIS = 28000

private const val BREATHE_SCALE = 0.04f

private const val PEER_BREATHE_PHASE = 0.5f

private const val VIEWFINDER_BREATHE_PHASE = 0.25f

private const val VIEWFINDER_SIZE = 0.72f

private const val PEER_GROW_FROM = 0.7f

private const val PARTICLE_COUNT = 18

private const val FULL_TURN = 360f

private const val ECHO_REACH = 2.1f

private const val BURST_REACH = 1.6f

private const val FAILURE_GAP = 0.55f

private const val PEER_GHOST_ALPHA = 0.45f

private const val CHECK_TWIST = -45f

private const val MERGE_DELAY_MILLIS = 380L

private const val BURST_MILLIS = 1100

private const val SHAKE_VELOCITY = -450f

private const val SHAKE_DAMPING = 0.18f

private const val SHAKE_STIFFNESS = 900f

/**
 * The stage of a pairing: this device and the other one as Material shapes, and the link
 * between them. It calls out while showing a code, scans, shakes hands, carries the data
 * and merges the two devices into one once they are linked. It is decoration: the steps
 * say the same in words.
 *
 * @param sending `true` while the data travels from this device to the other one.
 */
@Composable
fun PairingStage(
    phase: PairingStagePhase,
    modifier: Modifier = Modifier,
    sending: Boolean = true,
) {
    val motionEnabled = rememberMotionEnabled()
    val colors = rememberPairingStageColors()
    val shapes = remember { PairingStageShapes() }
    val loop = rememberLoop(LOOP_MILLIS, motionEnabled)
    val spin = rememberLoop(SPIN_MILLIS, motionEnabled)
    val motion = rememberPairingStageMotion(phase, motionEnabled)
    val moments = rememberPairingStageMoments(phase, motionEnabled)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(StageHeight)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        val density = LocalDensity.current
        val scene = PairingStageScene(
            nodePx = with(density) { NodeSize.toPx() },
            mergedPx = with(density) { MergedNodeSize.toPx() },
            halfDistancePx = min(
                constraints.maxWidth * HALF_DISTANCE_RATIO,
                with(density) { MaxHalfDistance.toPx() },
            ),
            shakePx = with(density) { 1.dp.toPx() },
            motion = motion,
            moments = moments,
            loop = loop,
            spin = spin,
            colors = colors,
            shapes = shapes,
            sending = sending,
        )
        Canvas(
            modifier = Modifier
                .matchParentSize(),
        ) {
            drawPairingStage(scene)
        }
        PairingStageIcons(
            scene = scene,
            thisIcon = keyguardPlatformIcon(CurrentPlatform.keyguardPlatformName()),
            peerIcon = Icons.Outlined.Devices,
        )
    }
}

/** The colors of the stage, from the theme. */
@Stable
private class PairingStageColors(
    val thisContainer: Color,
    val thisContent: Color,
    val peerContainer: Color,
    val peerContent: Color,
    val link: Color,
    val idle: Color,
    val error: Color,
    val errorContainer: Color,
    val done: Color,
    val onDone: Color,
    val confetti: List<Color>,
)

@Composable
private fun rememberPairingStageColors(): PairingStageColors {
    val scheme = MaterialTheme.colorScheme
    val ok = scheme.ok
    return remember(scheme, ok) {
        PairingStageColors(
            thisContainer = scheme.primaryContainer,
            thisContent = scheme.onPrimaryContainer,
            peerContainer = scheme.tertiaryContainer,
            peerContent = scheme.onTertiaryContainer,
            link = scheme.primary,
            idle = scheme.outline,
            error = scheme.error,
            errorContainer = scheme.errorContainer,
            done = scheme.primary,
            onDone = scheme.onPrimary,
            confetti = listOf(
                scheme.primary,
                scheme.tertiary,
                scheme.secondary,
                ok,
            ),
        )
    }
}

/** The unit outlines the stage draws with. */
private class PairingStageShapes {
    val thisNode = MaterialShapes.Cookie9Sided.toUnitPath()
    val peerNode = MaterialShapes.Cookie7Sided.toUnitPath()
    val merge = Morph(MaterialShapes.Cookie9Sided, MaterialShapes.Sunny)
    val mergePath = Path()
    val linkPath = Path()
    val confetti = listOf(
        MaterialShapes.Sunny,
        MaterialShapes.Cookie4Sided,
        MaterialShapes.Pill,
        MaterialShapes.Diamond,
        MaterialShapes.Clover4Leaf,
        MaterialShapes.Circle,
    ).map { it.toUnitPath() }
    val particles = particles(
        count = PARTICLE_COUNT,
        shapes = confetti.size,
        colors = 4,
    )
}

/** How far each part of the stage is, from 0 to 1, as it moves between phases. */
private class PairingStageMotion(
    val separation: State<Float>,
    val peer: State<Float>,
    val solid: State<Float>,
    val wave: State<Float>,
    val broadcast: State<Float>,
    val scan: State<Float>,
    val handshake: State<Float>,
    val transfer: State<Float>,
    val breathe: State<Float>,
    val failure: State<Float>,
    val merge: State<Float>,
)

@Composable
private fun rememberPairingStageMotion(
    phase: PairingStagePhase,
    motionEnabled: Boolean,
): PairingStageMotion {
    val transition = updateTransition(phase, label = "pairing_stage")
    val scheme = MaterialTheme.motionScheme
    val spatial: FiniteAnimationSpec<Float> = if (motionEnabled) scheme.slowSpatialSpec() else snap()
    val effects: FiniteAnimationSpec<Float> = if (motionEnabled) scheme.defaultEffectsSpec() else snap()

    @Composable
    fun animate(
        spec: FiniteAnimationSpec<Float>,
        target: (PairingStageTargets) -> Float,
    ): State<Float> = transition.animateFloat(
        transitionSpec = { spec },
        label = "pairing_stage_value",
        targetValueByState = { target(it.targets()) },
    )

    return PairingStageMotion(
        separation = animate(spatial) { it.separation },
        peer = animate(effects) { it.peer },
        solid = animate(effects) { it.solid },
        wave = animate(spatial) { it.wave },
        broadcast = animate(effects) { it.broadcast },
        scan = animate(effects) { it.scan },
        handshake = animate(effects) { it.handshake },
        transfer = animate(effects) { it.transfer },
        breathe = animate(effects) { it.breathe },
        failure = animate(effects) { it.failure },
        merge = animate(spatial) { it.merge },
    )
}

/** The one-shot moments: the jolt of a failure, the check and the confetti of a link. */
private class PairingStageMoments {
    val shake = Animatable(0f)
    val check = Animatable(0f)
    val burst = Animatable(0f)

    suspend fun play(
        phase: PairingStagePhase,
        motionEnabled: Boolean,
    ) {
        when (phase) {
            PairingStagePhase.DONE -> celebrate(motionEnabled)
            PairingStagePhase.FAILED -> {
                reset()
                if (motionEnabled) jolt()
            }

            else -> reset()
        }
    }

    private suspend fun reset() {
        check.snapTo(0f)
        burst.snapTo(0f)
    }

    /** A damped wobble, as if the devices bounced off each other. */
    private suspend fun jolt() {
        shake.snapTo(0f)
        shake.animateTo(
            targetValue = 0f,
            animationSpec = spring(
                dampingRatio = SHAKE_DAMPING,
                stiffness = SHAKE_STIFFNESS,
            ),
            initialVelocity = SHAKE_VELOCITY,
        )
    }

    private suspend fun celebrate(motionEnabled: Boolean) {
        if (!motionEnabled) {
            check.snapTo(1f)
            return
        }
        coroutineScope {
            launch {
                delay(MERGE_DELAY_MILLIS)
                check.animateTo(
                    targetValue = 1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioHighBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                )
            }
            launch {
                delay(MERGE_DELAY_MILLIS)
                burst.snapTo(0f)
                burst.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(BURST_MILLIS, easing = FastOutSlowInEasing),
                )
            }
        }
    }
}

@Composable
private fun rememberPairingStageMoments(
    phase: PairingStagePhase,
    motionEnabled: Boolean,
): PairingStageMoments {
    val moments = remember { PairingStageMoments() }
    LaunchedEffect(phase, motionEnabled) {
        moments.play(phase, motionEnabled)
    }
    return moments
}

/**
 * Everything one frame of the stage needs. Its values are read in the draw and layout
 * phases only, so the frames of the animation do not recompose.
 */
private class PairingStageScene(
    val nodePx: Float,
    val mergedPx: Float,
    val halfDistancePx: Float,
    val shakePx: Float,
    val motion: PairingStageMotion,
    val moments: PairingStageMoments,
    val loop: State<Float>,
    val spin: State<Float>,
    val colors: PairingStageColors,
    val shapes: PairingStageShapes,
    val sending: Boolean,
) {
    /** Where this device is, from the middle. */
    fun leftX() = -halfDistancePx * motion.separation.value + moments.shake.value * shakePx

    /** Where the other device is, from the middle. */
    fun rightX() = halfDistancePx * motion.separation.value - moments.shake.value * shakePx

    fun breathe(phase: Float = 0f) = 1f + BREATHE_SCALE *
        motion.breathe.value *
        sin(2f * PI.toFloat() * (loop.value + phase))

    fun spinDegrees() = spin.value * FULL_TURN

    /** The devices fade out as they merge. */
    fun nodesAlpha() = 1f - smoothStep(from = 0.55f, to = 0.95f, value = motion.merge.value)

    /** The icons fade out a little earlier. */
    fun iconsAlpha() = 1f - smoothStep(from = 0.3f, to = 0.7f, value = motion.merge.value)
}

private fun DrawScope.drawPairingStage(
    scene: PairingStageScene,
) {
    val center = Offset(size.width / 2f, size.height / 2f)
    val left = center + Offset(scene.leftX(), 0f)
    val right = center + Offset(scene.rightX(), 0f)
    drawStageEchoes(scene, left)
    drawStageLink(scene, left, right)
    drawStageViewfinder(scene, right)
    drawStagePeer(scene, right)
    drawUnitPath(
        path = scene.shapes.thisNode,
        center = left,
        size = scene.nodePx * scene.breathe(),
        color = scene.colors.thisContainer,
        rotation = scene.spinDegrees(),
        alpha = scene.nodesAlpha(),
    )
    drawStageMerge(scene, center)
}

/** This device calls out to the other one. */
private fun DrawScope.drawStageEchoes(
    scene: PairingStageScene,
    left: Offset,
) = drawEchoes(
    path = scene.shapes.thisNode,
    center = left,
    from = scene.nodePx,
    to = scene.nodePx * ECHO_REACH,
    progress = scene.loop.value,
    color = scene.colors.link,
    alpha = scene.motion.broadcast.value * scene.nodesAlpha(),
    strokeWidth = 2.dp.toPx(),
    rotation = scene.spinDegrees(),
)

/** The link, from the edge of one device to the other, and what travels along it. */
private fun DrawScope.drawStageLink(
    scene: PairingStageScene,
    left: Offset,
    right: Offset,
) {
    val motion = scene.motion
    val nodesAlpha = scene.nodesAlpha()
    val stroke = 2.dp.toPx()
    val start = left + Offset(scene.nodePx / 2f, 0f)
    val end = right - Offset(scene.nodePx / 2f, 0f)
    if (end.x - start.x <= stroke * 2f || nodesAlpha <= 0f) return
    val loop = scene.loop.value
    val link = LinkGeometry(
        start = start,
        end = end,
        amplitude = motion.wave.value * WaveAmplitude.toPx(),
        phase = loop,
    )
    drawLink(
        link = link,
        path = scene.shapes.linkPath,
        color = lerp(
            lerp(scene.colors.idle, scene.colors.link, motion.solid.value),
            scene.colors.error,
            motion.failure.value,
        ),
        strokeWidth = stroke * (1f + motion.solid.value / 2f),
        solid = motion.solid.value,
        dash = 6.dp.toPx(),
        dashPhase = loop * 2f,
        gap = motion.failure.value * FAILURE_GAP,
    )
    // Both devices talk while they shake hands.
    listOf(false, true).forEach { reverse ->
        drawLinkDots(
            link = link,
            progress = loop,
            count = 1,
            radius = 4.dp.toPx(),
            color = if (reverse) scene.colors.peerContent else scene.colors.link,
            alpha = motion.handshake.value * nodesAlpha,
            reverse = reverse,
        )
    }
    // The data travels to the device that joins.
    drawLinkDots(
        link = link,
        progress = (loop * 2f) % 1f,
        count = 4,
        radius = 5.dp.toPx(),
        color = scene.colors.link,
        alpha = motion.transfer.value * nodesAlpha,
        reverse = !scene.sending,
    )
}

/** This device scans the code of the other one. */
private fun DrawScope.drawStageViewfinder(
    scene: PairingStageScene,
    right: Offset,
) = drawViewfinder(
    center = right,
    halfSize = scene.nodePx * VIEWFINDER_SIZE * scene.breathe(phase = VIEWFINDER_BREATHE_PHASE),
    progress = scene.loop.value,
    color = scene.colors.link,
    alpha = scene.motion.scan.value * scene.nodesAlpha(),
    strokeWidth = 3.dp.toPx(),
)

/** The other device: a dashed ghost until it is there. */
private fun DrawScope.drawStagePeer(
    scene: PairingStageScene,
    right: Offset,
) {
    val peer = scene.motion.peer.value
    val breathe = scene.breathe(phase = PEER_BREATHE_PHASE)
    drawUnitPath(
        path = scene.shapes.peerNode,
        center = right,
        size = scene.nodePx * breathe,
        color = scene.colors.idle,
        rotation = -scene.spinDegrees(),
        alpha = (1f - peer) * scene.nodesAlpha(),
        style = Stroke(width = 2.dp.toPx()),
        dash = 5.dp.toPx(),
        dashPhase = -scene.loop.value * 10.dp.toPx(),
    )
    drawUnitPath(
        path = scene.shapes.peerNode,
        center = right,
        size = scene.nodePx * (PEER_GROW_FROM + (1f - PEER_GROW_FROM) * peer) * breathe,
        color = lerp(scene.colors.peerContainer, scene.colors.errorContainer, scene.motion.failure.value),
        rotation = -scene.spinDegrees(),
        alpha = peer * scene.nodesAlpha(),
    )
}

/** Linked: the two devices are one, and confetti flies. */
private fun DrawScope.drawStageMerge(
    scene: PairingStageScene,
    center: Offset,
) {
    val merge = scene.motion.merge.value
    if (merge > 0f) {
        val progress = smoothStep(from = 0.4f, to = 1f, value = merge)
        drawUnitPath(
            path = scene.shapes.merge.toUnitPath(progress, scene.shapes.mergePath),
            center = center,
            size = scene.nodePx + (scene.mergedPx - scene.nodePx) * progress,
            color = lerp(scene.colors.thisContainer, scene.colors.done, progress),
            rotation = scene.spinDegrees(),
            alpha = smoothStep(from = 0.45f, to = 0.75f, value = merge),
        )
    }
    drawBurst(
        particles = scene.shapes.particles,
        paths = scene.shapes.confetti,
        colors = scene.colors.confetti,
        center = center,
        from = scene.mergedPx / 2f,
        to = scene.mergedPx * BURST_REACH,
        maxSize = ConfettiSize.toPx(),
        progress = scene.moments.burst.value,
    )
}

/** The icons of the devices, and the check once they are linked. */
@Composable
private fun PairingStageIcons(
    scene: PairingStageScene,
    thisIcon: ImageVector,
    peerIcon: ImageVector,
) {
    Icon(
        modifier = Modifier
            .size(NodeIconSize)
            .offset { IntOffset(scene.leftX().roundToInt(), 0) }
            .graphicsLayer {
                val scale = scene.breathe()
                scaleX = scale
                scaleY = scale
                alpha = scene.iconsAlpha()
            },
        imageVector = thisIcon,
        contentDescription = null,
        tint = scene.colors.thisContent,
    )
    Icon(
        modifier = Modifier
            .size(NodeIconSize)
            .offset { IntOffset(scene.rightX().roundToInt(), 0) }
            .graphicsLayer {
                val peer = scene.motion.peer.value
                alpha = (PEER_GHOST_ALPHA + (1f - PEER_GHOST_ALPHA) * peer) * scene.iconsAlpha()
            },
        imageVector = peerIcon,
        contentDescription = null,
        tint = scene.colors.peerContent,
    )
    Icon(
        modifier = Modifier
            .size(CheckIconSize)
            .graphicsLayer {
                val value = scene.moments.check.value
                scaleX = value
                scaleY = value
                alpha = value.coerceIn(0f, 1f)
                rotationZ = (1f - value) * CHECK_TWIST
            },
        imageVector = Icons.Outlined.Check,
        contentDescription = null,
        tint = scene.colors.onDone,
    )
}
