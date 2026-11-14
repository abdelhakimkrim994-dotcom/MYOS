package com.myos.home

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import android.app.Activity
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt


/**
 * A translucent body of water occupying a fixed fraction of the launcher viewport.
 * Its free surface responds to gravity while the drawer scenery remains stationary.
 */
private class SloshWater(private val density: Float) {
    var fill = 0.45f
    private var theta = 0f
    private var omega = 0f
    private var target = 0f
    private var phase = 0f
    private var surfaceX = 0f
    private var surfaceY = 0f
    private var surfaceTx = 1f
    private var surfaceTy = 0f
    var gravityX: Float = 0f
        private set
    var gravityY: Float = 1f
        private set
    var surfaceDistance: Float = 0f
        private set
    val angle: Float get() = theta
    private val path = Path()
    private val edge = Path()
    private val px = FloatArray(8)
    private val py = FloatArray(8)
    private val qx = FloatArray(8)
    private val qy = FloatArray(8)
    private val matrix = Matrix()
    private val gradient = LinearGradient(
        0f, 0f, 0f, 1f,
        Color.argb(112, 190, 250, 255),
        Color.argb(178, 0, 145, 190),
        Shader.TileMode.CLAMP
    )
    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = gradient }
    private val depth = Paint(Paint.ANTI_ALIAS_FLAG)
    private val caustic = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val glint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.WHITE
    }
    private val causticPath = Path()
    private val soft = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 8f * density
        alpha = 85
    }
    private val foam = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 2.8f * density
        alpha = 220
    }

    val horizontalMotion: Float get() = kotlin.math.sin(theta).coerceIn(-1f, 1f)
    val verticalMotion: Float get() = (1f - kotlin.math.cos(theta)).coerceIn(-1f, 1f)
    val angularVelocity: Float get() = omega

    /** Gravity vector in device coordinates; rotation-matrix row 3 or filtered accelerometer gravity. */
    fun setGravity(ax: Float, ay: Float, az: Float) {
        val magnitude = kotlin.math.sqrt(ax * ax + ay * ay + az * az)
        if (magnitude < 1e-3f || kotlin.math.hypot(ax, ay) / magnitude < 0.25f) return
        target = kotlin.math.atan2(-ax, ay)
    }

    fun kick(strength: Float) {
        omega = (omega + strength.coerceIn(0f, 2f) * 4f).coerceIn(-9f, 9f)
    }

    fun step(dt: Float) {
        var delta = target - theta
        while (delta > Math.PI) delta -= (2f * Math.PI.toFloat())
        while (delta < -Math.PI) delta += (2f * Math.PI.toFloat())
        omega += (delta * 38f - omega * 4.2f) * dt
        omega = omega.coerceIn(-9f, 9f)
        theta += omega * dt
        phase += dt * (1.6f + kotlin.math.abs(omega))
    }

    private fun area(d: Float, w: Float, h: Float, gx: Float, gy: Float): Float {
        px[0] = 0f; py[0] = 0f
        px[1] = w; py[1] = 0f
        px[2] = w; py[2] = h
        px[3] = 0f; py[3] = h
        var count = 0
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            val da = (px[i] - w / 2f) * gx + (py[i] - h / 2f) * gy - d
            val db = (px[j] - w / 2f) * gx + (py[j] - h / 2f) * gy - d
            if (da >= 0f) { qx[count] = px[i]; qy[count] = py[i]; count++ }
            if ((da >= 0f) != (db >= 0f)) {
                val t = da / (da - db)
                qx[count] = px[i] + (px[j] - px[i]) * t
                qy[count] = py[i] + (py[j] - py[i]) * t
                count++
            }
        }
        var twiceArea = 0f
        for (i in 0 until count) {
            val j = (i + 1) % count
            twiceArea += qx[i] * qy[j] - qx[j] * qy[i]
        }
        return kotlin.math.abs(twiceArea) * 0.5f
    }

    private fun buildPath(w: Float, h: Float) {
        val gx = kotlin.math.sin(theta)
        val gy = kotlin.math.cos(theta)
        var low = -kotlin.math.hypot(w, h) * 0.5f
        var high = -low
        val targetArea = fill.coerceIn(0.05f, 0.95f) * w * h
        repeat(16) {
            val mid = (low + high) * 0.5f
            if (area(mid, w, h, gx, gy) > targetArea) low = mid else high = mid
        }
        val d = (low + high) * 0.5f
        gravityX = gx
        gravityY = gy
        surfaceDistance = d
        val sx = w * 0.5f + gx * d
        val sy = h * 0.5f + gy * d
        val tx = gy
        val ty = -gx
        surfaceX = sx
        surfaceY = sy
        surfaceTx = tx
        surfaceTy = ty
        val extent = kotlin.math.hypot(w, h)
        // Broader travelling waves give the free surface a visible curved profile.
        val amplitude = (5f + minOf(15f, kotlin.math.abs(omega) * 5.5f)) * density
        path.rewind()
        edge.rewind()
        val segments = 80
        for (i in 0..segments) {
            val u = (i / segments.toFloat() * 2f - 1f) * extent
            val waveTravel = phase * (0.72f + kotlin.math.abs(omega) * 0.08f)
            val offset = amplitude * (
                kotlin.math.sin(u / (58f * density) + waveTravel) * 0.58f +
                kotlin.math.sin(u / (29f * density) - waveTravel * 1.27f) * 0.27f +
                kotlin.math.sin(u / (105f * density) + waveTravel * 0.42f) * 0.15f
            )
            val x = sx + tx * u - gx * offset
            val y = sy + ty * u - gy * offset
            if (i == 0) { path.moveTo(x, y); edge.moveTo(x, y) }
            else { path.lineTo(x, y); edge.lineTo(x, y) }
        }
        path.lineTo(sx + tx * extent + gx * extent, sy + ty * extent + gy * extent)
        path.lineTo(sx - tx * extent + gx * extent, sy - ty * extent + gy * extent)
        path.close()
        matrix.setScale(1f, 420f * density)
        matrix.postRotate(-Math.toDegrees(theta.toDouble()).toFloat())
        matrix.postTranslate(sx, sy)
        gradient.setLocalMatrix(matrix)
    }

    fun clipToWater(canvas: Canvas, w: Float, h: Float) {
        buildPath(w, h)
        canvas.clipPath(path)
    }

    fun draw(canvas: Canvas, w: Float, h: Float, alpha: Float) {
        if (alpha <= 0.01f || w <= 0f || h <= 0f) return
        buildPath(w, h)

        // Layer 1: translucent body with a bright surface and deeper blue-green base.
        body.alpha = (alpha * 255f).toInt().coerceIn(0, 255)
        canvas.drawPath(path, body)

        // Layer 2: darkened side walls create the illusion of thickness and rounded volume.
        depth.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(
                Color.argb(8, 0, 44, 70),
                Color.argb(24, 0, 55, 82),
                Color.argb(112, 0, 35, 67),
                Color.argb(188, 0, 23, 54)
            ),
            floatArrayOf(0f, 0.24f, 0.72f, 1f),
            Shader.TileMode.CLAMP
        )
        depth.alpha = (alpha * 220f).toInt().coerceIn(0, 220)
        canvas.drawPath(path, depth)
        depth.shader = null

        // Layer 3: refracted caustic ribbons inside the liquid, moving at different depths.
        canvas.save()
        canvas.clipPath(path)
        for (i in 0 until 16) {
            val depthFactor = 0.12f + (i % 8) * 0.105f
            val y = h * (1f - fill * depthFactor)
            val x = ((i * 0.137f + phase * (0.018f + (i % 3) * 0.006f)) % 1.2f - 0.1f) * w
            val length = w * (0.08f + (i % 5) * 0.032f)
            val sway = sin(phase * 0.52f + i * 1.7f) * (7f + depthFactor * 8f) * density
            causticPath.rewind()
            causticPath.moveTo(x, y + sway)
            causticPath.cubicTo(
                x + length * 0.28f, y - 8f * density + sway,
                x + length * 0.68f, y + 9f * density + sway,
                x + length, y + sway * 0.45f
            )
            caustic.color = if (i % 3 == 0) Color.rgb(202, 255, 250) else Color.rgb(105, 229, 238)
            caustic.strokeWidth = (1f + (i % 3) * 0.55f) * density
            caustic.alpha = (alpha * (24f + (i % 4) * 12f)).toInt().coerceIn(0, 72)
            canvas.drawPath(causticPath, caustic)
        }

        // Soft shafts and scattered specular glints make the water read as a 3D medium.
        for (i in 0 until 7) {
            val x = w * (0.10f + i * 0.135f) + sin(phase * 0.4f + i) * 7f * density
            val y = h * (1f - fill * (0.22f + (i % 4) * 0.16f))
            glint.strokeWidth = (1.1f + (i % 2) * 0.8f) * density
            glint.alpha = (alpha * (32f + (i % 3) * 18f)).toInt().coerceIn(0, 86)
            canvas.drawLine(x, y, x + (12f + (i % 3) * 5f) * density, y - 2f * density, glint)
        }
        canvas.restore()

        // Layer 4: bright Fresnel-like rim and a broad, soft reflection on the free surface.
        soft.alpha = (85f * alpha).toInt().coerceIn(0, 85)
        foam.alpha = (220f * alpha).toInt().coerceIn(0, 220)
        canvas.drawPath(edge, soft)
        canvas.drawPath(edge, foam)
        glint.strokeWidth = 1.2f * density
        glint.alpha = (alpha * 115f).toInt().coerceIn(0, 115)
        causticPath.rewind()
        val rimHalf = w * 0.22f
        val ripple = sin(phase) * 2.5f * density
        causticPath.moveTo(
            surfaceX - surfaceTx * rimHalf,
            surfaceY - surfaceTy * rimHalf
        )
        causticPath.quadTo(
            surfaceX + surfaceTx * 0.02f * w - surfaceTy * ripple,
            surfaceY + surfaceTy * 0.02f * w + surfaceTx * ripple,
            surfaceX + surfaceTx * rimHalf,
            surfaceY + surfaceTy * rimHalf
        )
        canvas.drawPath(causticPath, glint)
    }
}


