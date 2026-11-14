package com.myos.home

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Gravity projected into the screen plane.
 *
 * Coordinates use Android view space: +X points right and +Y points down.
 * When the phone is nearly face-up/face-down, the projection becomes too small
 * to define a stable 2D water slope. In that case we retain the last valid
 * direction instead of allowing the surface angle to jump unpredictably.
 */
data class GravityFrame2D(
    val downX: Float,
    val downY: Float
) {
    init {
        require(downX.isFinite() && downY.isFinite())
        require(abs(hypot(downX, downY) - 1f) < 0.002f)
    }

    val angleRadians: Float
        get() = atan2(downX, downY)

    companion object {
        private const val MIN_GRAVITY = 0.15f
        private const val MIN_SCREEN_PROJECTION = 0.12f

        fun fromAcceleration(
            ax: Float,
            ay: Float,
            az: Float,
            previous: GravityFrame2D? = null
        ): GravityFrame2D {
            if (!ax.isFinite() || !ay.isFinite() || !az.isFinite()) {
                return previous ?: GravityFrame2D(0f, 1f)
            }

            val magnitude = sqrt(ax * ax + ay * ay + az * az)
            if (magnitude < MIN_GRAVITY) {
                return previous ?: GravityFrame2D(0f, 1f)
            }

            val screenMagnitude = hypot(ax, ay)
            if (screenMagnitude / magnitude < MIN_SCREEN_PROJECTION) {
                return previous ?: GravityFrame2D(0f, 1f)
            }

            val inverse = 1f / screenMagnitude
            // Match the original MYOS screen-space convention: positive device X
            // tilts the apparent gravity direction toward screen-left.
            return GravityFrame2D(-ax * inverse, ay * inverse)
        }
    }
}

/** The gravity-aligned, area-preserving free-surface line for a rectangular viewport. */
data class WaterSurfacePlane2D(
    val normalX: Float,
    val normalY: Float,
    val offset: Float,
    val centerX: Float,
    val centerY: Float,
    val tangentX: Float,
    val tangentY: Float,
    val fillFraction: Float
)

/**
 * Lightweight, renderer-independent water-surface motion.
 *
 * The surface angle is a damped response to projected gravity. A fixed-step
 * integrator makes the result substantially less sensitive to irregular
 * Android frame times. The free-surface plane is solved separately and keeps
 * the requested liquid area constant as the device rotates.
 */
