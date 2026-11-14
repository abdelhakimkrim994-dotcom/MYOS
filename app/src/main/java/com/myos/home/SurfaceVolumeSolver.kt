package com.myos.home

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Finds the free-surface plane offset that clips the rounded tank to the
 * requested fill fraction. A deterministic point cloud makes bisection
 * inexpensive and repeatable on mobile CPUs.
 */
internal class SurfaceVolumeSolver(
    private val resolution: Int = 22,
    private val targetFill: Float = 0.5f
) {
    private val samples: FloatArray

    init {
        require(resolution >= 2)
        val points = ArrayList<Float>(resolution * resolution * resolution * 3)
        val bx = 0.387f
        val by = 0.867f
        val bz = 0.287f
        val radius = 0.025f
        for (z in 0 until resolution) {
            val pz = -bz + (z + 0.5f) * (2f * bz / resolution)
            for (y in 0 until resolution) {
                val py = -by + (y + 0.5f) * (2f * by / resolution)
                for (x in 0 until resolution) {
                    val px = -bx + (x + 0.5f) * (2f * bx / resolution)
                    val qx = abs(px) - bx + radius
                    val qy = abs(py) - by + radius
                    val qz = abs(pz) - bz + radius
                    val outside = sqrt(max(qx, 0f) * max(qx, 0f) +
                        max(qy, 0f) * max(qy, 0f) +
                        max(qz, 0f) * max(qz, 0f))
                    val inside = min(max(qx, max(qy, qz)), 0f)
                    if (outside + inside - radius <= 0f) {
                        points.add(px)
                        points.add(py)
                        points.add(pz)
                    }
                }
            }
        }
        samples = points.toFloatArray()
    }

    fun offsetForHalfFill(nx: Float, ny: Float, nz: Float): Float {
        // A malformed normal must never poison the shader uniform.
        if (samples.size < 3 || !nx.isFinite() || !ny.isFinite() || !nz.isFinite()) return 0f
        val lengthSquared = nx * nx + ny * ny + nz * nz
        if (!lengthSquared.isFinite() || lengthSquared < 1e-8f) return 0f
        val length = sqrt(lengthSquared)
        val x = nx / length
        val y = ny / length
        val z = nz / length
        var minProjection = Float.POSITIVE_INFINITY
        var maxProjection = Float.NEGATIVE_INFINITY
        var i = 0
        while (i + 2 < samples.size) {
            val projection = samples[i] * x + samples[i + 1] * y + samples[i + 2] * z
            if (!projection.isFinite()) return 0f
            minProjection = min(minProjection, projection)
            maxProjection = max(maxProjection, projection)
            i += 3
        }
        if (!minProjection.isFinite() || !maxProjection.isFinite() || minProjection > maxProjection) return 0f
        var low = -maxProjection
        var high = -minProjection
        repeat(18) {
            val mid = (low + high) * 0.5f
            var below = 0
            i = 0
            while (i + 2 < samples.size) {
                val projection = samples[i] * x + samples[i + 1] * y + samples[i + 2] * z + mid
                if (projection <= 0f) below++
                i += 3
            }
            val fill = below.toFloat() / (samples.size / 3)
            // Increasing the offset raises the plane and decreases the
            // clipped water volume. Too much water therefore means raise it.
            if (fill > targetFill) low = mid else high = mid
        }
        val result = (low + high) * 0.5f
        return if (result.isFinite()) result.coerceIn(-1.5f, 1.5f) else 0f
    }
}