/**
 * Small ocean objects carried by the liquid. Pearls are heavy and settle low;
 * shells and starfish are buoyant and gather near the free surface.
 */
private class FloatingThings(private val density: Float, width: Float, height: Float) {
    private data class Thing(
        var x: Float, var y: Float, val radius: Float, val buoyancy: Float,
        val kind: Int, val color: Int,
        var vx: Float = 0f, var vy: Float = 0f, var rotation: Float = 0f
    )
    private val random = java.util.Random(19L)
    private val things = ArrayList<Thing>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val highlight = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    init {
        val colors = intArrayOf(
            Color.rgb(244, 177, 104), Color.rgb(255, 205, 128),
            Color.rgb(240, 125, 112), Color.rgb(245, 220, 164)
        )
        // Pearls: heavier objects that drift and collect toward the low side.
        repeat(7) { i ->
            val r = (5.5f + random.nextFloat() * 3.5f) * density
            things += Thing(
                width * (0.12f + random.nextFloat() * 0.76f),
                height * (0.59f + random.nextFloat() * 0.32f),
                r, 0.10f, 0, Color.rgb(226, 239, 236)
            )
        }
        // Shells and starfish: buoyant objects that ride the moving surface.
        repeat(4) { i ->
            val r = (10f + random.nextFloat() * 3f) * density
            things += Thing(
                width * (0.12f + random.nextFloat() * 0.76f),
                height * (0.50f + random.nextFloat() * 0.12f),
                r, 1.0f, 1 + (i % 2), colors[i % colors.size],
                rotation = random.nextFloat() * 0.7f - 0.35f
            )
        }
        highlight.color = Color.WHITE
        highlight.style = Paint.Style.STROKE
        highlight.strokeCap = Paint.Cap.ROUND
    }

    fun shake(strength: Float) {
        for (t in things) {
            t.vx += (random.nextFloat() - 0.5f) * 520f * density * strength
            t.vy += (random.nextFloat() - 0.5f) * 520f * density * strength
        }
    }

    fun step(dtInput: Float, w: Float, h: Float, gx: Float, gy: Float, surfaceD: Float, theta: Float) {
        val dt = dtInput.coerceIn(0.001f, 0.033f)
        val gravity = 980f * density
        for (t in things) {
            val signedDepth = (t.x - w * 0.5f) * gx + (t.y - h * 0.5f) * gy - surfaceD
            if (t.buoyancy > 0.5f) {
                // A damped buoyancy spring keeps the shell/starfish partly submerged.
                val desiredDepth = t.radius * 0.72f
                val error = signedDepth - desiredDepth
                val acceleration = (-error * 15f - (t.vx * gx + t.vy * gy) * 5.2f)
                t.vx += gx * acceleration * dt
                t.vy += gy * acceleration * dt
                val tangentSpeed = t.vx * gy - t.vy * gx
                t.vx += gy * (-tangentSpeed * 1.0f) * dt
                t.vy += -gx * (-tangentSpeed * 1.0f) * dt
            } else {
                // Pearls retain weight and slowly slide down the gravity vector.
                t.vx += gx * gravity * dt
                t.vy += gy * gravity * dt
                val drag = kotlin.math.exp(-1.8f * dt)
                t.vx *= drag
                t.vy *= drag
            }
            t.x += t.vx * dt
            t.y += t.vy * dt
            val pad = t.radius
            if (t.x < pad) { t.x = pad; t.vx = abs(t.vx) * 0.28f }
            if (t.x > w - pad) { t.x = w - pad; t.vx = -abs(t.vx) * 0.28f }
            if (t.y < pad) { t.y = pad; t.vy = abs(t.vy) * 0.25f }
            if (t.y > h - pad) { t.y = h - pad; t.vy = -abs(t.vy) * 0.24f }
            t.rotation += if (t.buoyancy > 0.5f) {
                (theta - t.rotation) * min(1f, 3.5f * dt)
            } else {
                ((t.vx * gy - t.vy * gx) / max(t.radius, 1f)) * dt * 0.25f
            }
        }
        // Gentle non-overlap keeps the decorative objects from occupying the same spot.
        for (i in things.indices) for (j in i + 1 until things.size) {
            val a = things[i]; val b = things[j]
            val dx = b.x - a.x; val dy = b.y - a.y
            val minDistance = (a.radius + b.radius) * 0.82f
            val distance2 = dx * dx + dy * dy
            if (distance2 < minDistance * minDistance && distance2 > 0.01f) {
                val distance = kotlin.math.sqrt(distance2)
                val push = (minDistance - distance) * 0.5f / distance
                a.x -= dx * push; a.y -= dy * push
                b.x += dx * push; b.y += dy * push
            }
        }
    }

    fun draw(canvas: Canvas, alpha: Float) {
        if (alpha <= 0.01f) return
        val baseAlpha = (alpha * 255f).toInt().coerceIn(0, 255)
        for (t in things) {
            canvas.save()
            canvas.translate(t.x, t.y)
            canvas.rotate(Math.toDegrees(t.rotation.toDouble()).toFloat())
            when (t.kind) {
                0 -> {
                    // Pearl: softly shaded sphere with a tiny specular highlight.
                    paint.shader = LinearGradient(
                        -t.radius, -t.radius, t.radius, t.radius,
                        intArrayOf(Color.rgb(255,255,250), Color.rgb(178,218,222), Color.rgb(245,250,239)),
                        null, Shader.TileMode.CLAMP
                    )
                    paint.alpha = baseAlpha
                    canvas.drawCircle(0f, 0f, t.radius, paint)
                    paint.shader = null
                    highlight.strokeWidth = 1.2f * density
                    highlight.alpha = (baseAlpha * 0.78f).toInt()
                    canvas.drawArc(-t.radius * 0.58f, -t.radius * 0.62f, t.radius * 0.18f, t.radius * 0.16f, 205f, 95f, false, highlight)
                }
                1 -> {
                    // Scallop shell with ribbed fan and a shaded lower lip.
                    paint.shader = null
                    paint.color = t.color; paint.alpha = baseAlpha
                    path.rewind()
                    path.moveTo(-t.radius, t.radius * 0.45f)
                    path.quadTo(-t.radius * 1.12f, -t.radius * 0.82f, 0f, -t.radius)
                    path.quadTo(t.radius * 1.12f, -t.radius * 0.82f, t.radius, t.radius * 0.45f)
                    path.quadTo(0f, t.radius * 1.05f, -t.radius, t.radius * 0.45f)
                    path.close()
                    canvas.drawPath(path, paint)
                    highlight.strokeWidth = 1f * density
                    highlight.alpha = (baseAlpha * 0.62f).toInt()
                    for (rib in -2..2) {
                        canvas.drawLine(0f, -t.radius * 0.78f, rib * t.radius * 0.31f, t.radius * 0.55f, highlight)
                    }
                    paint.color = Color.rgb(255, 231, 194); paint.alpha = baseAlpha
                    canvas.drawOval(-t.radius * 0.62f, t.radius * 0.34f, t.radius * 0.62f, t.radius * 0.62f, paint)
                }
                else -> {
                    // Rounded five-armed starfish.
                    paint.shader = null
                    paint.color = t.color; paint.alpha = baseAlpha
                    path.rewind()
                    for (i in 0 until 10) {
                        val angle = -Math.PI / 2.0 + i * Math.PI / 5.0
                        val radius = t.radius * if (i % 2 == 0) 1f else 0.48f
                        val x = kotlin.math.cos(angle).toFloat() * radius
                        val y = kotlin.math.sin(angle).toFloat() * radius
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    path.close()
                    canvas.drawPath(path, paint)
                    paint.color = Color.argb(95, 255, 245, 210); paint.alpha = baseAlpha
                    canvas.drawCircle(-t.radius * 0.18f, -t.radius * 0.12f, t.radius * 0.16f, paint)
                }
            }
            canvas.restore()
        }
    }
}

class MainActivity : Activity() {
    private lateinit var launcherView: LauncherView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        val ivory = Color.rgb(250, 248, 243)
        window.statusBarColor = ivory
        window.navigationBarColor = ivory
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        launcherView = LauncherView()
        setContentView(launcherView)
    }

