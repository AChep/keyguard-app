package com.artemchep.keyguard.feature.auth.keyguard.pairing

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.graphics.shapes.Cubic
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin

// The drawing primitives of the pairing stage and of the linked devices hero: devices as
// Material shapes, the links between them, and what travels along the links.

/** Returns the outline of the polygon in its unit square, the space Material shapes live in. */
internal fun RoundedPolygon.toUnitPath(path: Path = Path()): Path = cubics.toPath(path)

/** Returns the outline of the morph at [progress] in its unit square. */
internal fun Morph.toUnitPath(
    progress: Float,
    path: Path = Path(),
): Path = asCubics(progress).toPath(path)

private fun List<Cubic>.toPath(path: Path): Path {
    path.rewind()
    val first = firstOrNull()
        ?: return path
    path.moveTo(first.anchor0X, first.anchor0Y)
    forEach { cubic ->
        path.cubicTo(
            cubic.control0X,
            cubic.control0Y,
            cubic.control1X,
            cubic.control1Y,
            cubic.anchor1X,
            cubic.anchor1Y,
        )
    }
    path.close()
    return path
}

/**
 * Draws the unit [path] as a shape of [size] pixels around the [center], turned by
 * [rotation] degrees. A [Stroke] is given in pixels, and so are the [dash] and the
 * [dashPhase] of a dashed one.
 */
@Suppress("LongParameterList") // A shape, where it is, and how it is drawn.
internal fun DrawScope.drawUnitPath(
    path: Path,
    center: Offset,
    size: Float,
    color: Color,
    rotation: Float = 0f,
    alpha: Float = 1f,
    style: DrawStyle = Fill,
    dash: Float = 0f,
    dashPhase: Float = 0f,
) {
    if (size <= 0f || alpha <= 0f) return
    // The path is scaled with the canvas, so is the stroke.
    val scaledStyle = when (style) {
        is Stroke -> Stroke(
            width = style.width / size,
            cap = style.cap,
            join = style.join,
            pathEffect = if (dash > 0f) {
                PathEffect.dashPathEffect(
                    intervals = floatArrayOf(dash / size, dash / size),
                    phase = dashPhase / size,
                )
            } else {
                style.pathEffect
            },
        )

        else -> style
    }
    withTransform(
        transformBlock = {
            rotate(degrees = rotation, pivot = center)
            translate(left = center.x - size / 2f, top = center.y - size / 2f)
            scale(scaleX = size, scaleY = size, pivot = Offset.Zero)
        },
    ) {
        drawPath(
            path = path,
            color = color,
            alpha = alpha.coerceIn(0f, 1f),
            style = scaledStyle,
        )
    }
}

/**
 * A link from [start] to [end]: a wave of [amplitude] pixels with [waves] crests that
 * travels with [phase], pinned at both ends.
 */
internal class LinkGeometry(
    val start: Offset,
    val end: Offset,
    private val amplitude: Float = 0f,
    private val phase: Float = 0f,
    private val waves: Float = 2f,
) {
    private val dx = end.x - start.x

    private val dy = end.y - start.y

    val length = hypot(dx, dy)

    private val nx = if (length > 0f) -dy / length else 0f

    private val ny = if (length > 0f) dx / length else 0f

    /** Returns the point of the link at [t] in 0..1. */
    fun point(t: Float): Offset {
        val envelope = sin(PI.toFloat() * t)
        val offset = amplitude * envelope * sin(2f * PI.toFloat() * (waves * t - phase))
        return Offset(
            x = start.x + dx * t + nx * offset,
            y = start.y + dy * t + ny * offset,
        )
    }

    /** Fills the [path] with the link from [from] to [to], both in 0..1. */
    fun toPath(
        path: Path,
        from: Float = 0f,
        to: Float = 1f,
    ): Path {
        path.rewind()
        if (to <= from) return path
        val steps = LINK_STEPS
        val first = point(from)
        path.moveTo(first.x, first.y)
        for (i in 1..steps) {
            val t = from + (to - from) * i / steps
            val p = point(t)
            path.lineTo(p.x, p.y)
        }
        return path
    }
}

