@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.artemchep.keyguard.feature.applink

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.feature.auth.keyguard.pairing.LinkGeometry
import com.artemchep.keyguard.feature.auth.keyguard.pairing.PairingStage
import com.artemchep.keyguard.feature.auth.keyguard.pairing.PairingStagePhase
import com.artemchep.keyguard.feature.auth.keyguard.pairing.drawLink
import com.artemchep.keyguard.feature.auth.keyguard.pairing.drawLinkDots
import com.artemchep.keyguard.feature.auth.keyguard.pairing.drawUnitPath
import com.artemchep.keyguard.feature.auth.keyguard.pairing.keyguardPlatformIcon
import com.artemchep.keyguard.feature.auth.keyguard.pairing.rememberLoop
import com.artemchep.keyguard.feature.auth.keyguard.pairing.rememberMotionEnabled
import com.artemchep.keyguard.feature.auth.keyguard.pairing.toUnitPath
import com.artemchep.keyguard.feature.keyguardsync.devices.KeyguardDevicesState
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.app_link_hero_text
import com.artemchep.keyguard.res.app_link_hero_title
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.theme.ok
import com.artemchep.keyguard.ui.theme.okContainer
import com.artemchep.keyguard.ui.theme.onOkContainer
import com.artemchep.keyguard.ui.theme.onWarningContainer
import com.artemchep.keyguard.ui.theme.warning
import com.artemchep.keyguard.ui.theme.warningContainer
import org.jetbrains.compose.resources.stringResource
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** This device, waiting for another one, and what linking devices does. */
@Composable
internal fun AppLinkUnlinkedHero() {
    PairingStage(
        phase = PairingStagePhase.IDLE,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.textHorizontalPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(Res.string.app_link_hero_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(Res.string.app_link_hero_text),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = LocalContentColor.current
                .combineAlpha(MediumEmphasisAlpha),
        )
    }
}

private val MeshHeight = 208.dp

private val MeshCenterSize = 68.dp

private val MeshPeerSize = 50.dp

private val MeshPairSize = 72.dp

private val MeshMaxHalfDistance = 112.dp

private val MeshRadiusX = 148.dp

private val MeshRadiusY = 72.dp

private val MeshPairIconSize = 32.dp

private val MeshCenterIconSize = 30.dp

private val MeshPeerIconSize = 22.dp

private const val MESH_MAX_PEERS = 6

private const val MESH_HALF_DISTANCE_RATIO = 0.27f

private const val MESH_RADIUS_X_RATIO = 0.36f

private const val MESH_WOBBLE = 0.07f

private const val MESH_EDGE = 0.46f

private const val FULL_TURN = 360f

/** How a device looks in the mesh: its container, its icon, and its link. */
private class MeshLook(
    val container: Color,
    val content: Color,
    val link: Color,
    /** `true` while the devices have a session: the link is solid and busy. */
    val live: Boolean,
)

@Composable
private fun KeyguardDevicesState.Health?.meshLook(): MeshLook {
    val scheme = MaterialTheme.colorScheme
    return when (this) {
        KeyguardDevicesState.Health.OK -> MeshLook(scheme.okContainer, scheme.onOkContainer, scheme.ok, live = true)
        KeyguardDevicesState.Health.WARNING -> MeshLook(
            scheme.warningContainer,
            scheme.onWarningContainer,
            scheme.warning,
            live = true,
        )

        KeyguardDevicesState.Health.ERROR -> MeshLook(
            scheme.errorContainer,
            scheme.onErrorContainer,
            scheme.error,
            live = false,
        )

        KeyguardDevicesState.Health.NEUTRAL,
        null,
        -> MeshLook(
            scheme.surfaceContainerHighest,
            scheme.onSurfaceVariant,
            scheme.outline,
            live = false,
        )
    }
}

/**
 * The linked devices as a mesh: this device in the middle, the [peers] around it. A link
 * is solid and busy while the devices have a session, and dashed while they have none.
 * One peer sits beside this device, as on the pairing stage. Decoration: the list says
 * the same in words.
 */