    override fun onResume() {
        super.onResume()
        if (::launcherView.isInitialized) {
            launcherView.reloadApps()
            launcherView.startMotionSensors()
            launcherView.startWeatherUpdates()
        }
    }

    override fun onPause() {
        if (::launcherView.isInitialized) launcherView.stopMotionSensors()
        super.onPause()
    }

    @Deprecated("Deprecated in Android API; retained for broad device compatibility")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 41 && ::launcherView.isInitialized && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) launcherView.loadWeatherFromLocation()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (::launcherView.isInitialized && launcherView.drawerProgress > 0f) {
            launcherView.closeDrawer()
        } else {
            super.onBackPressed()
        }
    }

    private data class LaunchableApp(
        val label: String,
        val displayLabel: String,
        val icon: Drawable,
        val packageName: String
    ) {
        val raftSeed: Float =
            ((packageName.hashCode() and 0x7fffffff) % 1000) / 1000f
    }

    private enum class GestureMode { NONE, OPENING, CLOSING, SCROLLING }
    private enum class WeatherKind { CLEAR, CLOUDS, RAIN, STORM, SNOW, FOG, NIGHT }

    private data class HitRect(val bounds: RectF, val app: LaunchableApp)

    private inner class LauncherView : View(this@MainActivity) {
        var drawerProgress = 0f
            private set
        val isDrawerOpen: Boolean get() = drawerProgress >= 0.999f

        private val density = resources.displayMetrics.density
        private val sensorManager = this@MainActivity.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        private val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        private val linearAcceleration = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        // Water motion is a damped physical response, not a direct copy of the phone angle.
        private var motionX = 0f
        private var motionY = 0f
        private var targetMotionX = 0f
        private var targetMotionY = 0f
        private var motionVelocityX = 0f
        private var motionVelocityY = 0f
        private var lastPhysicsFrame = 0L
        private var latestRoll = 0f
        private var latestPitch = 0f
        private var neutralRoll = 0f
        private var neutralPitch = 0f
        private var hasNeutralPose = false
        private val rotationMatrix = FloatArray(9)
        private val orientationAngles = FloatArray(3)
        private val gravity = FloatArray(3)
        private var gravityInitialized = false
        private var shakeKickAt = 0L
        private var lastShakeAt = 0L
        private var shakeStrength = 1f
        private val weatherPrefs by lazy { this@MainActivity.getSharedPreferences("myos_weather", Context.MODE_PRIVATE) }
        private var permissionRequested = false
        private var weatherKind = WeatherKind.CLEAR
        // Weather artwork stays hidden until a fresh API result or a still-valid cached result confirms both condition and day/night.
        private var weatherConfirmed = false
        private var weatherLoading = false
        private val skyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val weatherDetailPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val lifePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val fishColors = intArrayOf(Color.rgb(255,132,65),Color.rgb(255,211,74),Color.rgb(79,205,190),Color.rgb(94,165,255),Color.rgb(255,112,145),Color.rgb(181,139,255))
        private val scaledDensity = resources.displayMetrics.scaledDensity
        private val ivory = Color.rgb(250, 248, 243)
        private val white = Color.WHITE
        private val apps = mutableListOf<LaunchableApp>()

        private val photo: Bitmap? = loadOceanPhoto()
        private val drawerWater: Bitmap? = loadDrawerWaterPhoto()
        private val photoRect = RectF()
        private val photoMatrix = Matrix()
        private val photoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val drawerWaterPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private var drawerWaterShader: BitmapShader? = null
        private val fallbackWaterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(14, 124, 140)
        }
        private val drawerBackdropPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val glassEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.4f * density
            color = Color.WHITE
        }
        private val glassGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 5f * density
            color = Color.WHITE
        }
        private val backdropDetailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            color = Color.WHITE
        }
        private val neutralWaterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(190, 201, 199)
        }
        private var photoShader: BitmapShader? = null
        private val homeFadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val foamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = Color.argb(230, 255, 255, 255)
        }
        private val softFoamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = Color.argb(77, 255, 255, 255)
        }
        private val streakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            color = Color.WHITE
        }
        private val streakPath = Path()
        private val debugPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 11f * scaledDensity
            typeface = Typeface.create("monospace", Typeface.BOLD)
            setShadowLayer(2f * density, 0f, 1f * density, Color.BLACK)
        }
        private val debugBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(150, 8, 24, 35)
        }
        private val iconShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(26, 0, 0, 0)
        }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = white
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            textSize = 11f * scaledDensity
            setShadowLayer(2f * density, 0f, 1f * density, Color.argb(90, 0, 0, 0))
        }
        private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(179, 138, 143, 140)
            textAlign = Paint.Align.LEFT
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            textSize = 13f * scaledDensity
        }
        private val wave = WaveShape()
        private val slosh = SloshWater(density)
        private var floatingThings: FloatingThings? = null
        private var homeWaterline = 0f
        private var fadeShader: LinearGradient? = null
        private var tintShader: LinearGradient? = null
        private var animator: ValueAnimator? = null
        private var raftAnimator: ValueAnimator? = null
        private var raftGainAnimator: ValueAnimator? = null
        private var raftGain = 0f
        private var raftStartTime = 0L
        private var downX = 0f
        private var downY = 0f
        private var lastY = 0f
        private var gestureStartY = 0f
        private var gestureStartProgress = 0f
        private var scrollY = 0f
        private var maxScroll = 0f
        private var velocityTracker: VelocityTracker? = null
        private var gestureMode = GestureMode.NONE
        private var isAnimating = false
        private var hitRects = ArrayList<HitRect>()
        private val tempOval = RectF()
        private val columns = 4

        private val motionListener = object : SensorEventListener {
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_ROTATION_VECTOR -> {
                        SensorManager.getRotationMatrixFromVector(rotationMatrix,event.values)
                        // Read gravity direction directly from the rotation matrix. Euler angles
                        // become unreliable near steep phone orientations (the user's test was
                        // around roll=-77°, pitch=43°).
                        latestRoll = kotlin.math.asin(rotationMatrix[6].coerceIn(-1f, 1f))
                        latestPitch = kotlin.math.asin(rotationMatrix[7].coerceIn(-1f, 1f))
                        slosh.setGravity(rotationMatrix[6], rotationMatrix[7], rotationMatrix[8])
                        updateTilt()
                    }
                    Sensor.TYPE_LINEAR_ACCELERATION -> detectShake(event.values[0],event.values[1],event.values[2])
                    Sensor.TYPE_ACCELEROMETER -> {
                        val a=0.82f
                        if(!gravityInitialized) { for(i in 0..2) gravity[i]=event.values[i]; gravityInitialized=true }
                        else for(i in 0..2) gravity[i]=a*gravity[i]+(1f-a)*event.values[i]
                        val linearX=event.values[0]-gravity[0]
                        val linearY=event.values[1]-gravity[1]
                        val linearZ=event.values[2]-gravity[2]
                        slosh.setGravity(gravity[0], gravity[1], gravity[2])
                        // Use the accelerometer for shake detection even when a rotation-vector sensor exists.
                        detectShake(linearX,linearY,linearZ)
                        if(rotationSensor==null) {
                            latestRoll=kotlin.math.atan2(gravity[0],gravity[2])
                            latestPitch=kotlin.math.atan2(-gravity[1],sqrt(gravity[0]*gravity[0]+gravity[2]*gravity[2]))
                            updateTilt()
                        }
                    }
                }
            }
        }
        private fun updateTilt() {
            // Do not accumulate tilt while the drawer is hidden. Capture the phone's
            // actual resting pose on the first sensor sample after the drawer appears.
            if (drawerProgress < 0.05f) {
                targetMotionX = 0f
                targetMotionY = 0f
                return
            }
            if (!hasNeutralPose) {
                neutralRoll = latestRoll
                neutralPitch = latestPitch
                hasNeutralPose = true
            }
            // Let the neutral pose drift very slowly so ordinary hand movement remains
            // responsive without permanently biasing the water after a long hold.
            neutralRoll += (latestRoll - neutralRoll) * 0.004f
            neutralPitch += (latestPitch - neutralPitch) * 0.004f
            targetMotionX = ((latestRoll - neutralRoll) / 0.21f).coerceIn(-1f, 1f)
            targetMotionY = ((latestPitch - neutralPitch) / 0.21f).coerceIn(-1f, 1f)
            postInvalidateOnAnimation()
        }

        // A critically damped spring gives the liquid mass: it lags behind tilt, overshoots gently,
        // and settles instead of snapping to the latest sensor reading.
        private fun advanceWaterPhysics(now:Long) {
            if(lastPhysicsFrame==0L) { lastPhysicsFrame=now; return }
            val dt=((now-lastPhysicsFrame)/1000f).coerceIn(0.001f,0.04f)
            lastPhysicsFrame=now
            slosh.step(dt)
            floatingThings?.step(dt.coerceAtMost(0.033f), width.toFloat(), height.toFloat(),
                slosh.gravityX, slosh.gravityY, slosh.surfaceDistance, slosh.angle)
            motionX = slosh.horizontalMotion
            motionY = slosh.verticalMotion
            val stiffness=18f
            val damping=6.4f
            val ax=(targetMotionX-motionX)*stiffness-motionVelocityX*damping
            val ay=(targetMotionY-motionY)*stiffness-motionVelocityY*damping
            motionVelocityX=(motionVelocityX+ax*dt).coerceIn(-3.2f,3.2f)
            motionVelocityY=(motionVelocityY+ay*dt).coerceIn(-3.2f,3.2f)
            motionX=(motionX+motionVelocityX*dt).coerceIn(-1.15f,1.15f)
            motionY=(motionY+motionVelocityY*dt).coerceIn(-1.15f,1.15f)
        }

        private fun detectShake(x:Float,y:Float,z:Float) {
            val magnitude=sqrt(x*x+y*y+z*z); val now=SystemClock.uptimeMillis()
            if(magnitude>8f && now-lastShakeAt>650L) {
                lastShakeAt=now
                shakeKickAt=now
                shakeStrength=(magnitude/15f).coerceIn(0.7f,1.7f)
                slosh.kick(shakeStrength)
                floatingThings?.shake(shakeStrength)
                // A shake injects momentum into the liquid; the spring and damping do the rest.
                motionVelocityX=(motionVelocityX+x*0.045f).coerceIn(-3.2f,3.2f)
                motionVelocityY=(motionVelocityY+y*0.045f-z*0.018f).coerceIn(-3.2f,3.2f)
                invalidate()
            }
        }
        fun startMotionSensors() {
            sensorManager.unregisterListener(motionListener)
            if(rotationSensor!=null) sensorManager.registerListener(motionListener,rotationSensor,SensorManager.SENSOR_DELAY_GAME)
            else accelerometer?.let { sensorManager.registerListener(motionListener,it,SensorManager.SENSOR_DELAY_GAME) }
            // Prefer linear acceleration; otherwise keep the raw accelerometer active for shake impulses.
            val shakeSensor=linearAcceleration ?: if(rotationSensor!=null) accelerometer else null
            shakeSensor?.let { sensorManager.registerListener(motionListener,it,SensorManager.SENSOR_DELAY_GAME) }
        }
        fun stopMotionSensors() {
            sensorManager.unregisterListener(motionListener)
            targetMotionX=0f; targetMotionY=0f
            motionVelocityX*=0.25f; motionVelocityY*=0.25f
            hasNeutralPose=false
        }

        fun startWeatherUpdates() {
            val cached=weatherPrefs.getString("kind",null)
            val fetchedAt=weatherPrefs.getLong("fetched_at",0L)
            val now=System.currentTimeMillis()
            val cachedKind=cached?.let { runCatching { WeatherKind.valueOf(it) }.getOrNull() }
            val cacheIsFresh=cachedKind!=null && fetchedAt>0L && now>=fetchedAt && now-fetchedAt<60L*60L*1000L

            // Never show yesterday's sky or a default moon/sun while a new confirmation is pending.
            weatherConfirmed=cacheIsFresh
            if(cacheIsFresh) weatherKind=cachedKind!!
            invalidate()

            if(cacheIsFresh) return
            if(this@MainActivity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED) loadWeatherFromLocation()
            else if(!permissionRequested) { permissionRequested=true; this@MainActivity.requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION),41) }
        }
        fun loadWeatherFromLocation() {
            if (this@MainActivity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED || weatherLoading) return
            val manager=this@MainActivity.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            var best: Location?=null
            for (provider in listOf(LocationManager.NETWORK_PROVIDER,LocationManager.PASSIVE_PROVIDER)) {
                try { val candidate=manager.getLastKnownLocation(provider); if(candidate!=null && (best==null || candidate.time>best!!.time)) best=candidate } catch(_:SecurityException) {}
            }
            if(best!=null) { fetchWeather(best!!.latitude,best!!.longitude); return }
            try {
                val listener=object:LocationListener {
                    override fun onLocationChanged(location:Location) { try { manager.removeUpdates(this) } catch(_:Exception) {}; fetchWeather(location.latitude,location.longitude) }
                    @Deprecated("Deprecated by Android") override fun onStatusChanged(provider:String?,status:Int,extras:android.os.Bundle?)=Unit
                    override fun onProviderEnabled(provider:String)=Unit
                    override fun onProviderDisabled(provider:String)=Unit
                }
                if(manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) manager.requestSingleUpdate(LocationManager.NETWORK_PROVIDER,listener,Looper.getMainLooper())
                else if(manager.isProviderEnabled(LocationManager.PASSIVE_PROVIDER)) manager.requestSingleUpdate(LocationManager.PASSIVE_PROVIDER,listener,Looper.getMainLooper())
            } catch(_:Exception) {}
        }
        private fun fetchWeather(latitude:Double,longitude:Double) {
            if(weatherLoading) return
            weatherLoading=true
            Thread {
                var result:WeatherKind?=null
                try {
                    val endpoint="https://api.open-meteo.com/v1/forecast?latitude=$latitude&longitude=$longitude&current=weather_code,is_day&timezone=auto"
                    val connection=URL(endpoint).openConnection() as HttpURLConnection
                    connection.connectTimeout=9000; connection.readTimeout=9000
                    val json=connection.inputStream.bufferedReader().use{it.readText()}; connection.disconnect()
                    val current=JSONObject(json).getJSONObject("current")
                    val code=current.optInt("weather_code",0); val day=current.optInt("is_day",1)==1
                    result=when { !day->WeatherKind.NIGHT; code>=95->WeatherKind.STORM; code in 71..77 || code in 85..86->WeatherKind.SNOW; code in 51..67 || code in 80..82->WeatherKind.RAIN; code==45 || code==48->WeatherKind.FOG; code in 1..3->WeatherKind.CLOUDS; else->WeatherKind.CLEAR }
                } catch(_:Exception) {}
                runOnUiThread {
                    weatherLoading=false
                    result?.let {
                        weatherKind=it
                        weatherConfirmed=true
                        weatherPrefs.edit().putString("kind",it.name).putLong("fetched_at",System.currentTimeMillis()).apply()
                    }
                    // A failed lookup intentionally leaves both scenes neutral.
                    invalidate()
                }
            }.apply { isDaemon=true; name="MYOS-weather" }.start()
        }
        private fun homeBackgroundColor():Int=when(weatherKind) {
            WeatherKind.CLEAR->ivory; WeatherKind.CLOUDS->Color.rgb(205,218,222); WeatherKind.RAIN->Color.rgb(116,145,158)
            WeatherKind.STORM->Color.rgb(35,47,65); WeatherKind.SNOW->Color.rgb(218,231,239); WeatherKind.FOG->Color.rgb(184,197,193)
            WeatherKind.NIGHT->Color.rgb(15,25,48)
        }
        private fun drawWeatherSky(canvas:Canvas) {
            val top:Int; val bottom:Int
            when(weatherKind) {
                WeatherKind.CLEAR->{top=Color.rgb(126,205,231);bottom=Color.rgb(244,218,174)}
                WeatherKind.CLOUDS->{top=Color.rgb(139,164,177);bottom=Color.rgb(207,211,203)}
                WeatherKind.RAIN->{top=Color.rgb(67,91,111);bottom=Color.rgb(140,161,164)}
                WeatherKind.STORM->{top=Color.rgb(24,34,53);bottom=Color.rgb(80,103,119)}
                WeatherKind.SNOW->{top=Color.rgb(166,199,218);bottom=Color.rgb(230,237,237)}
                WeatherKind.FOG->{top=Color.rgb(153,172,171);bottom=Color.rgb(211,218,208)}
                WeatherKind.NIGHT->{top=Color.rgb(8,17,42);bottom=Color.rgb(43,65,91)}
            }
            skyPaint.shader=LinearGradient(0f,0f,0f,homeWaterline,top,bottom,Shader.TileMode.CLAMP)
            canvas.drawRect(0f,0f,width.toFloat(),homeWaterline+2f*density,skyPaint); skyPaint.shader=null
            if(weatherKind==WeatherKind.CLEAR) {
                weatherDetailPaint.color=Color.rgb(255,232,166)
                canvas.drawCircle(width*0.76f,homeWaterline*0.38f,20f*density,weatherDetailPaint)
            } else if(weatherKind==WeatherKind.NIGHT) {
                weatherDetailPaint.color=Color.rgb(239,241,228); canvas.drawCircle(width*0.76f,homeWaterline*0.38f,14f*density,weatherDetailPaint)
                weatherDetailPaint.color=Color.rgb(24,39,65); canvas.drawCircle(width*0.76f+6f*density,homeWaterline*0.38f-4f*density,13f*density,weatherDetailPaint)
                weatherDetailPaint.color=Color.argb(175,245,245,229)
                for(i in 0 until 18) canvas.drawCircle(((i*73)%max(1,width)).toFloat(),(12+(i*41)%max(1,(homeWaterline*0.72f).toInt())).toFloat(),1f*density,weatherDetailPaint)
            } else if(weatherKind!=WeatherKind.CLEAR) {
                weatherDetailPaint.color=Color.argb(if(weatherKind==WeatherKind.STORM) 155 else 110,224,232,231)
                for(i in 0 until 4) {
                    val cx=width*(0.12f+i*0.25f); val cy=homeWaterline*(0.24f+(i%2)*0.13f); val r=(18f+(i%3)*5f)*density
                    canvas.drawCircle(cx,cy,r,weatherDetailPaint); canvas.drawCircle(cx+r*0.8f,cy-r*0.25f,r*0.82f,weatherDetailPaint)
                    canvas.drawCircle(cx+r*1.5f,cy,r*0.68f,weatherDetailPaint)
                }
            }
        }


        private fun loadOceanPhoto(): Bitmap? = try {
            assets.open("myos_ocean.jpg").use { BitmapFactory.decodeStream(it) }
        } catch (_: Exception) {
            null
        }

        private fun loadDrawerWaterPhoto(): Bitmap? = try {
            assets.open("myos_drawer_water.jpg").use { BitmapFactory.decodeStream(it) }
        } catch (_: Exception) {
            null
        }

        init {
            isFocusable = true
            isClickable = true
            contentDescription = "MYOS Home. Swipe up to open your apps."
            reloadApps()
        }

        fun reloadApps() {
            val intent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
            val results: List<ResolveInfo> = if (Build.VERSION.SDK_INT >= 33) {
                packageManager.queryIntentActivities(
                    intent,
                    android.content.pm.PackageManager.ResolveInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.queryIntentActivities(intent, 0)
            }
            apps.clear()
            results
                .filter { it.activityInfo.packageName != context.packageName }
                .distinctBy { it.activityInfo.packageName }
                .sortedBy { it.loadLabel(packageManager).toString().lowercase() }
                .forEach { info ->
                    apps.add(
                        LaunchableApp(
                            info.loadLabel(packageManager).toString(),
                            info.loadLabel(packageManager).toString().let { if (it.length > 15) it.take(14) + "…" else it },
                            info.loadIcon(packageManager),
                            info.activityInfo.packageName
                        )
                    )
                }
            hitRects.clear()
            apps.forEach { hitRects.add(HitRect(RectF(), it)) }
            calculateScrollBounds()
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            homeWaterline = h * 0.58f
            floatingThings = FloatingThings(density, w.toFloat(), h.toFloat())
            val fadeBottom = homeWaterline + 48f * density
            fadeShader = LinearGradient(
                0f, homeWaterline, 0f, fadeBottom,
                ivory, Color.TRANSPARENT, Shader.TileMode.CLAMP
            )
            homeFadePaint.shader = fadeShader
            photo?.let { bitmap ->
                val scale = max(w.toFloat() / bitmap.width, h.toFloat() / bitmap.height)
                val dx = (w - bitmap.width * scale) / 2f
                val dy = (h - bitmap.height * scale) / 2f
                photoMatrix.setScale(scale, scale)
                photoMatrix.postTranslate(dx, dy)
                photoShader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also {
                    it.setLocalMatrix(photoMatrix)
                }
                photoPaint.shader = photoShader
            }
            drawerWater?.let { bitmap ->
                val scale = max(w.toFloat() / bitmap.width, h.toFloat() / bitmap.height)
                val dx = (w - bitmap.width * scale) / 2f
                val dy = (h - bitmap.height * scale) / 2f
                val drawerMatrix = Matrix().apply {
                    setScale(scale, scale)
                    postTranslate(dx, dy)
                }
                drawerWaterShader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also {
                    it.setLocalMatrix(drawerMatrix)
                }
                drawerWaterPaint.shader = drawerWaterShader
            }
            tintShader = LinearGradient(
                0f, 0f, 0f, h.toFloat(),
                intArrayOf(Color.rgb(14, 124, 140), Color.rgb(47, 180, 188)),
                null, Shader.TileMode.CLAMP
            )
            tintPaint.shader = tintShader
            calculateScrollBounds()
        }

        private fun calculateScrollBounds() {
            if (height <= 0) return
            val top = 0.18f * height
            val rowHeight = 104f * density
            val rows = (apps.size + columns - 1) / columns
            val contentBottom = top + max(0, rows - 1) * rowHeight + 92f * density
            maxScroll = max(0f, contentBottom - (height - 24f * density))
            scrollY = scrollY.coerceIn(0f, maxScroll)
        }

        fun closeDrawer() {
            if (drawerProgress <= 0f) return
            settleDrawer(false)
        }

        private fun startRaftMotion() {
            raftGainAnimator?.cancel()
            raftGainAnimator = null
            if (raftAnimator?.isRunning != true) {
                raftStartTime = SystemClock.uptimeMillis()
                lastPhysicsFrame=SystemClock.uptimeMillis()
                raftAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 10_000L
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = LinearInterpolator()
                    addUpdateListener {
                        advanceWaterPhysics(SystemClock.uptimeMillis())
                        invalidate()
                    }
                    start()
                }
            }
            animateRaftGain(1f, 900L, false)
        }

        private fun stopRaftMotion() {
            if (raftAnimator == null && raftGain <= 0f) return
            animateRaftGain(0f, 350L, true)
        }

        private fun animateRaftGain(target: Float, durationMs: Long, stopClock: Boolean) {
            raftGainAnimator?.cancel()
            raftGainAnimator = null
            val gainAnimation = ValueAnimator.ofFloat(raftGain, target).apply {
                duration = durationMs
                interpolator = LinearInterpolator()
                addUpdateListener {
                    raftGain = it.animatedValue as Float
                    invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (raftGainAnimator === animation) {
                            raftGainAnimator = null
                            if (stopClock && raftGain <= 0.001f) {
                                raftAnimator?.cancel()
                                raftAnimator = null
                            }
                        }
                    }
                })
            }
            raftGainAnimator = gainAnimation
            gainAnimation.start()
        }

        private fun settleDrawer(open: Boolean) {
            if (!open) stopRaftMotion()
            val currentProgress = drawerProgress
            animator?.cancel()
            drawerProgress = currentProgress
            isAnimating = true
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            val target = if (open) 1f else 0f
            val start = currentProgress
            if (!open) {
                hasNeutralPose = false
            }
            animator = ValueAnimator.ofFloat(start, target).apply {
                duration = if (open) 520L else 420L
                interpolator = if (open) {
                    PathInterpolator(0.22f, 1f, 0.36f, 1f)
                } else {
                    PathInterpolator(0.4f, 0f, 0.2f, 1f)
                }
                addUpdateListener {
                    drawerProgress = it.animatedValue as Float
                    invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        drawerProgress = target
                        if (!open) scrollY = 0f
                        isAnimating = false
                        setLayerType(View.LAYER_TYPE_NONE, null)
                        if (open) startRaftMotion()
                        invalidate()
                    }
                })
                start()
            }
        }

        private fun drawPhoto(canvas: Canvas) {
            val bitmap = photo ?: return
            val w = width.toFloat()
            val h = height.toFloat()
            val scale = max(w / bitmap.width, h / bitmap.height)
            val dw = bitmap.width * scale
            val dh = bitmap.height * scale
            photoRect.set((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f)
            canvas.drawBitmap(bitmap, null, photoRect, null)
        }

        private fun drawHome(canvas: Canvas) {
            if (!weatherConfirmed) {
                canvas.drawColor(ivory)
                drawHint(canvas)
                return
            }
            canvas.drawColor(homeBackgroundColor())
            drawWeatherSky(canvas)
            if (photo != null) {
                canvas.save()
                canvas.clipRect(0f, homeWaterline, width.toFloat(), height.toFloat())
                // The home scenery is static; motion belongs to the drawer water layer.
                drawPhoto(canvas)
                canvas.restore()
                homeFadePaint.shader = fadeShader
                canvas.drawRect(0f, homeWaterline, width.toFloat(), homeWaterline + 48f * density, homeFadePaint)
            }
            drawHint(canvas)
        }

        private fun drawHint(canvas: Canvas) {
            val text = "Swipe up to open"
            val letterSpacing = 0.12f * 13f * scaledDensity
            var total = 0f
            for (i in text.indices) total += hintPaint.measureText(text, i, i + 1)
            total += letterSpacing * (text.length - 1)
            var x = (width - total) / 2f
            val baseline = homeWaterline - 20f * density
            for (i in text.indices) {
                val next = x + hintPaint.measureText(text, i, i + 1)
                hintPaint.color=if(weatherKind==WeatherKind.STORM || weatherKind==WeatherKind.NIGHT) Color.argb(210,245,248,250) else Color.argb(179,108,119,119)
                canvas.drawText(text,i,i+1,x,baseline,hintPaint)
                x = next + letterSpacing
            }
        }

        private fun drawStreaks(c: Canvas, w: Float, h: Float, p: Float) {
            c.save()
            c.clipPath(wave.fill)
            for (i in 0 until 7) {
                val depth = 0.35f + i * 0.1f
                val y = h * (0.12f + i * 0.125f) + motionY * 10f * density * depth
                val x = w * ((i * 0.37f) % 1f) + motionX * 60f * density * depth
                val len = w * (0.25f + 0.08f * (i % 3))
                streakPaint.strokeWidth = (1.2f + depth) * density
                streakPaint.alpha = (p * p * 38f * depth).toInt().coerceIn(0, 255)
                streakPath.rewind()
                streakPath.moveTo(x - len / 2f, y)
                streakPath.quadTo(x, y - 6f * density, x + len / 2f, y)
                c.drawPath(streakPath, streakPaint)
            }

            // A shake launches a visible wave ring that expands and fades inside the water.
            val age = if (shakeKickAt == 0L) Long.MAX_VALUE else (SystemClock.uptimeMillis() - shakeKickAt).coerceAtLeast(0L)
            if (age < 1250L) {
                val progress = age / 1250f
                val radius = progress * max(w, h) * 0.78f
                val alpha = ((1f - progress) * 115f * shakeStrength.coerceAtMost(1.4f)).toInt().coerceIn(0, 180)
                streakPaint.strokeWidth = (2.2f + (1f - progress) * 2f) * density
                streakPaint.alpha = alpha
                c.drawCircle(w * 0.5f, h * 0.52f, radius, streakPaint)
            }
            c.restore()
        }

        private fun drawMotionDebug(c: Canvas) {
            val line = String.format(
                Locale.US,
                "roll=%.0f° pitch=%.0f° mx=%.2f my=%.2f",
                Math.toDegrees(latestRoll.toDouble()),
                Math.toDegrees(latestPitch.toDouble()),
                motionX,
                motionY
            )
            val x = 10f * density
            val baseline = 25f * density
            val padX = 7f * density
            val padY = 5f * density
            val width = debugPaint.measureText(line)
            c.drawRoundRect(
                x, baseline - debugPaint.textSize - padY,
                x + width + padX * 2f, baseline + padY,
                6f * density, 6f * density, debugBackgroundPaint
            )
            c.drawText(line, x + padX, baseline, debugPaint)
        }

        // Stationary underwater environment, separate from the animated water element.
        private fun drawDrawerBackdrop(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val top = if (!weatherConfirmed) Color.rgb(18, 55, 69) else when (weatherKind) {
                WeatherKind.NIGHT, WeatherKind.STORM -> Color.rgb(8, 35, 53)
                WeatherKind.RAIN, WeatherKind.FOG, WeatherKind.CLOUDS -> Color.rgb(18, 66, 78)
                WeatherKind.SNOW -> Color.rgb(47, 91, 104)
                else -> Color.rgb(9, 78, 91)
            }
            val bottom = if (!weatherConfirmed) Color.rgb(25, 83, 91) else when (weatherKind) {
                WeatherKind.NIGHT, WeatherKind.STORM -> Color.rgb(13, 65, 82)
                WeatherKind.RAIN, WeatherKind.FOG, WeatherKind.CLOUDS -> Color.rgb(17, 91, 102)
                WeatherKind.SNOW -> Color.rgb(73, 126, 137)
                else -> Color.rgb(13, 112, 123)
            }
            drawerBackdropPaint.shader = LinearGradient(0f, 0f, 0f, h, top, bottom, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h, drawerBackdropPaint)
            drawerBackdropPaint.shader = null
            // Fixed underwater light shafts; intentionally unaffected by phone motion.
            backdropDetailPaint.strokeWidth = 1.2f * density
            backdropDetailPaint.alpha = 22
            for (i in 0 until 5) {
                val x = w * (0.08f + i * 0.22f)
                val y = h * (0.08f + (i % 2) * 0.06f)
                canvas.drawLine(x, y, x + w * 0.09f, h * 0.88f, backdropDetailPaint)
            }
        }

        private fun drawWater(canvas: Canvas) {
            // The drawer's scenery is static. This is a translucent, gravity-aligned
            // water volume with a fixed 45% fill level and an independently sloshing surface.
            slosh.draw(canvas, width.toFloat(), height.toFloat(), drawerProgress)
        }

        // The phone screen acts as the clear vessel: fixed bevels and edge reflections
        // create a sense of glass thickness without moving the drawer background.
        private fun drawGlassDepthFrame(canvas: Canvas) {
            val inset = 3.5f * density
            val w = width.toFloat()
            val h = height.toFloat()
            val left = inset
            val top = inset
            val right = w - inset
            val bottom = h - inset
            glassGlowPaint.alpha = 24
            canvas.drawRoundRect(left, top, right, bottom, 18f * density, 18f * density, glassGlowPaint)
            glassEdgePaint.alpha = 82
            canvas.drawRoundRect(left, top, right, bottom, 18f * density, 18f * density, glassEdgePaint)
            // Stronger vertical edge glints suggest curved transparent side walls.
            glassGlowPaint.alpha = 34
            canvas.drawLine(left + 2f * density, top + 28f * density, left + 2f * density, bottom - 34f * density, glassGlowPaint)
            canvas.drawLine(right - 2f * density, top + 28f * density, right - 2f * density, bottom - 34f * density, glassGlowPaint)
            glassEdgePaint.alpha = 108
            canvas.drawLine(left + 1.5f * density, top + 32f * density, left + 1.5f * density, bottom - 38f * density, glassEdgePaint)
            canvas.drawLine(right - 1.5f * density, top + 32f * density, right - 1.5f * density, bottom - 38f * density, glassEdgePaint)
        }

        private fun drawWaterLife(canvas:Canvas) {
            if(!weatherConfirmed || raftAnimator?.isRunning!=true || drawerProgress<0.85f) return
            val now=(SystemClock.uptimeMillis()-raftStartTime)/1000f; val w=width.toFloat(); val h=height.toFloat()
            canvas.save()
            slosh.clipToWater(canvas, w, h)
            for(i in 0 until 19) {
                val seed=i*0.618f; val progress=(now*(0.055f+(i%5)*0.012f)+seed)%1.18f
                val x=(0.08f+((i*37)%83)/100f)*w+sin(now*1.3f+seed*9f)*7f*density
                val y=h*(1f-slosh.fill*(0.08f+progress*0.92f)); val radius=(1.7f+(i%4)*0.8f)*density
                lifePaint.style=Paint.Style.STROKE; lifePaint.strokeWidth=1f*density; lifePaint.color=Color.argb(75+(i%4)*18,224,250,255)
                canvas.drawCircle(x,y,radius,lifePaint); lifePaint.style=Paint.Style.FILL; lifePaint.color=Color.argb(105,255,255,255)
                canvas.drawCircle(x-radius*0.35f,y-radius*0.35f,radius*0.22f,lifePaint)
            }
            for(i in 0 until 8) {
                val seed=i*0.731f; val direction=if(i%2==0) 1f else -1f; val speed=10f+(i%4)*4f
                val travel=now*speed*direction+seed*w*1.8f; val span=w+70f*density
                val x=((travel%span)+span)%span-35f*density
                val y=h*(1f-slosh.fill*(0.12f+(i%5)*0.16f))+sin(now*0.7f+seed*8f)*9f*density
                val size=(10f+(i%3)*2.5f)*density; val wag=sin(now*5f+seed*11f)*size*0.22f
                val saved=canvas.save(); canvas.translate(x,y); canvas.scale(direction,1f)
                val body=Path().apply { moveTo(-size*0.48f,0f); cubicTo(-size*0.22f,-size*0.42f,size*0.34f,-size*0.35f,size*0.48f,0f); cubicTo(size*0.30f,size*0.34f,-size*0.22f,size*0.40f,-size*0.48f,0f); close() }
                lifePaint.style=Paint.Style.FILL; lifePaint.color=fishColors[i%fishColors.size]; lifePaint.alpha=if(i<3) 175 else 220; canvas.drawPath(body,lifePaint)
                val dorsal=Path().apply { moveTo(-size*0.18f,-size*0.22f); cubicTo(0f,-size*0.48f,size*0.28f,-size*0.42f,size*0.37f,-size*0.16f); close() }
                canvas.drawPath(dorsal,lifePaint)
                val ventral=Path().apply { moveTo(-size*0.05f,size*0.22f); lineTo(size*0.22f,size*0.34f+wag*0.12f); lineTo(size*0.27f,size*0.18f); close() }
                canvas.drawPath(ventral,lifePaint)
                val tail=Path().apply { moveTo(-size*0.40f,0f); cubicTo(-size*0.56f,-size*0.12f,-size*0.70f,-size*0.34f+wag,-size*0.88f,-size*0.30f+wag); cubicTo(-size*0.78f,wag*0.2f,-size*0.72f,size*0.30f+wag,-size*0.88f,size*0.32f+wag); cubicTo(-size*0.62f,size*0.25f,-size*0.50f,size*0.10f,-size*0.40f,0f); close() }
                canvas.drawPath(tail,lifePaint)
                weatherDetailPaint.color=Color.argb(100,18,43,54)
                val pectoral=Path().apply { moveTo(size*0.02f,size*0.03f); lineTo(size*0.22f,size*0.27f+wag*0.1f); lineTo(size*0.30f,size*0.02f); close() }
                canvas.drawPath(pectoral,weatherDetailPaint)
                weatherDetailPaint.style=Paint.Style.STROKE; weatherDetailPaint.strokeWidth=0.65f*density
                weatherDetailPaint.color=Color.argb(105,255,255,255)
                canvas.drawLine(-size*0.18f,0f,size*0.28f,0f,weatherDetailPaint)
                canvas.drawArc(size*0.12f,-size*0.18f,size*0.35f,size*0.18f,80f,190f,false,weatherDetailPaint)
                weatherDetailPaint.style=Paint.Style.FILL
                lifePaint.color=Color.WHITE; lifePaint.alpha=235; canvas.drawCircle(size*0.27f,-size*0.09f,size*0.055f,lifePaint)
                lifePaint.color=Color.rgb(28,55,65); canvas.drawCircle(size*0.29f,-size*0.09f,size*0.027f,lifePaint); canvas.restoreToCount(saved)
            }
            if(weatherKind==WeatherKind.RAIN || weatherKind==WeatherKind.STORM) {
                lifePaint.style=Paint.Style.STROKE; lifePaint.strokeWidth=1.3f*density; lifePaint.color=Color.argb(if(weatherKind==WeatherKind.STORM) 105 else 70,218,239,248)
                for(i in 0 until 24) { val x=((i*47f+now*(45f+i%5*9f))%(w+20f))-10f; val y=(i*71f+now*130f)%h; canvas.drawLine(x,y,x-3f*density,y+10f*density,lifePaint) }
            } else if(weatherKind==WeatherKind.SNOW) {
                lifePaint.style=Paint.Style.FILL; lifePaint.color=Color.argb(190,245,252,255)
                for(i in 0 until 18) { val x=(i*59f+sin(now+i)*15f)%w; val y=(i*83f+now*24f)%h; canvas.drawCircle(x,y,(1.2f+i%3)*density,lifePaint) }
            }
            canvas.restore(); lifePaint.alpha=255
        }

        private fun drawApps(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val horizontalPadding = 18f * density
            val cellWidth = (w - horizontalPadding * 2f) / columns
            val iconSize = 56f * density
            val firstRowY = 0.19f * h
            val rowHeight = 104f * density
            val labelOffset = 13f * density
            val floating = raftGain > 0f && raftAnimator?.isRunning == true
            val raftTime = if (floating) {
                (SystemClock.uptimeMillis() - raftStartTime) / 1000f
            } else {
                0f
            }
            canvas.save()
            canvas.clipRect(0f, 0f, w, h)
            canvas.translate(0f, -scrollY)

            for (index in apps.indices) {
                val row = index / columns
                val col = index % columns
                val centerX = horizontalPadding + cellWidth * (col + 0.5f)
                val columnFloat = if (col % 2 == 0) -10f else 10f
                val centerY = firstRowY + row * rowHeight + columnFloat * density
                val waterY = height - drawerProgress * height * 1.08f
                val columnDelay = col * 24f * density
                val reveal = ((centerY - waterY - columnDelay) / (120f * density)).coerceIn(0f, 1f)
                if (reveal <= 0f) {
                    hitRects[index].bounds.setEmpty()
                    continue
                }

                val eased = reveal * reveal * (3f - 2f * reveal)
                val alpha = (255f * eased).toInt()
                val scale = 0.92f + 0.08f * eased
                val translateY = (1f - eased) * 24f * density
                val cy = centerY + translateY
                val app = apps[index]
                var raftX = 0f
                var raftY = 0f
                var raftRotation = 0f
                if (floating) {
                    val seed = app.raftSeed
                    val period = 5.5f + seed * 3f
                    val driftPeriod = 9f + seed * 4f
                    val phase = seed * 2f * PI.toFloat()
                    val bob =
                        0.65f * sin(2f * PI.toFloat() * raftTime / period + phase) +
                        0.35f * sin(2f * PI.toFloat() * raftTime / (period * 1.7f) + phase * 1.7f)
                    val drift = sin(2f * PI.toFloat() * raftTime / driftPeriod + phase * 0.6f)
                    val tilt = sin(2f * PI.toFloat() * raftTime / (period * 1.3f) + phase * 2.3f)
                    raftX = drift * 8.5f * density * raftGain
                    raftY = bob * 13f * density * raftGain
                    raftRotation = tilt * 4.2f * raftGain
                }
                // Icons are lighter than the water: they follow its drift with a smaller, delayed response.
                val drawCenterX=centerX+raftX+motionX*3.5f*density+motionVelocityX*0.65f*density
                val drawCy=cy+raftY+motionY*2.5f*density+motionVelocityY*0.45f*density
                val half = iconSize * scale / 2f

                val groupSave = canvas.save()
                canvas.rotate(raftRotation, drawCenterX, drawCy + half + labelOffset * 0.5f)
                tempOval.set(drawCenterX - 18f * density, drawCy + half + 5f * density,
                    drawCenterX + 18f * density, drawCy + half + 11f * density)
                iconShadowPaint.alpha = (26f * eased).toInt()
                canvas.drawOval(tempOval, iconShadowPaint)

                canvas.save()
                canvas.translate(drawCenterX, drawCy)
                canvas.scale(scale, scale)
                app.icon.alpha = alpha
                app.icon.setBounds((-iconSize / 2f).toInt(), (-iconSize / 2f).toInt(),
                    (iconSize / 2f).toInt(), (iconSize / 2f).toInt())
                app.icon.draw(canvas)
                canvas.restore()
                app.icon.alpha = 255

                labelPaint.alpha = alpha
                canvas.drawText(app.displayLabel, drawCenterX, drawCy + half + labelOffset, labelPaint)
                canvas.restoreToCount(groupSave)

                val hitTop = drawCy - half - 8f * density
                val hitBottom = drawCy + half + 34f * density
                hitRects[index].bounds.set(
                    drawCenterX - cellWidth * 0.46f, hitTop,
                    drawCenterX + cellWidth * 0.46f, hitBottom
                )
            }
            canvas.restore()
            labelPaint.alpha = 255
            iconShadowPaint.alpha = 26
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (drawerProgress <= 0f) {
                drawHome(canvas)
                return
            }
            val h = height.toFloat()
            val w = width.toFloat()
            // The water rises as its own element but keeps a visible surface when fully open.
            val waterY = h - drawerProgress * h * 1.08f
            val shakeAge=(SystemClock.uptimeMillis()-shakeKickAt).coerceAtLeast(0L)
            val shake=if(shakeKickAt==0L) 0f else (1f-shakeAge/1200f).coerceIn(0f,1f)*shakeStrength
            if (drawerProgress < 1f) drawHome(canvas)
            // Reveal the stationary drawer environment only where the drawer has risen.
            // This keeps the home scene visible during the transition.
            canvas.save()
            canvas.clipRect(0f, waterY.coerceAtLeast(0f), w, h)
            drawDrawerBackdrop(canvas)
            canvas.restore()
            drawWater(canvas)
            drawMotionDebug(canvas)
            drawWaterLife(canvas)
            canvas.save()
            slosh.clipToWater(canvas, w, h)
            floatingThings?.draw(canvas, drawerProgress)
            canvas.restore()
            drawApps(canvas)
            drawGlassDepthFrame(canvas)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val currentProgress = drawerProgress
                    animator?.cancel()
                    drawerProgress = currentProgress
                    isAnimating = false
                    setLayerType(View.LAYER_TYPE_NONE, null)
                    downX = event.x
                    downY = event.y
                    lastY = event.y
                    gestureStartY = event.y
                    gestureStartProgress = drawerProgress
                    velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
                    gestureMode = when {
                        drawerProgress <= 0f -> GestureMode.OPENING
                        drawerProgress >= 0.999f -> GestureMode.SCROLLING
                        else -> GestureMode.CLOSING
                    }
                    if (drawerProgress >= 0.999f) startRaftMotion()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    velocityTracker?.addMovement(event)
                    val dy = event.y - downY
                    when (gestureMode) {
                        GestureMode.OPENING -> {
                            drawerProgress = (gestureStartProgress - (event.y - gestureStartY) / (height * 0.70f)).coerceIn(0f, 1f)
                            invalidate()
                        }
                        GestureMode.CLOSING -> {
                            if (dy > 0f) {
                                drawerProgress = (gestureStartProgress - (event.y - gestureStartY) / (height * 0.70f)).coerceIn(0f, 1f)
                                invalidate()
                            } else if (drawerProgress >= 0.999f && scrollY > 0f) {
                                gestureMode = GestureMode.SCROLLING
                            }
                        }
                        GestureMode.SCROLLING -> {
                            val delta = event.y - lastY
                            if (scrollY <= 0f && delta > 0f) {
                                stopRaftMotion()
                                gestureMode = GestureMode.CLOSING
                                gestureStartY = event.y
                                gestureStartProgress = 1f
                                drawerProgress = 1f
                            } else {
                                scrollY = (scrollY - delta).coerceIn(0f, maxScroll)
                            }
                            invalidate()
                        }
                        GestureMode.NONE -> Unit
                    }
                    lastY = event.y
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    velocityTracker?.addMovement(event)
                    velocityTracker?.computeCurrentVelocity(1000)
                    val velocityY = velocityTracker?.yVelocity ?: 0f
                    velocityTracker?.recycle()
                    velocityTracker = null

                    if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                        if (drawerProgress > 0f) settleDrawer(drawerProgress >= 0.35f)
                        return true
                    }

                    when (gestureMode) {
                        GestureMode.OPENING, GestureMode.CLOSING -> {
                            val flingOpen = velocityY < -800f * density
                            val flingClose = velocityY > 800f * density
                            val shouldOpen = when {
                                flingOpen -> true
                                flingClose -> false
                                else -> drawerProgress > 0.35f
                            }
                            settleDrawer(shouldOpen)
                        }
                        GestureMode.SCROLLING -> Unit
                        GestureMode.NONE -> Unit
                    }

                    if (drawerProgress >= 0.98f && abs(event.x - downX) < 18f * density &&
                        abs(event.y - downY) < 18f * density) {
                        val hit = hitRects.lastOrNull { it.bounds.contains(event.x, event.y + scrollY) }
                        hit?.app?.let { app ->
                            packageManager.getLaunchIntentForPackage(app.packageName)?.let(::startActivity)
                        }
                    }
                    performClick()
                    gestureMode = GestureMode.NONE
                    return true
                }
            }
            return true
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }

    private class WaveShape {
        private val xs = floatArrayOf(-0.12f, 0.17f, 0.46f, 0.79f, 1.12f)
        private val ys = floatArrayOf(0.55f, -1.00f, 0.70f, -0.75f, 0.45f)
        val edge = Path()
        val fill = Path()

        fun build(w:Float,h:Float,waterY:Float,amp:Float,phase:Float,tiltX:Float,tiltY:Float,ripple:Float,velocityX:Float,velocityY:Float,density:Float) {
            // Keep the drawer reveal shift separate from tilt: tilt moves the whole water mass.
            val shift=phase*0.06f*w+tiltX*14f*density
            edge.rewind()
            var px=xs[0]*w+shift; var py=pointY(0,waterY,amp,phase,tiltX,tiltY,ripple,velocityX,velocityY,density)
            edge.moveTo(px,py)
            for(i in 1 until xs.size) {
                val x=xs[i]*w+shift; val y=pointY(i,waterY,amp,phase,tiltX,tiltY,ripple,velocityX,velocityY,density); val dx=x-px
                edge.cubicTo(px+dx*0.45f,py,x-dx*0.45f,y,x,y); px=x; py=y
            }
            fill.set(edge); fill.lineTo(px,h); fill.lineTo(xs[0]*w+shift,h); fill.close()
        }
        private fun pointY(index:Int,base:Float,amp:Float,phase:Float,tiltX:Float,tiltY:Float,ripple:Float,velocityX:Float,velocityY:Float,density:Float):Float {
            val wavePhase=phase*2f*PI.toFloat()+index*1.7f
            val wobble=1f+0.18f*sin(wavePhase+tiltX*0.85f)
            // Tilt creates a visible liquid slope; momentum bends the surface into travelling slosh crests.
            val slope=xs[index]*tiltX*32f*density-tiltY*12f*density
            val travelling=(velocityX*0.75f+velocityY*0.35f)*10f*density*sin(index*1.45f+phase*7.5f)
            val rebound=ripple*8f*density*sin(index*2.3f+phase*8f)
            return base+ys[index]*amp*wobble+slope+travelling+rebound
        }
    }
}