private const val LINK_STEPS = 48

/**
 * Draws the [link] solid at [solid] 1, dashed at 0 with dashes that march with [dashPhase],
 * and cut in the middle by a [gap] in 0..1.
 */
@Suppress("LongParameterList") // A stroke, its dashes and its gap.
internal fun DrawScope.drawLink(
    link: LinkGeometry,
    path: Path,
    color: Color,
    strokeWidth: Float,
    solid: Float,
    dash: Float,
    dashPhase: Float,
    gap: Float = 0f,
) {
    val halfGap = (gap / 2f).coerceIn(0f, 0.5f)
    val segments = if (halfGap > 0f) {
        listOf(0f to 0.5f - halfGap, 0.5f + halfGap to 1f)
    } else {
        listOf(0f to 1f)
    }
    val dashed = PathEffect.dashPathEffect(
        intervals = floatArrayOf(dash, dash),
        phase = -dashPhase * dash * 2f,
    )
    segments.forEach { (from, to) ->
        link.toPath(path, from, to)
        if (solid < 1f) {
            drawPath(
                path = path,
                color = color,
                alpha = 1f - solid,
                style = Stroke(
                    width = strokeWidth,
                    cap = StrokeCap.Round,
                    pathEffect = dashed,
                ),
            )
        }
        if (solid > 0f) {
            drawPath(
                path = path,
                color = color,
                alpha = solid,
                style = Stroke(
                    width = strokeWidth,
                    cap = StrokeCap.Round,
                ),
            )
        }
    }
}

/**
 * Draws [count] dots that travel along the [link] with [progress] in 0..1, from its start
 * or, if [reverse], from its end. They fade in and out at the ends.
 */
@Suppress("LongParameterList") // The dots and their trail.
internal fun DrawScope.drawLinkDots(
    link: LinkGeometry,
    progress: Float,
    count: Int,
    radius: Float,
    color: Color,
    alpha: Float,
    reverse: Boolean = false,
) {
    if (alpha <= 0f || count <= 0) return
    repeat(count) { i ->
        val raw = (progress + i.toFloat() / count) % 1f
        val t = if (reverse) 1f - raw else raw
        val fade = sin(PI.toFloat() * raw)
        drawCircle(
            color = color,
            radius = radius * (0.6f + 0.4f * fade),
            center = link.point(t),
            alpha = (alpha * fade).coerceIn(0f, 1f),
        )
    }
}

/**
 * Draws [count] echoes of the unit [path] that grow from [from] to [to] pixels around the
 * [center] with [progress] in 0..1, fading as they grow.
 */
@Suppress("LongParameterList") // The echoes and how they grow.
internal fun DrawScope.drawEchoes(
    path: Path,
    center: Offset,
    from: Float,
    to: Float,
    progress: Float,
    color: Color,
    alpha: Float,
    strokeWidth: Float,
    rotation: Float = 0f,
    count: Int = 3,
) {
    if (alpha <= 0f) return
    repeat(count) { i ->
        val t = (progress + i.toFloat() / count) % 1f
        val fade = (1f - t) * (1f - t)
        drawUnitPath(
            path = path,
            center = center,
            size = from + (to - from) * t,
            color = color,
            rotation = rotation,
            alpha = alpha * fade * ECHO_ALPHA,
            style = Stroke(width = strokeWidth),
        )
    }
}

private const val ECHO_ALPHA = 0.7f

/**
 * Draws the corners of a viewfinder of [halfSize] around the [center], and a line that
 * sweeps it with [progress] in 0..1.
 */