@Composable
internal fun AppLinkMesh(
    thisPlatform: String,
    peers: List<KeyguardDevicesState.Device>,
    modifier: Modifier = Modifier,
) {
    if (peers.isEmpty()) {
        PairingStage(
            modifier = modifier,
            phase = PairingStagePhase.IDLE,
        )
        return
    }
    val shown = if (peers.size > MESH_MAX_PEERS) peers.take(MESH_MAX_PEERS - 1) else peers
    val overflow = peers.size - shown.size
    val looks = meshLooks(shown, overflow)
    val clock = rememberMeshClock()
    val shapes = remember { MeshShapes() }
    val colors = MeshColors(
        thisContainer = MaterialTheme.colorScheme.primaryContainer,
        thisContent = MaterialTheme.colorScheme.onPrimaryContainer,
        mesh = MaterialTheme.colorScheme.outlineVariant,
    )

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(MeshHeight)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        val density = LocalDensity.current
        val single = looks.size == 1
        val scene = MeshScene(
            count = looks.size,
            shown = shown.size,
            looks = looks,
            centerPx = with(density) { (if (single) MeshPairSize else MeshCenterSize).toPx() },
            peerPx = with(density) { (if (single) MeshPairSize else MeshPeerSize).toPx() },
            halfDistancePx = min(
                constraints.maxWidth * MESH_HALF_DISTANCE_RATIO,
                with(density) { MeshMaxHalfDistance.toPx() },
            ),
            radiusXPx = min(
                constraints.maxWidth * MESH_RADIUS_X_RATIO,
                with(density) { MeshRadiusX.toPx() },
            ),
            radiusYPx = with(density) { MeshRadiusY.toPx() },
            clock = clock,
            shapes = shapes,
            colors = colors,
        )
        Canvas(
            modifier = Modifier
                .matchParentSize(),
        ) {
            drawMesh(scene)
        }
        AppLinkMeshIcons(
            scene = scene,
            thisPlatform = thisPlatform,
            shown = shown,
            overflow = overflow,
        )
    }
}

/** The icons of the devices on the mesh, and a count of the rest. */
@Composable
private fun AppLinkMeshIcons(
    scene: MeshScene,
    thisPlatform: String,
    shown: List<KeyguardDevicesState.Device>,
    overflow: Int,
) {
    val single = scene.count == 1
    Icon(
        modifier = Modifier
            .size(if (single) MeshPairIconSize else MeshCenterIconSize)
            .offset { scene.thisOffset().round() },
        imageVector = keyguardPlatformIcon(thisPlatform),
        contentDescription = null,
        tint = scene.colors.thisContent,
    )
    shown.forEachIndexed { index, device ->
        Icon(
            modifier = Modifier
                .size(if (single) MeshPairIconSize else MeshPeerIconSize)
                .offset { scene.peerOffset(index).round() },
            imageVector = keyguardPlatformIcon(device.platform),
            contentDescription = null,
            tint = scene.looks[index].content,
        )
    }
    if (overflow > 0) {
        Text(
            modifier = Modifier
                .offset { scene.peerOffset(scene.looks.lastIndex).round() },
            // A count, as on a badge.
            text = "+$overflow",
            style = MaterialTheme.typography.labelLarge,
            color = scene.looks.last().content,
        )
    }
}

/** How each peer looks, with one more that stands for the [overflow]. */
@Composable
private fun meshLooks(
    shown: List<KeyguardDevicesState.Device>,
    overflow: Int,
): List<MeshLook> = shown.map { it.health.meshLook() } +
    List(if (overflow > 0) 1 else 0) { null.meshLook() }

/** The clocks of the mesh: the busy links, the drift of the peers and the spin of the shapes. */
private class MeshClock(
    val loop: State<Float>,
    val drift: State<Float>,
    val spin: State<Float>,
)

@Composable
private fun rememberMeshClock(): MeshClock {
    val motionEnabled = rememberMotionEnabled()
    return MeshClock(
        loop = rememberLoop(MESH_LOOP_MILLIS, motionEnabled),
        drift = rememberLoop(MESH_DRIFT_MILLIS, motionEnabled),
        spin = rememberLoop(MESH_SPIN_MILLIS, motionEnabled),
    )
}

private class MeshColors(
    val thisContainer: Color,
    val thisContent: Color,
    val mesh: Color,
)

