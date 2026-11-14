package com.myos.home

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * Stable, volume-preserving shallow-water surface solver.
 *
 * The solver runs at a fixed 1/120 s step with a small cross-shaped
 * height blur and zero-mean correction. It is a 2D surface approximation,
 * not a full 3D fluid solver.
 */
internal class FluidSurfaceSimulation(private val size: Int = 40) {
    private val count = size * size
    private val height = FloatArray(count)
    private val nextHeight = FloatArray(count)
    private val smoothHeight = FloatArray(count)
    private val velocityX = FloatArray(count)
    private val velocityZ = FloatArray(count)
    private val nextVelocityX = FloatArray(count)
    private val nextVelocityZ = FloatArray(count)
    private val uploadBuffer: FloatBuffer =
        ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private val spacing = 2f / (size - 1)
    private val waterDepth = 0.30f
    private val gravity = 1.35f
    private val damping = 2.4f
    private val fixedStep = 1f / 120f
    private var accumulator = 0f
    private var impulseX = 0f
    private var impulseZ = 0f

    @Synchronized
    fun addImpulse(x: Float, z: Float) {
        impulseX = (impulseX + x).coerceIn(-3.5f, 3.5f)
        impulseZ = (impulseZ + z).coerceIn(-3.5f, 3.5f)
    }

    @Synchronized
    fun step(deltaSeconds: Float) {
        if (!deltaSeconds.isFinite()) {
            reset()
            return
        }
        accumulator = min(accumulator + deltaSeconds.coerceIn(0f, 0.033f), fixedStep * 4f)
        var steps = 0
        while (accumulator >= fixedStep && steps < 4) {
            simulate(fixedStep)
            accumulator -= fixedStep
            steps++
        }
    }

    private fun simulate(dt: Float) {
        val ix = impulseX
        val iz = impulseZ
        impulseX = 0f
        impulseZ = 0f

        // Inject momentum smoothly in the interior rather than exciting boundary cells.
        if (ix != 0f || iz != 0f) {
            for (z in 1 until size - 1) {
                for (x in 1 until size - 1) {
                    val nx = (x - (size - 1) * 0.5f) / (size * 0.5f)
                    val nz = (z - (size - 1) * 0.5f) / (size * 0.5f)
                    val r2 = nx * nx + nz * nz
                    val weight = 0.35f + 0.65f * (1f - min(1f, r2))
                    val i = z * size + x
                    velocityX[i] += ix * weight
                    velocityZ[i] += iz * weight
                }
            }
        }

        val invTwoDx = 0.5f / spacing
        val invDx2 = 1f / (spacing * spacing)
        val dampingFactor = max(0f, 1f - damping * dt)

        // Semi-discrete shallow-water continuity and momentum equations.
        for (z in 1 until size - 1) {
            for (x in 1 until size - 1) {
                val i = z * size + x
                val left = i - 1
                val right = i + 1
                val down = i - size
                val up = i + size

                val divergence =
                    (velocityX[right] - velocityX[left] + velocityZ[up] - velocityZ[down]) * invTwoDx
                nextHeight[i] = height[i] - waterDepth * divergence * dt

                val gradientX = (height[right] - height[left]) * invTwoDx
                val gradientZ = (height[up] - height[down]) * invTwoDx
                val laplacian = (height[left] + height[right] + height[down] + height[up] - 4f * height[i]) * invDx2

                nextVelocityX[i] = (velocityX[i] - gravity * gradientX * dt) * dampingFactor
                nextVelocityZ[i] = (velocityZ[i] - gravity * gradientZ * dt) * dampingFactor
                // Mild viscosity damps grid-scale noise without injecting height.
                nextVelocityX[i] += 0.00035f * laplacian * dt
                nextVelocityZ[i] += 0.00035f * laplacian * dt
            }
        }

        // No-through-flow boundaries with zero-normal-gradient height.
        for (x in 0 until size) {
            val top = (size - 1) * size + x
            val bottom = x
            nextHeight[top] = nextHeight[top - size]
            nextHeight[bottom] = nextHeight[bottom + size]
            nextVelocityX[top] = nextVelocityX[top - size]
            nextVelocityX[bottom] = nextVelocityX[bottom + size]
            nextVelocityZ[top] = 0f
            nextVelocityZ[bottom] = 0f
        }
        for (z in 0 until size) {
            val left = z * size
            val right = left + size - 1
            nextHeight[left] = nextHeight[left + 1]
            nextHeight[right] = nextHeight[right - 1]
            nextVelocityX[left] = 0f
            nextVelocityX[right] = 0f
            nextVelocityZ[left] = nextVelocityZ[left + 1]
            nextVelocityZ[right] = nextVelocityZ[right - 1]
        }

        System.arraycopy(nextHeight, 0, height, 0, count)
        System.arraycopy(nextVelocityX, 0, velocityX, 0, count)
        System.arraycopy(nextVelocityZ, 0, velocityZ, 0, count)

        // Three-tap-style cross blur: center plus its four immediate neighbors.
        for (z in 1 until size - 1) {
            for (x in 1 until size - 1) {
                val i = z * size + x
                smoothHeight[i] = 0.50f * height[i] +
                    0.125f * (height[i - 1] + height[i + 1] + height[i - size] + height[i + size])
            }
        }
        for (x in 0 until size) {
            smoothHeight[x] = smoothHeight[x + size]
            smoothHeight[(size - 1) * size + x] = smoothHeight[(size - 2) * size + x]
        }
        for (z in 0 until size) {
            smoothHeight[z * size] = smoothHeight[z * size + 1]
            smoothHeight[z * size + size - 1] = smoothHeight[z * size + size - 2]
        }
        System.arraycopy(smoothHeight, 0, height, 0, count)

        // Preserve the mean surface height (and therefore the represented water volume).
        var sum = 0f
        for (value in height) sum += value
        val mean = sum / count
        for (i in height.indices) height[i] = (height[i] - mean).coerceIn(-0.045f, 0.045f)

        // Remove the tiny residual mean introduced by the safety limiter.
        sum = 0f
        for (value in height) sum += value
        val correction = sum / count
        for (i in height.indices) height[i] -= correction

        // Last-line defence: one invalid cell must not poison the whole rendered surface.
        for (i in height.indices) {
            if (!height[i].isFinite() || !velocityX[i].isFinite() || !velocityZ[i].isFinite()) {
                reset()
                return
            }
            velocityX[i] = velocityX[i].coerceIn(-1f, 1f)
            velocityZ[i] = velocityZ[i].coerceIn(-1f, 1f)
            height[i] = height[i].coerceIn(-0.045f, 0.045f)
        }
    }

    @Synchronized
    private fun reset() {
        height.fill(0f)
        nextHeight.fill(0f)
        smoothHeight.fill(0f)
        velocityX.fill(0f)
        velocityZ.fill(0f)
        nextVelocityX.fill(0f)
        nextVelocityZ.fill(0f)
        accumulator = 0f
        impulseX = 0f
        impulseZ = 0f
    }

    @Synchronized
    fun writeHeightTexture(buffer: FloatBuffer) {
        buffer.clear()
        buffer.put(height)
        buffer.position(0)
    }

    fun createUploadBuffer(): FloatBuffer = uploadBuffer
    fun resolution(): Int = size
}
