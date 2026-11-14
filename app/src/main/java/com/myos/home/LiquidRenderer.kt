package com.myos.home

import android.hardware.SensorManager
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal class LiquidRenderer(private val onDiagnostics: (String) -> Unit) : GLSurfaceView.Renderer {
    @Volatile private var targetGravityX = 0f
    @Volatile private var targetGravityY = 1f
    @Volatile private var targetGravityZ = 0f
    @Volatile private var gravityJitterG = 0f
    @Volatile private var rawSensorX = 0f
    @Volatile private var rawSensorY = 0f
    @Volatile private var rawSensorZ = 0f
    private val shakeLock = Any()
    private var shake = 0f
    private var pendingShakeX = 0f
    private var pendingShakeZ = 0f
    private var filteredGravityX = 0f
    private var filteredGravityY = 1f
    private var filteredGravityZ = 0f
    private var acceptedGravityX = 0f
    private var acceptedGravityY = 1f
    private var acceptedGravityZ = 0f
    private var gravityFilterInitialized = false
    private var lastSensorTimestamp = 0L
    private val jitterTimes = LongArray(128)
    private val jitterX = FloatArray(128)
    private val jitterY = FloatArray(128)
    private val jitterZ = FloatArray(128)
    private var jitterIndex = 0
    private var jitterCount = 0
    private var springGravityX = 0f
    private var springGravityY = 1f
    private var springGravityZ = 0f
    private var springVelocityX = 0f
    private var springVelocityY = 0f
    private var springVelocityZ = 0f
    private val fluidSimulation = FluidSurfaceSimulation()
    private var fluidHeightTexture = 0
    private var fluidHeightHandle = 0
    private var flowHandle = 0
    private var program = 0
    private var timeHandle = 0
    private var upHandle = 0
    private var eyeHandle = 0
    private var resolutionHandle = 0
    private var shakeHandle = 0
    private var surfaceOffsetHandle = 0
    private val volumeSolver = SurfaceVolumeSolver()
    private var lastGoodSurfaceOffset = 0f
    private val upSignX = -1f
    private val upSignZ = 1f
    private val computedUp = FloatArray(3)
    private var neutralGravityX = 0f
    private var neutralGravityZ = 0f
    @Volatile private var eyeX = 0f
    @Volatile private var eyeY = 0f
    private var eyeFilterInitialized = false
    private var lastEyeTimestamp = 0L
    private var frameCounter = 0L
    private var lastDiagnosticsNanos = 0L
    private var startNanos = 0L
    private var lastNanos = 0L
    private lateinit var vertexBuffer: FloatBuffer

    @Synchronized
    fun setGravity(
        x: Float, y: Float, z: Float, timestampNanos: Long,
        rawX: Float, rawY: Float, rawZ: Float
    ) {
        rawSensorX = rawX
        rawSensorY = rawY
        rawSensorZ = rawZ
        // Slow neutral tracking prevents long-term sensor bias from moving the camera.
        // A separate 100 ms eye filter keeps parallax responsive without adding lag
        // to the gravity vector used by the water volume.
        if (!eyeFilterInitialized) {
            neutralGravityX = x
            neutralGravityZ = z
            lastEyeTimestamp = timestampNanos
            eyeFilterInitialized = true
        } else {
            val eyeDt = ((timestampNanos - lastEyeTimestamp) / 1_000_000_000f).coerceIn(0.001f, 0.1f)
            lastEyeTimestamp = timestampNanos
            val neutralAlpha = 1f - kotlin.math.exp(-eyeDt / 4f)
            neutralGravityX += (x - neutralGravityX) * neutralAlpha
            neutralGravityZ += (z - neutralGravityZ) * neutralAlpha
            val targetEyeX = ((x - neutralGravityX) / 0.35f).coerceIn(-1f, 1f)
            val targetEyeY = (-(z - neutralGravityZ) / 0.35f).coerceIn(-1f, 1f)
            val eyeAlpha = 1f - kotlin.math.exp(-eyeDt / 0.10f)
            eyeX += (targetEyeX - eyeX) * eyeAlpha
            eyeY += (targetEyeY - eyeY) * eyeAlpha
        }
        val magnitude = kotlin.math.sqrt(x * x + y * y + z * z)
        if (!magnitude.isFinite() || magnitude < 0.35f) return
        val rx = x / magnitude
        val ry = y / magnitude
        val rz = z / magnitude

        // Keep a two-second rolling estimate of stationary sensor spread.
        jitterTimes[jitterIndex] = timestampNanos
        jitterX[jitterIndex] = rx
        jitterY[jitterIndex] = ry
        jitterZ[jitterIndex] = rz
        jitterIndex = (jitterIndex + 1) % jitterTimes.size
        jitterCount = min(jitterCount + 1, jitterTimes.size)
        var sx = 0f; var sy = 0f; var sz = 0f; var n = 0
        for (i in 0 until jitterCount) {
            if (timestampNanos - jitterTimes[i] <= 2_000_000_000L) {
                sx += jitterX[i]; sy += jitterY[i]; sz += jitterZ[i]; n++
            }
        }
        if (n > 1) {
            val mx = sx / n; val my = sy / n; val mz = sz / n
            var variance = 0f
            for (i in 0 until jitterCount) {
                if (timestampNanos - jitterTimes[i] <= 2_000_000_000L) {
                    val dx = jitterX[i] - mx; val dy = jitterY[i] - my; val dz = jitterZ[i] - mz
                    variance += dx * dx + dy * dy + dz * dz
                }
            }
            gravityJitterG = kotlin.math.sqrt(variance / n)
        }

        if (!gravityFilterInitialized) {
            filteredGravityX = rx; filteredGravityY = ry; filteredGravityZ = rz
            acceptedGravityX = rx; acceptedGravityY = ry; acceptedGravityZ = rz
            gravityFilterInitialized = true
            lastSensorTimestamp = timestampNanos
        } else {
            val sensorDt = ((timestampNanos - lastSensorTimestamp) / 1_000_000_000f).coerceIn(0.001f, 0.1f)
            lastSensorTimestamp = timestampNanos
            val alpha = 1f - kotlin.math.exp(-sensorDt / 0.20f)
            filteredGravityX += (rx - filteredGravityX) * alpha
            filteredGravityY += (ry - filteredGravityY) * alpha
            filteredGravityZ += (rz - filteredGravityZ) * alpha
            val filteredLength = kotlin.math.sqrt(
                filteredGravityX * filteredGravityX +
                filteredGravityY * filteredGravityY +
                filteredGravityZ * filteredGravityZ
            ).coerceAtLeast(0.0001f)
            filteredGravityX /= filteredLength
            filteredGravityY /= filteredLength
            filteredGravityZ /= filteredLength

            // Change-based soft dead zone: retain large tilts, suppress tiny sensor chatter.
            val dx = filteredGravityX - acceptedGravityX
            val dy = filteredGravityY - acceptedGravityY
            val dz = filteredGravityZ - acceptedGravityZ
            val change = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
            val deadZone = max(0.015f, (gravityJitterG * 1.5f).coerceAtMost(0.05f))
            if (change > deadZone) {
                val softFraction = ((change - deadZone) / change).coerceIn(0f, 1f)
                acceptedGravityX += dx * softFraction
                acceptedGravityY += dy * softFraction
                acceptedGravityZ += dz * softFraction
            }
        }
        targetGravityX = acceptedGravityX
        targetGravityY = acceptedGravityY
        targetGravityZ = acceptedGravityZ
    }

    fun setShakeAcceleration(x: Float, y: Float, z: Float, timestampNanos: Long) {
        val magnitude = kotlin.math.sqrt(x * x + y * y + z * z)
        val threshold = 1.6f // m/s²; linear-acceleration sensor has gravity removed.
        if (!magnitude.isFinite() || magnitude <= threshold) return
        val strength = ((magnitude - threshold) / SensorManager.GRAVITY_EARTH).coerceIn(0f, 1.4f)
        synchronized(shakeLock) {
            pendingShakeX = (pendingShakeX - x / SensorManager.GRAVITY_EARTH * 0.16f).coerceIn(-0.35f, 0.35f)
            pendingShakeZ = (pendingShakeZ + z / SensorManager.GRAVITY_EARTH * 0.16f).coerceIn(-0.35f, 0.35f)
            shake = min(1.6f, shake + strength * 0.65f)
        }
        fluidSimulation.addImpulse(-x / SensorManager.GRAVITY_EARTH * 0.38f,
            z / SensorManager.GRAVITY_EARTH * 0.38f)
    }

    override fun onSurfaceCreated(glUnused: javax.microedition.khronos.opengles.GL10?, config: javax.microedition.khronos.egl.EGLConfig?) {
        GLES30.glClearColor(1f, 1f, 1f, 1f)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        val vertex = """
            #version 300 es
            layout(location=0) in vec2 aPosition;
            out vec2 vUv;
            void main() {
                vUv = aPosition * 0.5 + 0.5;
                gl_Position = vec4(aPosition, 0.0, 1.0);
            }
        """.trimIndent()
        val fragment = """
            #version 300 es
            precision highp float;
            in vec2 vUv;
            out vec4 fragColor;
            uniform vec2 uResolution;
            uniform vec3 uUp;
            uniform vec2 uEye;
            uniform float uTime;
            uniform float uShake;
            uniform float uSurfaceOffset;
            uniform vec2 uFlow;
            uniform sampler2D uFluidHeight;

            const vec3 TANK = vec3(0.405, 0.885, 0.305);
            float hash(vec3 p) {
                p = fract(p * 0.3183099 + vec3(0.1));
                p *= 17.0;
                return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
            }
            float noise(vec3 x) {
                vec3 i = floor(x), f = fract(x);
                f = f * f * (3.0 - 2.0 * f);
                return mix(mix(mix(hash(i), hash(i + vec3(1,0,0)), f.x),
                               mix(hash(i + vec3(0,1,0)), hash(i + vec3(1,1,0)), f.x), f.y),
                           mix(mix(hash(i + vec3(0,0,1)), hash(i + vec3(1,0,1)), f.x),
                               mix(hash(i + vec3(0,1,1)), hash(i + vec3(1,1,1)), f.x), f.y), f.z);
            }
            float causticPattern(vec2 p) {
                vec2 q = p * vec2(8.5, 10.5);
                q += vec2(sin(p.y * 5.0 + uTime * 0.55),
                          cos(p.x * 4.0 - uTime * 0.48)) * 0.24;
                float a = sin(q.x * 1.65 + sin(q.y * 1.25 + uTime * 0.82));
                float b = sin(q.y * 1.85 - sin(q.x * 1.35 - uTime * 0.68));
                return pow(clamp(1.0 - abs(a * b), 0.0, 1.0), 8.0);
            }
            float triCaustics(vec3 p, vec3 n) {
                vec3 w = pow(abs(n), vec3(4.0));
                w /= max(w.x + w.y + w.z, 0.0001);
                return causticPattern(p.zy) * w.x
                     + causticPattern(p.xz) * w.y
                     + causticPattern(p.xy) * w.z;
            }
            float bubbles(vec2 p, float t) {
                float acc = 0.0;
                for (int i = 0; i < 3; i++) {
                    float fi = float(i);
                    vec2 q = p * (5.0 + fi * 4.0);
                    q.y -= t * (0.30 + 0.14 * fi);
                    vec2 id = floor(q);
                    vec2 f = fract(q) - 0.5;
                    float h = hash(vec3(id, fi));
                    if (h > 0.45) {
                        vec2 off = (vec2(h, fract(h * 7.13)) - 0.5) * 0.4;
                        float r = 0.10 + 0.08 * fract(h * 3.7);
                        float d = length(f - off);
                        float ring = 1.0 - smoothstep(r - 0.025, r, d);
                        float glimmer = 1.0 - smoothstep(0.0, 0.03,
                            length(f - off - vec2(-r * 0.35, r * 0.35)));
                        acc += ring + glimmer * 0.8;
                    }
                }
                return clamp(acc, 0.0, 1.0);
            }
            float sdRoundBox(vec3 p, vec3 b, float r) {
                vec3 q = abs(p) - b + r;
                return length(max(q, 0.0)) + min(max(q.x, max(q.y, q.z)), 0.0) - r;
            }
            float sdRoundRect(vec2 p, vec2 b, float r) {
                vec2 q = abs(p) - b + r;
                return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
            }
            float boxEdges(vec3 ro, vec3 rd) {
                vec3 safeRd = vec3(
                    abs(rd.x) < 0.00001 ? (rd.x < 0.0 ? -0.00001 : 0.00001) : rd.x,
                    abs(rd.y) < 0.00001 ? (rd.y < 0.0 ? -0.00001 : 0.00001) : rd.y,
                    abs(rd.z) < 0.00001 ? (rd.z < 0.0 ? -0.00001 : 0.00001) : rd.z
                );
                vec3 a = (-TANK - ro) / safeRd;
                vec3 b = ( TANK - ro) / safeRd;
                vec3 lo = min(a, b);
                vec3 hi = max(a, b);
                float tNear = max(lo.x, max(lo.y, lo.z));
                float tFar = min(hi.x, min(hi.y, hi.z));
                if (tFar < max(tNear, 0.0)) return 0.0;
                vec3 p = ro + rd * max(tNear, 0.0);
                float edgeDistance;
                if (lo.x >= lo.y && lo.x >= lo.z)
                    edgeDistance = min(abs(abs(p.y) - TANK.y), abs(abs(p.z) - TANK.z));
                else if (lo.y >= lo.z)
                    edgeDistance = min(abs(abs(p.x) - TANK.x), abs(abs(p.z) - TANK.z));
                else
                    edgeDistance = min(abs(abs(p.x) - TANK.x), abs(abs(p.y) - TANK.y));
                return 1.0 - smoothstep(0.002, 0.018, edgeDistance);
            }
            vec3 surfaceNormal(vec3 p, vec3 up) {
                vec3 n = normalize(up);
                float w = 0.010 * sin(p.x * 13.0 + uTime * 1.7)
                        + 0.006 * sin(p.z * 19.0 - uTime * 1.25)
                        + uShake * 0.012 * sin(p.x * 23.0 + p.z * 17.0 - uTime * 7.0);
                return normalize(n + vec3(-0.13 * cos(p.x * 13.0 + uTime * 1.7),
                                           0.0,
                                           -0.11 * cos(p.z * 19.0 - uTime * 1.25)));
            }
            float freeSurfaceDistance(vec3 p, vec3 up, float surfaceOffset) {
                vec3 n = normalize(up);
                float energy = clamp(length(uFlow) * 0.018 + uShake * 0.008, 0.0, 0.032);
                vec2 fluidUv = clamp(vec2(p.x / (2.0 * TANK.x) + 0.5,
                                          p.z / (2.0 * TANK.z) + 0.5), 0.0, 1.0);
                float simulatedHeight = texture(uFluidHeight, fluidUv).r;
                float wave = 0.0025 * sin(p.x * 11.0 + uTime * 1.1)
                           + 0.0018 * sin(p.z * 15.0 - uTime * 0.9)
                           + energy * sin(p.x * 10.0 + p.z * 7.0 - uTime * 3.6);
                return dot(p, n) + surfaceOffset + wave - simulatedHeight;
            }
            float waterDistance(vec3 p, vec3 up) {
                float container = sdRoundBox(p, TANK - vec3(0.018), 0.025);
                return max(container, freeSurfaceDistance(p, up, uSurfaceOffset));
            }
            vec3 displacedSurfaceNormal(vec3 p, vec3 up, float surfaceOffset) {
                float e = 0.0025;
                return normalize(vec3(
                    freeSurfaceDistance(p + vec3(e,0,0), up, surfaceOffset) -
                    freeSurfaceDistance(p - vec3(e,0,0), up, surfaceOffset),
                    freeSurfaceDistance(p + vec3(0,e,0), up, surfaceOffset) -
                    freeSurfaceDistance(p - vec3(0,e,0), up, surfaceOffset),
                    freeSurfaceDistance(p + vec3(0,0,e), up, surfaceOffset) -
                    freeSurfaceDistance(p - vec3(0,0,e), up, surfaceOffset)
                ));
            }
            vec3 waterNormal(vec3 p, vec3 up) {
                float e = 0.0025;
                return normalize(vec3(
                    waterDistance(p + vec3(e,0,0),up) - waterDistance(p - vec3(e,0,0),up),
                    waterDistance(p + vec3(0,e,0),up) - waterDistance(p - vec3(0,e,0),up),
                    waterDistance(p + vec3(0,0,e),up) - waterDistance(p - vec3(0,0,e),up)
                ));
            }
            bool traceWater(vec3 ro, vec3 rd, vec3 up, out vec3 hit, out float travel) {
                travel = 0.0;
                for (int i = 0; i < 88; i++) {
                    vec3 p = ro + rd * travel;
                    float d = waterDistance(p, up);
                    if (d < 0.0018) { hit = p; return true; }
                    travel += max(d * 0.72, 0.0015);
                    if (travel > 7.0) break;
                }
                hit = vec3(0.0);
                return false;
            }
            // Axis-aligned inner-box exit. Parallel axes contribute infinity,
            // never a fabricated near-zero exit. Corner ties choose one stable face.
            float exitBox(vec3 p, vec3 r, vec3 wb, out vec3 nOut) {
                float tx = r.x > 0.00001 ? ( wb.x - p.x) / r.x :
                           r.x < -0.00001 ? (-wb.x - p.x) / r.x : 1e9;
                float ty = r.y > 0.00001 ? ( wb.y - p.y) / r.y :
                           r.y < -0.00001 ? (-wb.y - p.y) / r.y : 1e9;
                float tz = r.z > 0.00001 ? ( wb.z - p.z) / r.z :
                           r.z < -0.00001 ? (-wb.z - p.z) / r.z : 1e9;
                float t = min(tx, min(ty, tz));
                if (tx <= ty && tx <= tz) nOut = vec3(sign(r.x), 0.0, 0.0);
                else if (ty <= tz) nOut = vec3(0.0, sign(r.y), 0.0);
                else nOut = vec3(0.0, 0.0, sign(r.z));
                return max(t, 0.0);
            }
            float exitWater(vec3 p, vec3 r, vec3 up, float surfaceOffset,
                            vec3 wb, out vec3 nOut, out float viaSurface) {
                vec3 boxNormal;
                float tBox = exitBox(p, r, wb, boxNormal);
                float tSurface = 1e9;
                vec3 surfaceNormalOut = normalize(up);

                // Find the first inside-to-outside crossing of the same displaced
                // free surface used by waterDistance(). The tank-wall path remains
                // the existing analytic inner-box approximation.
                float previousT = 0.0;
                float previousF = freeSurfaceDistance(p, up, surfaceOffset);
                if (previousF < 0.0 && tBox > 0.0 && tBox < 1e8) {
                    for (int i = 1; i <= 16; i++) {
                        float sampleT = tBox * (float(i) / 16.0);
                        float sampleF = freeSurfaceDistance(p + r * sampleT, up, surfaceOffset);
                        if (previousF < 0.0 && sampleF >= 0.0) {
                            float lo = previousT;
                            float hi = sampleT;
                            for (int j = 0; j < 10; j++) {
                                float mid = 0.5 * (lo + hi);
                                float midF = freeSurfaceDistance(p + r * mid, up, surfaceOffset);
                                if (midF < 0.0) lo = mid;
                                else hi = mid;
                            }
                            tSurface = hi;
                            surfaceNormalOut = displacedSurfaceNormal(
                                p + r * tSurface, up, surfaceOffset);
                            break;
                        }
                        previousT = sampleT;
                        previousF = sampleF;
                    }
                }

                if (tSurface < tBox) {
                    nOut = surfaceNormalOut;
                    viaSurface = 1.0;
                    return tSurface;
                }
                nOut = normalize(boxNormal);
                viaSurface = 0.0;
                return tBox;
            }
            vec3 sampleBackdrop(vec3 p, vec3 r) {
                // Select the far plane in the ray's travel direction. This explicitly
                // handles positive-Z rays instead of clamping them to the rear plane.
                float planeZ = r.z < 0.0 ? -0.80 : 0.80;
                if (abs(r.z) < 0.0001) return vec3(4.0);
                float t = max((planeZ - p.z) / r.z, 0.0);
                vec3 q = p + r * t;
                // Featureless white studio backdrop for clean optical testing.
                return vec3(4.0);
            }
            void main() {
                vec2 uv = vUv;
                float aspect = uResolution.x / uResolution.y;
                vec2 screen = (uv - 0.5) * vec2(aspect, 1.0);
                // Anchor the camera to the physical front-glass plane. The scale
                // preserves the original 0.3.7 framing at z=+0.305.
                vec2 win = screen * 2.436;
                vec2 eye = clamp(uEye, vec2(-1.0), vec2(1.0)) * vec2(0.20, 0.26);
                vec3 ro = vec3(eye, 3.35);
                vec3 rd = normalize(vec3(win - eye, TANK.z - 3.35));
                vec3 up = normalize(uUp);
                // Linear value above 1.0 compensates for the final filmic-style
                // exponential mapping so the empty test space renders near pure white.
                vec3 background = vec3(4.0);
                float inRect = 1.0 - smoothstep(0.0, 0.012,
                    sdRoundRect(win, TANK.xy, 0.055));
                // The backdrop is a real plane behind the tank. Guard both ray
                // directions so refracted rays with positive Z never divide by zero.
                float backdropDenom = abs(rd.z) < 0.001
                    ? (rd.z < 0.0 ? -0.001 : 0.001) : rd.z;
                float backdropZ = rd.z < 0.0 ? -0.80 : 0.80;
                float backdropT = (backdropZ - ro.z) / backdropDenom;
                vec3 backdropPoint = ro + rd * max(backdropT, 0.0);
                // Keep the tank interior and exterior on the same white test field.
                background = vec3(4.0);
                // Keep the background uniform: the old radial halo looked like a
                // persistent glowing blob trapped in the center of the water.

                // Ray-marched intersection of a real 3D liquid volume with its container.
                vec3 hit;
                float travel;
                vec3 color = background;
                if (traceWater(ro, rd, up, hit, travel)) {
                    vec3 n = waterNormal(hit, up);
                    vec3 viewDir = normalize(ro - hit);
                    vec3 lightDir = normalize(vec3(-0.45, 0.78, 0.62));

                    // Signed distance from the gravity-aligned free surface.
                    // Negative values are below the surface and remain correct while tilted.
                    float s = dot(hit, up) + uSurfaceOffset;
                    float below = clamp(-s * 1.15, 0.0, 1.0);

                    // Ocean turquoise -> clear aqua -> deep indigo, with depth tied to the plane.
                    vec3 shallow = vec3(0.19, 0.78, 0.69);
                    vec3 tropical = vec3(0.035, 0.43, 0.57);
                    vec3 deep = vec3(0.055, 0.16, 0.39);
                    vec3 body = mix(shallow, tropical, smoothstep(0.0, 0.38, below));
                    body = mix(body, deep, smoothstep(0.32, 1.0, below));

                    float diff = max(dot(n, lightDir), 0.0);
                    float band = 0.5 * smoothstep(0.15, 0.20, diff)
                               + 0.5 * smoothstep(0.55, 0.60, diff);
                    body *= 0.76 + 0.30 * band;

                    // Bubble sprites and animated caustic-line overlays are disabled
                    // for clean material and refraction testing.

                    float rimLight = pow(1.0 - max(dot(n, viewDir), 0.0), 2.5);
                    body += vec3(0.20, 0.58, 0.68) * rimLight * 0.20;

                    float surfaceFactor = smoothstep(0.66, 0.95, dot(n, up));
                    vec3 top = mix(vec3(0.25, 0.78, 0.72),
                                   vec3(0.68, 0.91, 0.83), diff * 0.58);
                    float reflected = max(dot(reflect(-lightDir, n), viewDir), 0.0);
                    float glint = step(0.965, reflected);
                    top += vec3(0.72, 0.88, 0.76) * glint * 0.34;

                    color = mix(body, top, surfaceFactor);

                    // Two-interface optical transport through the actual water path.
                    // The inner box is a conservative analytic approximation of the
                    // rounded tank; every start is checked before slab exits are used.
                    vec3 entryNormal = normalize(n);
                    if (dot(rd, entryNormal) > 0.0) entryNormal = -entryNormal;
                    vec3 rayInside = refract(rd, entryNormal, 1.0 / 1.33);
                    if (dot(rayInside, rayInside) > 0.000001) {
                        vec3 wb = TANK - vec3(0.018);
                        vec3 insidePoint = hit + rayInside * 0.004;
                        // Use the exact same rounded, wave-displaced water volume as the ray marcher.
                        bool insideStart = waterDistance(insidePoint, up) < -0.0005;
                        if (insideStart) {
                            float totalWaterLength = 0.0;
                            vec3 exitPoint = insidePoint;
                            vec3 exitNormal = up;
                            vec3 rayOutside = vec3(0.0);
                            bool escaped = false;

                            // At most one internal-reflection retry. A second TIR
                            // deliberately falls back to an absorbed internal tint;
                            // it must never sample the backdrop from inside the water.
                            for (int bounce = 0; bounce < 2; bounce++) {
                                float viaSurface;
                                vec3 candidateNormal;
                                float segmentLength = exitWater(insidePoint, rayInside, up,
                                    uSurfaceOffset, wb, candidateNormal, viaSurface);
                                if (segmentLength >= 1e8) break;
                                segmentLength = max(segmentLength, 0.0);
                                totalWaterLength += segmentLength;
                                exitPoint = insidePoint + rayInside * segmentLength;
                                exitNormal = normalize(candidateNormal);
                                rayOutside = refract(rayInside, -exitNormal, 1.33);
                                if (dot(rayOutside, rayOutside) > 0.000001) {
                                    escaped = true;
                                    break;
                                }
                                if (bounce == 1) break;
                                rayInside = reflect(rayInside, exitNormal);
                                insidePoint = exitPoint - exitNormal * 0.004 + rayInside * 0.002;
                                // Revalidate the reflected ray against the same displaced water volume.
                                insideStart = waterDistance(insidePoint, up) < -0.0005;
                                if (!insideStart) break;
                            }

                            vec3 absorption = vec3(1.10, 0.48, 0.30);
                            vec3 transmittance = exp(-absorption * totalWaterLength);
                            if (escaped) {
                                vec3 farGlass = sampleBackdrop(exitPoint + rayOutside * 0.003,
                                                                normalize(rayOutside));
                                // No procedural caustic or bubble overlays in this test mode.
                                vec3 inScatter = vec3(0.018, 0.105, 0.115) *
                                                 (1.0 - transmittance);
                                vec3 transmitted = farGlass * transmittance + inScatter;

                                vec3 reflectedRay = reflect(rd, entryNormal);
                                vec3 reflected = sampleBackdrop(hit + entryNormal * 0.004,
                                                                 reflectedRay);
                                float cosEntry = clamp(-dot(rd, entryNormal), 0.0, 1.0);
                                float cosExit = clamp(dot(rayInside, exitNormal), 0.0, 1.0);
                                float fEntry = 0.0204 + 0.9796 * pow(1.0 - cosEntry, 5.0);
                                float fExit = 0.0204 + 0.9796 * pow(1.0 - cosExit, 5.0);
                                float fresnel = 1.0 - (1.0 - fEntry) * (1.0 - fExit);
                                color = mix(transmitted, reflected, clamp(fresnel, 0.0, 0.45));
                            } else {
                                color = vec3(0.012, 0.055, 0.072) * transmittance +
                                        vec3(0.006, 0.022, 0.030);
                            }
                        }
                    }

                    // Thin warm-ivory meniscus where the water meets the glass.
                    float foam = 1.0 - smoothstep(0.006, 0.026, abs(s));
                    float sideFace = 1.0 - surfaceFactor;
                    color = mix(color, vec3(0.83, 0.91, 0.78),
                                foam * 0.62 * sideFace);
                }

                float perspectiveEdges = boxEdges(ro, rd);
                // Subtle neutral edge contrast keeps the clear glass vessel readable
                // against the white studio field without a colored neon outline.
                color += vec3(0.42, 0.48, 0.50) * perspectiveEdges * 0.20;

                // Front glass is evaluated on its actual plane, not a constant
                // ray-distance slice (which curved the tank during parallax).
                float frontT = (TANK.z - ro.z) / rd.z;
                vec3 pNear = ro + rd * frontT;
                float shell = abs(sdRoundRect(pNear.xy, TANK.xy, 0.055));
                float edge = (1.0 - smoothstep(0.002, 0.013, shell)) * inRect;
                float rim = (1.0 - smoothstep(0.018, 0.045, shell)) * inRect;
                color += vec3(0.78,0.86,0.88) * edge * 0.62;
                color += vec3(0.42,0.50,0.53) * rim * 0.12;
                float leftStrip = exp(-pow((pNear.x + 0.365)*45.0,2.0)) *
                                  smoothstep(-0.82,0.72,pNear.y) * inRect;
                float rightStrip = exp(-pow((pNear.x - 0.37)*54.0,2.0)) *
                                   smoothstep(-0.78,0.78,pNear.y) * inRect;
                color += vec3(0.92,0.97,0.98) * (leftStrip*0.30 + rightStrip*0.16);
                float topGlare = exp(-pow((pNear.y - 0.85)*38.0,2.0)) *
                                 smoothstep(-0.34,0.34,pNear.x) * inRect;
                color += vec3(1.0,1.0,1.0) * topGlare * 0.30;
                // No global vignette: the white test field must remain uniformly white.
                // Gentle exponential tone mapping keeps highlights bright without clipping.
                color = 1.0 - exp(-max(color, vec3(0.0)) * 1.35);
                fragColor = vec4(color,1.0);
            }
        """.trimIndent()

        val quad = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        vertexBuffer = ByteBuffer.allocateDirect(quad.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        vertexBuffer.put(quad).position(0)
        program = try {
            linkProgram(vertex, fragment)
        } catch (error: Exception) {
            onDiagnostics("GLSL initialization failed: ${error.message ?: "unknown shader error"}")
            0
        }
        if (program == 0) return
        timeHandle = GLES30.glGetUniformLocation(program, "uTime")
        upHandle = GLES30.glGetUniformLocation(program, "uUp")
        eyeHandle = GLES30.glGetUniformLocation(program, "uEye")
        resolutionHandle = GLES30.glGetUniformLocation(program, "uResolution")
        shakeHandle = GLES30.glGetUniformLocation(program, "uShake")
        surfaceOffsetHandle = GLES30.glGetUniformLocation(program, "uSurfaceOffset")
        flowHandle = GLES30.glGetUniformLocation(program, "uFlow")
        fluidHeightHandle = GLES30.glGetUniformLocation(program, "uFluidHeight")
        val textureIds = IntArray(1)
        GLES30.glGenTextures(1, textureIds, 0)
        fluidHeightTexture = textureIds[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fluidHeightTexture)
        // R32F linear filtering is not guaranteed on every ES 3.0 device.
        // Use linear when the extension is exposed; otherwise fall back safely.
        val floatLinear = GLES30.glGetString(GLES30.GL_EXTENSIONS)
            ?.contains("GL_OES_texture_float_linear") == true
        val heightFilter = if (floatLinear) GLES30.GL_LINEAR else GLES30.GL_NEAREST
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, heightFilter)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, heightFilter)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        val gridSize = fluidSimulation.resolution()
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R32F, gridSize, gridSize, 0,
            GLES30.GL_RED, GLES30.GL_FLOAT, null)
        startNanos = System.nanoTime()
        lastNanos = startNanos
    }

    override fun onSurfaceChanged(glUnused: javax.microedition.khronos.opengles.GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(glUnused: javax.microedition.khronos.opengles.GL10?) {
        val now = System.nanoTime()
        val dt = ((now - lastNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
        lastNanos = now
        val frameShake: Float
        val kickX: Float
        val kickZ: Float
        synchronized(shakeLock) {
            shake = max(0f, shake - dt * 1.25f)
            frameShake = shake
            kickX = pendingShakeX
            kickZ = pendingShakeZ
            pendingShakeX = 0f
            pendingShakeZ = 0f
        }

        // A 0.7 Hz damped spring (zeta 0.30) gives the whole gravity vector
        // physical follow-through. Substeps keep integration stable across frame rates.
        val targetLength = kotlin.math.sqrt(
            targetGravityX * targetGravityX + targetGravityY * targetGravityY +
            targetGravityZ * targetGravityZ
        ).coerceAtLeast(0.0001f)
        val targetX = targetGravityX / targetLength * 0.8f
        val targetY = targetGravityY / targetLength
        val targetZ = targetGravityZ / targetLength * 0.8f
        val omega = 2f * Math.PI.toFloat() * 0.7f
        val dampingRatio = 0.30f
        val steps = max(1, kotlin.math.ceil(dt.toDouble() / (1.0 / 120.0)).toInt())
        val stepDt = if (steps > 0) dt / steps else 0f
        repeat(steps) {
            springVelocityX += (omega * omega * (targetX - springGravityX) -
                2f * dampingRatio * omega * springVelocityX) * stepDt
            springVelocityY += (omega * omega * (targetY - springGravityY) -
                2f * dampingRatio * omega * springVelocityY) * stepDt
            springVelocityZ += (omega * omega * (targetZ - springGravityZ) -
                2f * dampingRatio * omega * springVelocityZ) * stepDt
            springGravityX += springVelocityX * stepDt
            springGravityY += springVelocityY * stepDt
            springGravityZ += springVelocityZ * stepDt
        }
        val springLength = kotlin.math.sqrt(
            springGravityX * springGravityX + springGravityY * springGravityY +
            springGravityZ * springGravityZ
        ).coerceAtLeast(0.0001f)
        springGravityX /= springLength
        springGravityY /= springLength
        springGravityZ /= springLength

        // Shake contributes a short momentum kick, never a change to the sensor target.
        springVelocityX += kickX
        springVelocityZ += kickZ
        fluidSimulation.step(dt)
        val time = (now - startNanos) / 1_000_000_000f
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        if (program == 0) return
        GLES30.glUseProgram(program)
        val up = computeUp(springGravityX, springGravityY, springGravityZ)
        GLES30.glUniform1f(timeHandle, time)
        GLES30.glUniform3f(upHandle, up[0], up[1], up[2])
        GLES30.glUniform2f(eyeHandle, eyeX, eyeY)
        GLES30.glUniform1f(shakeHandle, frameShake)
        val rawSurfaceOffset = volumeSolver.offsetForHalfFill(up[0], up[1], up[2])
        if (rawSurfaceOffset.isFinite()) {
            lastGoodSurfaceOffset = rawSurfaceOffset.coerceIn(-1.5f, 1.5f)
        }
        GLES30.glUniform1f(surfaceOffsetHandle, lastGoodSurfaceOffset)
        GLES30.glUniform2f(flowHandle, springVelocityX, springVelocityZ)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fluidHeightTexture)
        GLES30.glUniform1i(fluidHeightHandle, 0)
        val heightData = fluidSimulation.createUploadBuffer()
        fluidSimulation.writeHeightTexture(heightData)
        val gridSize = fluidSimulation.resolution()
        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, gridSize, gridSize,
            GLES30.GL_RED, GLES30.GL_FLOAT, heightData)
        val viewport = IntArray(4)
        GLES30.glGetIntegerv(GLES30.GL_VIEWPORT, viewport, 0)
        GLES30.glUniform2f(resolutionHandle, viewport[2].toFloat(), viewport[3].toFloat())
        vertexBuffer.position(0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, vertexBuffer)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(0)

        frameCounter++
        if (now - lastDiagnosticsNanos >= 250_000_000L) {
            lastDiagnosticsNanos = now
            onDiagnostics(
                "frames: $frameCounter   jitter: %.3f g\nraw accel(g): %.2f, %.2f, %.2f\nup: %.2f, %.2f, %.2f  signs: %.0f/%.0f  offset: %.4f".format(
                    gravityJitterG, rawSensorX, rawSensorY, rawSensorZ,
                    up[0], up[1], up[2], upSignX, upSignZ, lastGoodSurfaceOffset
                )
            )
        }
    }

    private fun computeUp(gx: Float, gy: Float, gz: Float): FloatArray {
        // Stage 0 device test confirmed the face-up Z direction is inverted with -1.
        // Keep X unchanged; flip only Z so the solver and shader share the corrected up vector.
        val x = upSignX * gx
        val y = gy
        val z = upSignZ * gz
        val length = kotlin.math.sqrt(x * x + y * y + z * z).coerceAtLeast(0.0001f)
        computedUp[0] = x / length
        computedUp[1] = y / length
        computedUp[2] = z / length
        return computedUp
    }

    private fun linkProgram(vertexSource: String, fragmentSource: String): Int {
        fun compile(type: Int, source: String): Int {
            val shader = GLES30.glCreateShader(type)
            GLES30.glShaderSource(shader, source)
            GLES30.glCompileShader(shader)
            val status = IntArray(1)
            GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) throw IllegalStateException(GLES30.glGetShaderInfoLog(shader))
            return shader
        }
        val vs = compile(GLES30.GL_VERTEX_SHADER, vertexSource)
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
        val linked = GLES30.glCreateProgram()
        GLES30.glAttachShader(linked, vs)
        GLES30.glAttachShader(linked, fs)
        GLES30.glLinkProgram(linked)
        val status = IntArray(1)
        GLES30.glGetProgramiv(linked, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) throw IllegalStateException(GLES30.glGetProgramInfoLog(linked))
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        return linked
    }
}