@Suppress("LongParameterList") // The viewfinder and its sweep.
internal fun DrawScope.drawViewfinder(
    center: Offset,
    halfSize: Float,
    progress: Float,
    color: Color,
    alpha: Float,
    strokeWidth: Float,
) {
    if (alpha <= 0f) return
    val corner = halfSize * VIEWFINDER_CORNER
    val left = center.x - halfSize
    val right = center.x + halfSize
    val top = center.y - halfSize
    val bottom = center.y + halfSize
    val corners = listOf(
        Triple(Offset(left, top), 1f, 1f),
        Triple(Offset(right, top), -1f, 1f),
        Triple(Offset(left, bottom), 1f, -1f),
        Triple(Offset(right, bottom), -1f, -1f),
    )
    corners.forEach { (p, sx, sy) ->
        drawLine(color, p, p + Offset(corner * sx, 0f), strokeWidth, StrokeCap.Round, alpha = alpha)
        drawLine(color, p, p + Offset(0f, corner * sy), strokeWidth, StrokeCap.Round, alpha = alpha)
    }
    // Back and forth.
    val sweep = 1f - abs(2f * progress - 1f)
    val y = top + (bottom - top) * sweep
    drawLine(
        brush = Brush.horizontalGradient(
            colors = listOf(Color.Transparent, color, Color.Transparent),
            startX = left,
            endX = right,
        ),
        start = Offset(left, y),
        end = Offset(right, y),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round,
        alpha = alpha,
    )
}

private const val VIEWFINDER_CORNER = 0.32f

/** A bit of confetti: where it flies, how big it is, how it spins, and its look. */
internal class Particle(
    /** The direction, in radians. */
    val angle: Float,
    /** How far it flies, as a part of the burst. */
    val distance: Float,
    /** Its size, as a part of the largest one. */
    val size: Float,
    /** How far it spins, in degrees. */
    val spin: Float,
    val shape: Int,
    val color: Int,
)

/** Returns [count] bits of confetti, the same for the same [seed]. */
internal fun particles(
    count: Int,
    shapes: Int,
    colors: Int,
    seed: Int = 7,
): List<Particle> {
    val random = kotlin.random.Random(seed)
    return List(count) { i ->
        val jitter = (random.nextFloat() - 0.5f) * PARTICLE_JITTER
        Particle(
            angle = (2f * PI.toFloat() * i / count) + jitter,
            distance = 0.55f + random.nextFloat() * 0.45f,
            size = 0.55f + random.nextFloat() * 0.45f,
            spin = (random.nextFloat() - 0.5f) * PARTICLE_SPIN,
            shape = i % shapes,
            color = random.nextInt(colors),
        )
    }
}

private const val PARTICLE_JITTER = 0.5f

private const val PARTICLE_SPIN = 540f

/**
 * Draws the [particles] that burst from the [center] with [progress] in 0..1: from
 * [from] pixels out to [to], spinning, shrinking and fading.
 */
@Suppress("LongParameterList") // The burst and its looks.
internal fun DrawScope.drawBurst(
    particles: List<Particle>,
    paths: List<Path>,
    colors: List<Color>,
    center: Offset,
    from: Float,
    to: Float,
    maxSize: Float,
    progress: Float,
) {
    if (progress <= 0f || progress >= 1f) return
    val fade = (1f - progress)
    particles.forEach { particle ->
        val distance = from + (to - from) * particle.distance * progress
        val position = Offset(
            x = center.x + distance * kotlin.math.cos(particle.angle),
            y = center.y + distance * sin(particle.angle),
        )
        drawUnitPath(
            path = paths[particle.shape % paths.size],
            center = position,
            size = maxSize * particle.size * (1f - 0.4f * progress),
            color = colors[particle.color % colors.size],
            rotation = particle.spin * progress,
            alpha = fade,
        )
    }
}

/** Returns a smooth step from 0 at [from] to 1 at [to]. */
internal fun smoothStep(
    from: Float,
    to: Float,
    value: Float,
): Float {
    val t = ((value - from) / (to - from)).coerceIn(0f, 1f)
    return t * t * (SMOOTH_STEP_RISE - 2f * t)
}

private const val SMOOTH_STEP_RISE = 3f