class WaterSurfaceSimulation2D(
    fillFraction: Float = 0.45f,
    private val stiffness: Float = 38f,
    private val damping: Float = 8.4f
) {
    var fillFraction: Float = fillFraction.coerceIn(0.05f, 0.95f)
        set(value) {
            field = value.coerceIn(0.05f, 0.95f)
        }

    var angleRadians: Float = 0f
        private set
    var angularVelocity: Float = 0f
        private set
    var wavePhase: Float = 0f
        private set
    var gravityFrame: GravityFrame2D = GravityFrame2D(0f, 1f)
        private set

    private var targetAngle = 0f
    private var accumulator = 0f
    private var previousAngularVelocity = 0f

    // A compact 1D wave field along the free surface. Values are normalized
    // to the viewport's short dimension; fixed endpoints keep the surface stable.
    private val waveCount = 48
    private val waveDisplacement = FloatArray(waveCount)
    private val waveVelocity = FloatArray(waveCount)
    private val nextWaveDisplacement = FloatArray(waveCount)
    private val waveTension = 34f
    private val waveDamping = 7.5f

    // Reused clipping buffers: solveSurface() may run every frame, so it must
    // not allocate polygon arrays inside its binary-search loop.
    private val clipInputX = FloatArray(4)
    private val clipInputY = FloatArray(4)
    private val clipOutputX = FloatArray(8)
    private val clipOutputY = FloatArray(8)

    fun setGravity(ax: Float, ay: Float, az: Float) {
        gravityFrame = GravityFrame2D.fromAcceleration(ax, ay, az, gravityFrame)
        targetAngle = gravityFrame.angleRadians
    }

    /** Adds a bounded impulse, for example from a detected phone shake or drawer gesture. */
    fun addImpulse(angularImpulse: Float) {
        if (!angularImpulse.isFinite()) return
        val impulse = angularImpulse.coerceIn(-5f, 5f)
        angularVelocity = (angularVelocity + impulse).coerceIn(-9f, 9f)
        for (i in 1 until waveCount - 1) {
            val position = i / (waveCount - 1f) * 2f - 1f
            waveVelocity[i] += impulse * position * 0.0025f
        }
    }

    /** Injects a localized ripple from a finger drag across the drawer reveal. */
    fun disturbSurface(position: Float, impulse: Float) {
        if (!position.isFinite() || !impulse.isFinite()) return
        val center = (position.coerceIn(-1f, 1f) + 1f) * 0.5f
        val strength = impulse.coerceIn(-2f, 2f)
        for (i in 1 until waveCount - 1) {
            val sample = i / (waveCount - 1f)
            val distance = sample - center
            val falloff = 1f / (1f + distance * distance * 90f)
            waveVelocity[i] = (waveVelocity[i] + strength * falloff * 0.035f)
                .coerceIn(-0.8f, 0.8f)
        }
    }

    /** Interpolated physical wave height at a position from -1 (left) to +1 (right). */
    fun waveHeightAt(position: Float): Float {
        if (!position.isFinite()) return 0f
        val sample = ((position.coerceIn(-1f, 1f) + 1f) * 0.5f) * (waveCount - 1)
        val left = sample.toInt().coerceIn(0, waveCount - 1)
        val right = (left + 1).coerceAtMost(waveCount - 1)
        val fraction = sample - left
        return waveDisplacement[left] * (1f - fraction) + waveDisplacement[right] * fraction
    }

    /**
     * Advance by elapsed seconds. Long stalls are capped to avoid a spiral of
     * catch-up work after the app resumes or the drawer becomes visible again.
     */
    fun advance(elapsedSeconds: Float) {
        if (!elapsedSeconds.isFinite() || elapsedSeconds <= 0f) return

        accumulator += elapsedSeconds.coerceAtMost(0.08f)
        val step = 1f / 120f
        var steps = 0
        while (accumulator >= step && steps < 8) {
            integrate(step)
            accumulator -= step
            steps++
        }
        if (steps == 8 && accumulator >= step) accumulator = 0f
    }

    private fun integrate(dt: Float) {
        val oldAngularVelocity = angularVelocity
        val delta = wrapAngle(targetAngle - angleRadians)
        val angularAcceleration = stiffness * delta - damping * angularVelocity
        angularVelocity = (angularVelocity + angularAcceleration * dt).coerceIn(-9f, 9f)
        angleRadians = wrapAngle(angleRadians + angularVelocity * dt)
        wavePhase = wrapAngle(wavePhase + dt * (1.6f + abs(angularVelocity)))

        // Phone rotation accelerates the liquid surface; the resulting wave
        // propagates through neighboring samples instead of being a decorative sine.
        val angularJerk = ((angularVelocity - oldAngularVelocity) / dt)
            .coerceIn(-45f, 45f)
        for (i in 1 until waveCount - 1) {
            val position = i / (waveCount - 1f) * 2f - 1f
            val laplacian = waveDisplacement[i - 1] -
                2f * waveDisplacement[i] + waveDisplacement[i + 1]
            val acceleration = laplacian * waveTension -
                waveVelocity[i] * waveDamping +
                angularJerk * position * 0.00045f
            waveVelocity[i] = (waveVelocity[i] + acceleration * dt).coerceIn(-0.8f, 0.8f)
            nextWaveDisplacement[i] = (waveDisplacement[i] + waveVelocity[i] * dt)
                .coerceIn(-0.035f, 0.035f)
        }
        nextWaveDisplacement[0] = 0f
        nextWaveDisplacement[waveCount - 1] = 0f
        for (i in 0 until waveCount) {
            waveDisplacement[i] = nextWaveDisplacement[i]
        }
        previousAngularVelocity = angularVelocity
    }

    /**
     * Finds the line whose lower half-plane contains exactly fillFraction of
     * the viewport area. Binary search plus rectangle clipping avoids the
     * orientation-dependent fill drift of simply rotating a fixed-height mask.
     */
    fun solveSurface(width: Float, height: Float): WaterSurfacePlane2D? {
        if (!width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f) return null

        val downX = sin(angleRadians)
        val downY = cos(angleRadians)
        val normalX = -downX
        val normalY = -downY
        val centerX = width * 0.5f
        val centerY = height * 0.5f
        val halfDiagonal = hypot(width, height) * 0.5f
        val targetArea = fillFraction * width * height

        var low = -halfDiagonal
        var high = halfDiagonal
        repeat(22) {
            val mid = (low + high) * 0.5f
            val area = clippedArea(width, height, normalX, normalY, mid)
            if (area < targetArea) low = mid else high = mid
        }

        val offset = (low + high) * 0.5f
        return WaterSurfacePlane2D(
            normalX = normalX,
            normalY = normalY,
            offset = offset,
            centerX = centerX,
            centerY = centerY,
            tangentX = -normalY,
            tangentY = normalX,
            fillFraction = fillFraction
        )
    }

    private fun clippedArea(
        width: Float,
        height: Float,
        nx: Float,
        ny: Float,
        offset: Float
    ): Float {
        // A half-plane clips a rectangle into a polygon with at most six vertices.
        clipInputX[0] = 0f
        clipInputX[1] = width
        clipInputX[2] = width
        clipInputX[3] = 0f
        clipInputY[0] = 0f
        clipInputY[1] = 0f
        clipInputY[2] = height
        clipInputY[3] = height
        var count = 0

        for (i in 0 until 4) {
            val j = (i + 1) % 4
            val da = (clipInputX[i] - width * 0.5f) * nx +
                (clipInputY[i] - height * 0.5f) * ny - offset
            val db = (clipInputX[j] - width * 0.5f) * nx +
                (clipInputY[j] - height * 0.5f) * ny - offset

            if (da <= 0f) {
                clipOutputX[count] = clipInputX[i]
                clipOutputY[count] = clipInputY[i]
                count++
            }

            if ((da < 0f && db > 0f) || (da > 0f && db < 0f)) {
                val t = da / (da - db)
                clipOutputX[count] = clipInputX[i] + (clipInputX[j] - clipInputX[i]) * t
                clipOutputY[count] = clipInputY[i] + (clipInputY[j] - clipInputY[i]) * t
                count++
            }
        }

        if (count < 3) return 0f
        var twiceArea = 0f
        for (i in 0 until count) {
            val j = (i + 1) % count
            twiceArea += clipOutputX[i] * clipOutputY[j] - clipOutputX[j] * clipOutputY[i]
        }
        return abs(twiceArea) * 0.5f
    }

    private fun wrapAngle(value: Float): Float {
        var angle = value
        val fullTurn = 2f * PI.toFloat()
        while (angle > PI) angle -= fullTurn
        while (angle < -PI) angle += fullTurn
        return angle
    }
}