/** The unit outlines the mesh draws with. */
private class MeshShapes {
    val thisNode = MaterialShapes.Cookie9Sided.toUnitPath()
    val peerNode = MaterialShapes.Cookie7Sided.toUnitPath()
    val moreNode = MaterialShapes.Circle.toUnitPath()
    val linkPath = Path()
}

/**
 * Everything one frame of the mesh needs. Its values are read in the draw and layout
 * phases only, so the frames of the animation do not recompose.
 *
 * @param count the peers on the mesh, with the one that stands for the rest.
 * @param shown the peers that are devices; the one after them stands for the rest.
 */
private class MeshScene(
    val count: Int,
    val shown: Int,
    val looks: List<MeshLook>,
    val centerPx: Float,
    val peerPx: Float,
    val halfDistancePx: Float,
    val radiusXPx: Float,
    val radiusYPx: Float,
    val clock: MeshClock,
    val shapes: MeshShapes,
    val colors: MeshColors,
) {
    private val single get() = count == 1

    fun thisOffset(): Offset = if (single) Offset(-halfDistancePx, 0f) else Offset.Zero

    fun peerOffset(index: Int): Offset {
        if (single) return Offset(halfDistancePx, 0f)
        val turn = 2f * PI.toFloat()
        val wobble = MESH_WOBBLE * sin(turn * (clock.drift.value + index.toFloat() / count))
        val angle = -turn / 4f + turn * index / count + wobble
        return Offset(cos(angle) * radiusXPx, sin(angle) * radiusYPx)
    }

    fun spinDegrees() = clock.spin.value * FULL_TURN
}

private fun DrawScope.drawMesh(
    scene: MeshScene,
) {
    val center = Offset(size.width / 2f, size.height / 2f)
    val me = center + scene.thisOffset()
    // Every device syncs with every other one.
    if (scene.count > 2) {
        repeat(scene.count) { i ->
            drawLine(
                color = scene.colors.mesh,
                start = center + scene.peerOffset(i),
                end = center + scene.peerOffset((i + 1) % scene.count),
                strokeWidth = 1.dp.toPx(),
            )
        }
    }
    repeat(scene.count) { i ->
        drawMeshLink(scene, me, center + scene.peerOffset(i), i)
    }
    repeat(scene.count) { i ->
        val more = i >= scene.shown
        drawUnitPath(
            path = if (more) scene.shapes.moreNode else scene.shapes.peerNode,
            center = center + scene.peerOffset(i),
            size = scene.peerPx,
            color = scene.looks[i].container,
            rotation = if (more) 0f else -scene.spinDegrees(),
        )
    }
    drawUnitPath(
        path = scene.shapes.thisNode,
        center = me,
        size = scene.centerPx,
        color = scene.colors.thisContainer,
        rotation = scene.spinDegrees(),
    )
}

/** The link to the peer [index]: solid and busy while the devices have a session. */
private fun DrawScope.drawMeshLink(
    scene: MeshScene,
    me: Offset,
    peer: Offset,
    index: Int,
) {
    val look = scene.looks[index]
    val dx = peer.x - me.x
    val dy = peer.y - me.y
    val distance = hypot(dx, dy).coerceAtLeast(1f)
    val direction = Offset(dx / distance, dy / distance)
    val link = LinkGeometry(
        start = me + direction * (scene.centerPx * MESH_EDGE),
        end = peer - direction * (scene.peerPx * MESH_EDGE),
    )
    drawLink(
        link = link,
        path = scene.shapes.linkPath,
        color = look.link,
        strokeWidth = 2.dp.toPx(),
        solid = if (look.live) 1f else 0f,
        dash = 5.dp.toPx(),
        dashPhase = 0f,
    )
    if (look.live) {
        // The sync goes both ways.
        drawLinkDots(
            link = link,
            progress = (scene.clock.loop.value + index * DOT_SPREAD) % 1f,
            count = 1,
            radius = 3.5f.dp.toPx(),
            color = look.link,
            alpha = 1f,
            reverse = index % 2 == 1,
        )
    }
}

private fun Offset.round() = IntOffset(x.roundToInt(), y.roundToInt())

private const val MESH_LOOP_MILLIS = 2600

private const val MESH_DRIFT_MILLIS = 14000

private const val MESH_SPIN_MILLIS = 36000

private const val DOT_SPREAD = 0.37f
