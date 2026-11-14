package com.myos.home

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity(), SensorEventListener {
    private lateinit var launcherView: LauncherView
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var filteredX = 0f
    private var filteredY = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = Color.rgb(242, 237, 225)
        window.navigationBarColor = Color.rgb(242, 237, 225)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        launcherView = LauncherView()
        setContentView(launcherView)
    }

    override fun onResume() {
        super.onResume()
        if (::launcherView.isInitialized) launcherView.reloadApps()
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        if (::sensorManager.isInitialized) sensorManager.unregisterListener(this)
        super.onPause()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER || !::launcherView.isInitialized) return
        val targetX = (-event.values[0] / SensorManager.GRAVITY_EARTH).coerceIn(-1f, 1f)
        val targetY = ((event.values[1] / SensorManager.GRAVITY_EARTH) - 0.5f).coerceIn(-1f, 1f)
        filteredX += (targetX - filteredX) * 0.08f
        filteredY += (targetY - filteredY) * 0.08f
        launcherView.setParallax(filteredX, filteredY)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (::launcherView.isInitialized && launcherView.isDrawerOpen) {
            launcherView.closeDrawer()
        } else {
            super.onBackPressed()
        }
    }

    private enum class IconKind {
        PHONE, MESSAGES, BROWSER, CAMERA, GALLERY, SETTINGS, MUSIC, MAPS,
        MAIL, CALENDAR, CONTACTS, FILES, VIDEO, WEATHER, GENERIC
    }

    private data class LaunchableApp(
        val label: String,
        val icon: Drawable,
        val packageName: String,
        val activityName: String,
        val kind: IconKind?
    )

    private inner class LauncherView : View(this@MainActivity) {
        var isDrawerOpen = false
            private set

        private val paper = Color.rgb(242, 237, 225)
        private val paperShade = Color.rgb(229, 220, 202)
        private val ink = Color.rgb(42, 40, 35)
        private val fadedInk = Color.rgb(112, 107, 96)
        private val sealRed = Color.rgb(179, 58, 50)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val apps = mutableListOf<LaunchableApp>()
        private val homeApps = mutableListOf<LaunchableApp>()
        private val iconRects = mutableListOf<Pair<RectF, LaunchableApp>>()
        private var downX = 0f
        private var downY = 0f
        private var lastY = 0f
        private var scrollY = 0f
        private var maxScroll = 0f
        private var drawerProgress = 0f
        private var drawerAnimator: ValueAnimator? = null
        private var isAnimatingDrawer = false
        private var parallaxX = 0f
        private var parallaxY = 0f
        private val density = resources.displayMetrics.density
        private val scaledDensity = resources.displayMetrics.scaledDensity

        init {
            isFocusable = true
            contentDescription = "MYOS Home. Swipe up to open your apps."
            reloadApps()
        }

        fun setParallax(x: Float, y: Float) {
            parallaxX += (x - parallaxX) * 0.28f
            parallaxY += (y - parallaxY) * 0.28f
            if (!isDrawerOpen) invalidate()
        }

        private fun classify(label: String, packageName: String): IconKind? {
            val value = (label + " " + packageName).lowercase()
            return when {
                listOf("dialer", "phone", "contacts.phone", "telephony").any { value.contains(it) } -> IconKind.PHONE
                listOf("message", "sms", "messaging").any { value.contains(it) } -> IconKind.MESSAGES
                listOf("chrome", "browser", "firefox", "brave", "edge", "opera").any { value.contains(it) } -> IconKind.BROWSER
                listOf("camera").any { value.contains(it) } -> IconKind.CAMERA
                listOf("gallery", "photos", "image").any { value.contains(it) } -> IconKind.GALLERY
                listOf("setting", "preferences").any { value.contains(it) } -> IconKind.SETTINGS
                listOf("music", "spotify", "audio").any { value.contains(it) } -> IconKind.MUSIC
                listOf("maps", "navigation").any { value.contains(it) } -> IconKind.MAPS
                listOf("gmail", "mail", "email", "outlook").any { value.contains(it) } -> IconKind.MAIL
                listOf("calendar").any { value.contains(it) } -> IconKind.CALENDAR
                listOf("contacts", "address book").any { value.contains(it) } -> IconKind.CONTACTS
                listOf("files", "file manager", "documents").any { value.contains(it) } -> IconKind.FILES
                listOf("youtube", "video", "player").any { value.contains(it) } -> IconKind.VIDEO
                listOf("weather").any { value.contains(it) } -> IconKind.WEATHER
                else -> null
            }
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
                    val label = info.loadLabel(packageManager).toString()
                    apps.add(
                        LaunchableApp(
                            label,
                            info.loadIcon(packageManager),
                            info.activityInfo.packageName,
                            info.activityInfo.name,
                            classify(label, info.activityInfo.packageName)
                        )
                    )
                }

            val preferred = listOf(
                IconKind.PHONE, IconKind.MESSAGES, IconKind.BROWSER, IconKind.CAMERA,
                IconKind.GALLERY, IconKind.MAPS, IconKind.MUSIC, IconKind.MAIL
            )
            homeApps.clear()
            preferred.forEach { kind ->
                apps.firstOrNull { it.kind == kind && homeApps.none { chosen -> chosen.packageName == it.packageName } }
                    ?.let { homeApps.add(it) }
            }
            if (homeApps.size < 4) {
                apps.filter { candidate -> homeApps.none { it.packageName == candidate.packageName } }
                    .take(4 - homeApps.size)
                    .forEach { homeApps.add(it) }
            }
            invalidate()
        }

        fun closeDrawer() {
            if (isAnimatingDrawer || !isDrawerOpen) return
            animateDrawer(1f, 0f, opening = false)
        }

        private fun openDrawer() {
            if (isAnimatingDrawer || isDrawerOpen) return
            scrollY = 0f
            animateDrawer(0f, 1f, opening = true)
        }

        private fun animateDrawer(from: Float, to: Float, opening: Boolean) {
            drawerAnimator?.cancel()
            isAnimatingDrawer = true
            drawerProgress = from
            drawerAnimator = ValueAnimator.ofFloat(from, to).apply {
                duration = 440L
                interpolator = android.view.animation.DecelerateInterpolator(1.5f)
                addUpdateListener {
                    drawerProgress = it.animatedValue as Float
                    invalidate()
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        drawerProgress = to
                        isDrawerOpen = opening
                        if (!opening) scrollY = 0f
                        isAnimatingDrawer = false
                        invalidate()
                    }
                })
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            drawHome(canvas)
            if (drawerProgress > 0f) {
                val top = height * (1f - drawerProgress)
                canvas.save()
                canvas.clipRect(0f, top, width.toFloat(), height.toFloat())
                drawDrawer(canvas)
                canvas.restore()
            }
        }

        private fun drawHome(canvas: Canvas) {
            iconRects.clear()
            canvas.drawColor(paper)
            val w = width.toFloat()
            val h = height.toFloat()
            drawPaperTexture(canvas, w, h)

            // Distant ink mountains: pale, broken silhouettes fading into rice-paper mist.
            val px = parallaxX * 13f * density
            val py = parallaxY * 9f * density
            drawRidge(canvas, w, h, 0.30f, 0.48f, Color.rgb(215, 207, 190), px * 0.18f, py * 0.18f, 0.75f)
            drawRidge(canvas, w, h, 0.38f, 0.57f, Color.rgb(190, 181, 162), px * 0.35f, py * 0.35f, 0.9f)

            // Main mountain peaks and their ink-wash shadow faces.
            drawPeak(canvas, w, h, w * 0.20f + px * 0.55f, h * 0.43f + py * 0.55f, w * 0.43f, h * 0.39f, Color.rgb(111, 108, 96))
            drawPeak(canvas, w, h, w * 0.79f + px * 0.62f, h * 0.46f + py * 0.62f, w * 0.36f, h * 0.32f, Color.rgb(132, 127, 112))
            drawPeak(canvas, w, h, w * 0.50f + px * 0.8f, h * 0.53f + py * 0.8f, w * 0.48f, h * 0.24f, Color.rgb(157, 149, 130))

            // Mist cuts through the valleys instead of forming flat graphic bands.
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(190, 242, 237, 225)
            val mist = Path().apply {
                moveTo(-w * 0.1f, h * 0.48f)
                cubicTo(w * 0.12f, h * 0.42f, w * 0.25f, h * 0.53f, w * 0.43f, h * 0.48f)
                cubicTo(w * 0.62f, h * 0.43f, w * 0.77f, h * 0.54f, w * 1.1f, h * 0.46f)
                lineTo(w * 1.1f, h * 0.60f)
                cubicTo(w * 0.78f, h * 0.65f, w * 0.62f, h * 0.56f, w * 0.40f, h * 0.62f)
                cubicTo(w * 0.18f, h * 0.66f, w * 0.04f, h * 0.57f, -w * 0.1f, h * 0.62f)
                close()
            }
            canvas.drawPath(mist, paint)

            // Thin waterfalls and a winding river lead the eye into the scene.
            drawWaterfall(canvas, w * 0.28f + px * 0.55f, h * 0.48f, h * 0.67f)
            drawWaterfall(canvas, w * 0.75f + px * 0.6f, h * 0.50f, h * 0.65f)
            paint.color = Color.argb(115, 78, 86, 82)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.2f * density
            paint.strokeCap = Paint.Cap.ROUND
            val river = Path().apply {
                moveTo(w * 0.49f + px, h * 0.61f + py)
                cubicTo(w * 0.43f + px, h * 0.69f, w * 0.62f + px, h * 0.72f, w * 0.52f, h * 0.80f)
                cubicTo(w * 0.46f, h * 0.85f, w * 0.54f, h * 0.88f, w * 0.48f, h * 0.91f)
            }
            canvas.drawPath(river, paint)
            paint.strokeWidth = 0.8f * density
            canvas.drawPath(Path().apply {
                moveTo(w * 0.53f + px, h * 0.63f + py)
                cubicTo(w * 0.50f, h * 0.70f, w * 0.67f, h * 0.75f, w * 0.57f, h * 0.81f)
            }, paint)
            paint.style = Paint.Style.FILL

            // Foreground ink rocks frame the scene without filling the central negative space.
            drawForegroundRock(canvas, w * 0.03f, h * 0.77f, w * 0.39f, h * 0.16f, true)
            drawForegroundRock(canvas, w * 0.98f, h * 0.75f, w * 0.36f, h * 0.19f, false)
            drawPine(canvas, w * 0.12f + px, h * 0.70f, 0.92f)
            drawPine(canvas, w * 0.88f + px, h * 0.68f, 0.78f)
            drawPavilion(canvas, w * 0.67f + px * 0.45f, h * 0.57f, 0.72f)

            // Small calligraphic title and vermilion seal.
            paint.color = ink
            paint.textAlign = Paint.Align.RIGHT
            paint.typeface = Typeface.create("casual", Typeface.BOLD)
            paint.textSize = 39f * scaledDensity
            canvas.drawText("山水", w * 0.89f, h * 0.16f, paint)
            paint.color = sealRed
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.5f * density
            val seal = RectF(w * 0.80f, h * 0.175f, w * 0.91f, h * 0.215f)
            canvas.drawRect(seal, paint)
            paint.style = Paint.Style.FILL
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = Typeface.create("serif", Typeface.BOLD)
            paint.textSize = 8f * scaledDensity
            canvas.drawText("山", seal.centerX(), seal.centerY() + 3f * density, paint)

            drawHomeDock(canvas, w, h)
        }

        private fun drawRidge(canvas: Canvas, w: Float, h: Float, leftY: Float, rightY: Float, color: Int, ox: Float, oy: Float, scale: Float) {
            val p = Path().apply {
                moveTo(-w * 0.1f + ox, h * leftY + oy)
                lineTo(w * 0.10f + ox, h * (leftY - 0.12f * scale) + oy)
                lineTo(w * 0.19f + ox, h * (leftY - 0.04f * scale) + oy)
                lineTo(w * 0.34f + ox, h * (leftY - 0.19f * scale) + oy)
                lineTo(w * 0.48f + ox, h * (leftY - 0.02f * scale) + oy)
                lineTo(w * 0.65f + ox, h * (rightY - 0.13f * scale) + oy)
                lineTo(w * 0.80f + ox, h * (rightY - 0.03f * scale) + oy)
                lineTo(w * 1.1f + ox, h * rightY + oy)
                lineTo(w * 1.1f, h * 0.72f)
                lineTo(-w * 0.1f, h * 0.72f)
                close()
            }
            paint.color = color
            paint.style = Paint.Style.FILL
            canvas.drawPath(p, paint)
            paint.color = Color.argb(48, 48, 45, 38)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 0.9f * density
            canvas.drawPath(p, paint)
            paint.style = Paint.Style.FILL
        }

        private fun drawPeak(canvas: Canvas, w: Float, h: Float, cx: Float, peakY: Float, halfWidth: Float, depth: Float, color: Int) {
            val baseY = peakY + depth
            val silhouette = Path().apply {
                moveTo(cx - halfWidth, baseY)
                lineTo(cx - halfWidth * 0.64f, peakY + depth * 0.43f)
                lineTo(cx - halfWidth * 0.39f, peakY + depth * 0.20f)
                lineTo(cx - halfWidth * 0.22f, peakY + depth * 0.28f)
                lineTo(cx, peakY)
                lineTo(cx + halfWidth * 0.13f, peakY + depth * 0.13f)
                lineTo(cx + halfWidth * 0.30f, peakY + depth * 0.08f)
                lineTo(cx + halfWidth * 0.47f, peakY + depth * 0.36f)
                lineTo(cx + halfWidth, baseY)
                close()
            }
            paint.color = color
            paint.style = Paint.Style.FILL
            canvas.drawPath(silhouette, paint)

            // Dry-brush creases on the mountain face.
            paint.color = Color.argb(95, 38, 38, 34)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.15f * density
            paint.strokeCap = Paint.Cap.ROUND
            for (i in 0..4) {
                val x = cx - halfWidth * 0.18f + i * halfWidth * 0.10f
                canvas.drawLine(x, peakY + depth * (0.20f + i * 0.025f), x + halfWidth * 0.22f, peakY + depth * (0.50f + i * 0.035f), paint)
            }
            paint.style = Paint.Style.FILL
        }

        private fun drawWaterfall(canvas: Canvas, x: Float, top: Float, bottom: Float) {
            paint.color = Color.argb(155, 247, 243, 232)
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = 4f * density
            canvas.drawLine(x, top, x + 2f * density, bottom, paint)
            paint.color = Color.argb(100, 91, 91, 82)
            paint.strokeWidth = 0.8f * density
            canvas.drawLine(x + 3f * density, top + 4f * density, x + 4f * density, bottom - 5f * density, paint)
            paint.style = Paint.Style.FILL
        }

        private fun drawForegroundRock(canvas: Canvas, x: Float, y: Float, rw: Float, rh: Float, left: Boolean) {
            val sign = if (left) 1f else -1f
            val rock = Path().apply {
                moveTo(x - rw * 0.5f, y + rh)
                cubicTo(x - rw * 0.40f, y + rh * 0.22f, x - rw * 0.16f, y - rh * 0.25f, x + rw * 0.02f * sign, y)
                cubicTo(x + rw * 0.30f * sign, y - rh * 0.12f, x + rw * 0.43f * sign, y + rh * 0.46f, x + rw * 0.55f * sign, y + rh)
                close()
            }
            paint.color = Color.rgb(76, 75, 66)
            paint.style = Paint.Style.FILL
            canvas.drawPath(rock, paint)
            paint.color = Color.argb(110, 218, 208, 187)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f * density
            canvas.drawLine(x - rw * 0.20f, y + rh * 0.48f, x + rw * 0.03f * sign, y + rh * 0.12f, paint)
            canvas.drawLine(x + rw * 0.03f * sign, y + rh * 0.12f, x + rw * 0.24f * sign, y + rh * 0.53f, paint)
            paint.style = Paint.Style.FILL
        }

        private fun drawPine(canvas: Canvas, x: Float, baseY: Float, scale: Float) {
            canvas.save()
            canvas.translate(x, baseY)
            canvas.scale(scale * density, scale * density)
            paint.color = Color.rgb(48, 49, 43)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.1f
            paint.strokeCap = Paint.Cap.ROUND
            val trunk = Path().apply { moveTo(0f, 0f); cubicTo(-3f, -18f, 3f, -34f, -2f, -58f) }
            canvas.drawPath(trunk, paint)
            for (i in 0..5) {
                val yy = -12f - i * 8f
                val span = 15f - i * 1.7f
                canvas.drawLine(-2f, yy, -span, yy - 7f, paint)
                canvas.drawLine(-2f, yy, span, yy - 9f, paint)
                canvas.drawLine(-span, yy - 7f, -span - 4f, yy - 10f, paint)
                canvas.drawLine(span, yy - 9f, span + 4f, yy - 12f, paint)
            }
            paint.style = Paint.Style.FILL
            canvas.restore()
        }

        private fun drawPavilion(canvas: Canvas, x: Float, y: Float, scale: Float) {
            canvas.save()
            canvas.translate(x, y)
            canvas.scale(scale * density, scale * density)
            paint.color = Color.rgb(55, 53, 46)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.8f
            paint.strokeCap = Paint.Cap.ROUND
            val roof = Path().apply {
                moveTo(-19f, -8f); lineTo(-11f, -13f); lineTo(-6f, -19f)
                lineTo(0f, -13f); lineTo(11f, -13f); lineTo(19f, -8f)
                moveTo(-19f, -8f); quadTo(0f, -4f, 19f, -8f)
            }
            canvas.drawPath(roof, paint)
            canvas.drawLine(-13f, -7f, -13f, 9f, paint)
            canvas.drawLine(13f, -7f, 13f, 9f, paint)
            canvas.drawLine(-13f, 9f, 13f, 9f, paint)
            paint.style = Paint.Style.FILL
            canvas.restore()
        }

        private fun drawPaperTexture(canvas: Canvas, w: Float, h: Float) {
            paint.color = Color.argb(13, 105, 91, 66)
            paint.strokeWidth = 0.7f * density
            var y = 0f
            while (y < h) {
                canvas.drawLine(0f, y, w, y + 0.35f * density, paint)
                y += 7f * density
            }
        }

        private fun drawMountainLayer(canvas: Canvas, w: Float, h: Float, base: Float, color: Int, offsetX: Float, offsetY: Float) {
            val path = Path()
            path.moveTo(-w * 0.08f + offsetX, h * (base + 0.12f) + offsetY)
            path.cubicTo(w * 0.08f + offsetX, h * (base - 0.08f) + offsetY, w * 0.18f + offsetX, h * (base - 0.03f) + offsetY, w * 0.31f + offsetX, h * (base + 0.04f) + offsetY)
            path.cubicTo(w * 0.43f + offsetX, h * (base - 0.12f) + offsetY, w * 0.52f + offsetX, h * (base - 0.10f) + offsetY, w * 0.66f + offsetX, h * (base + 0.04f) + offsetY)
            path.cubicTo(w * 0.79f + offsetX, h * (base - 0.02f) + offsetY, w * 0.91f + offsetX, h * (base - 0.05f) + offsetY, w * 1.08f + offsetX, h * (base + 0.10f) + offsetY)
            path.lineTo(w * 1.08f, h * 0.78f)
            path.lineTo(-w * 0.08f, h * 0.78f)
            path.close()
            paint.color = color
            paint.style = Paint.Style.FILL
            canvas.drawPath(path, paint)

            paint.color = Color.argb(55, 35, 34, 30)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.1f * density
            canvas.drawPath(path, paint)
            paint.style = Paint.Style.FILL
        }

        private fun drawHomeDock(canvas: Canvas, w: Float, h: Float) {
            val count = homeApps.size
            if (count == 0) return
            val iconSize = min(w * 0.105f, 42f * density)
            val spacing = w / (count + 1)
            val centerY = h * 0.86f
            homeApps.forEachIndexed { index, app ->
                val cx = spacing * (index + 1)
                drawInkIcon(canvas, app.kind, app.icon, cx, centerY - 5f * density, iconSize)
                paint.color = ink
                paint.textAlign = Paint.Align.CENTER
                paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                paint.textSize = 9f * scaledDensity
                val label = if (app.label.length > 10) app.label.take(9) + "…" else app.label
                canvas.drawText(label, cx, centerY + iconSize * 0.82f, paint)
                iconRects.add(Pair(RectF(cx - iconSize, centerY - iconSize, cx + iconSize, centerY + iconSize), app))
            }
            paint.color = fadedInk
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = 10f * scaledDensity
            canvas.drawText("↑", w / 2f, h * 0.965f, paint)
        }

        private fun drawDrawer(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            canvas.drawColor(paper)
            drawPaperTexture(canvas, w, h)

            paint.color = ink
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = Typeface.create("casual", Typeface.BOLD)
            paint.textSize = 25f * scaledDensity
            canvas.drawText("应用", w / 2f, h * 0.105f, paint)
            paint.color = fadedInk
            paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            paint.textSize = 10f * scaledDensity
            canvas.drawText("A P P S", w / 2f, h * 0.145f, paint)

            iconRects.clear()
            val columns = 4
            val horizontalPadding = w * 0.055f
            val cellWidth = (w - horizontalPadding * 2f) / columns
            val iconSize = min(cellWidth * 0.49f, 48f * density)
            val top = h * 0.235f
            val rowHeight = max(iconSize + 43f * density, h * 0.125f)
            val contentHeight = top + ((apps.size + columns - 1) / columns) * rowHeight + h * 0.04f
            maxScroll = max(0f, contentHeight - h)
            scrollY = scrollY.coerceIn(0f, maxScroll)

            canvas.save()
            canvas.clipRect(0f, h * 0.19f, w, h)
            canvas.translate(0f, -scrollY)
            apps.forEachIndexed { index, app ->
                val row = index / columns
                val col = index % columns
                val centerX = horizontalPadding + cellWidth * (col + 0.5f)
                val centerY = top + row * rowHeight
                drawInkIcon(canvas, app.kind, app.icon, centerX, centerY, iconSize)
                iconRects.add(Pair(RectF(centerX - cellWidth * 0.45f, centerY - iconSize * 0.62f, centerX + cellWidth * 0.45f, centerY + iconSize + 34f * density), app))

                paint.color = ink
                paint.textAlign = Paint.Align.CENTER
                paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                paint.textSize = 10f * scaledDensity
                val label = if (app.label.length > 13) app.label.take(12) + "…" else app.label
                canvas.drawText(label, centerX, centerY + iconSize * 0.82f + 16f * density, paint)
            }
            canvas.restore()

            if (apps.isEmpty()) {
                paint.color = fadedInk
                paint.textAlign = Paint.Align.CENTER
                paint.textSize = 14f * scaledDensity
                canvas.drawText("No launchable apps found", w / 2f, h * 0.5f, paint)
            }
        }

        private fun drawInkIcon(canvas: Canvas, kind: IconKind?, fallback: Drawable, cx: Float, cy: Float, size: Float) {
            if (kind == null) {
                fallback.setBounds((cx - size * 0.43f).toInt(), (cy - size * 0.43f).toInt(), (cx + size * 0.43f).toInt(), (cy + size * 0.43f).toInt())
                fallback.draw(canvas)
                return
            }

            canvas.save()
            canvas.translate(cx, cy)
            canvas.scale(size / 48f, size / 48f)
            paint.color = ink
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.15f
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND

            when (kind) {
                IconKind.PHONE -> {
                    val p = Path().apply { moveTo(-12f, -17f); cubicTo(-17f, -12f, -9f, 5f, 8f, 15f); cubicTo(12f, 17f, 17f, 10f, 14f, 7f); lineTo(7f, 1f); lineTo(1f, 6f); cubicTo(-3f, 4f, -6f, 0f, -7f, -4f); lineTo(-2f, -9f); lineTo(-8f, -16f); close() }
                    canvas.drawPath(p, paint)
                }
                IconKind.MESSAGES -> {
                    canvas.drawRoundRect(RectF(-18f, -14f, 18f, 11f), 7f, 7f, paint)
                    val p = Path().apply { moveTo(-8f, 11f); lineTo(-13f, 18f); lineTo(0f, 11f) }
                    canvas.drawPath(p, paint)
                    for (x in -8..8 step 8) canvas.drawCircle(x.toFloat(), -1f, 1.2f, paint)
                }
                IconKind.BROWSER -> {
                    canvas.drawCircle(0f, 0f, 17f, paint)
                    canvas.drawOval(RectF(-8f, -17f, 8f, 17f), paint)
                    canvas.drawLine(-16f, -6f, 16f, -6f, paint)
                    canvas.drawLine(-16f, 6f, 16f, 6f, paint)
                }
                IconKind.CAMERA -> {
                    canvas.drawRoundRect(RectF(-18f, -12f, 18f, 14f), 4f, 4f, paint)
                    canvas.drawCircle(0f, 1f, 7f, paint)
                    canvas.drawRoundRect(RectF(-8f, -17f, 5f, -12f), 2f, 2f, paint)
                }
                IconKind.GALLERY -> {
                    canvas.drawRoundRect(RectF(-18f, -15f, 18f, 15f), 3f, 3f, paint)
                    canvas.drawCircle(9f, -7f, 2.5f, paint)
                    val p = Path().apply { moveTo(-14f, 10f); lineTo(-5f, 0f); lineTo(1f, 7f); lineTo(8f, -1f); lineTo(15f, 10f) }
                    canvas.drawPath(p, paint)
                }
                IconKind.SETTINGS -> {
                    canvas.drawCircle(0f, 0f, 7f, paint)
                    for (i in 0 until 8) {
                        val a = i * Math.PI / 4.0
                        val x1 = kotlin.math.cos(a).toFloat() * 11f
                        val y1 = kotlin.math.sin(a).toFloat() * 11f
                        val x2 = kotlin.math.cos(a).toFloat() * 17f
                        val y2 = kotlin.math.sin(a).toFloat() * 17f
                        canvas.drawLine(x1, y1, x2, y2, paint)
                    }
                }
                IconKind.MUSIC -> {
                    val p = Path().apply { moveTo(5f, -16f); lineTo(5f, 9f); cubicTo(5f, 17f, -12f, 19f, -12f, 10f); cubicTo(-12f, 4f, -3f, 1f, 5f, 4f); moveTo(5f, -16f); lineTo(16f, -13f); lineTo(16f, -7f); lineTo(5f, -10f) }
                    canvas.drawPath(p, paint)
                }
                IconKind.MAPS -> {
                    val p = Path().apply { moveTo(-15f, -12f); lineTo(-4f, -16f); lineTo(7f, -11f); lineTo(16f, -15f); lineTo(16f, 12f); lineTo(7f, 16f); lineTo(-4f, 11f); lineTo(-15f, 15f); close() }
                    canvas.drawPath(p, paint)
                    canvas.drawCircle(1f, -1f, 5f, paint)
                }
                IconKind.MAIL -> {
                    canvas.drawRoundRect(RectF(-18f, -13f, 18f, 13f), 3f, 3f, paint)
                    val p = Path().apply { moveTo(-17f, -11f); lineTo(0f, 2f); lineTo(17f, -11f) }
                    canvas.drawPath(p, paint)
                }
                IconKind.CALENDAR -> {
                    canvas.drawRoundRect(RectF(-15f, -15f, 15f, 16f), 3f, 3f, paint)
                    canvas.drawLine(-15f, -6f, 15f, -6f, paint)
                    canvas.drawLine(-8f, -19f, -8f, -11f, paint)
                    canvas.drawLine(8f, -19f, 8f, -11f, paint)
                }
                IconKind.CONTACTS -> {
                    canvas.drawCircle(0f, -7f, 6f, paint)
                    canvas.drawArc(RectF(-14f, 1f, 14f, 20f), 180f, 180f, false, paint)
                }
                IconKind.FILES -> {
                    val p = Path().apply { moveTo(-17f, -12f); lineTo(-4f, -12f); lineTo(1f, -6f); lineTo(17f, -6f); lineTo(15f, 15f); lineTo(-16f, 15f); close() }
                    canvas.drawPath(p, paint)
                }
                IconKind.VIDEO -> {
                    canvas.drawRoundRect(RectF(-17f, -12f, 10f, 12f), 3f, 3f, paint)
                    val p = Path().apply { moveTo(10f, -6f); lineTo(18f, -11f); lineTo(18f, 11f); lineTo(10f, 6f); close() }
                    canvas.drawPath(p, paint)
                }
                IconKind.WEATHER -> {
                    canvas.drawCircle(-4f, -5f, 7f, paint)
                    canvas.drawArc(RectF(-13f, -2f, 18f, 17f), 180f, 180f, false, paint)
                }
                IconKind.GENERIC -> canvas.drawCircle(0f, 0f, 15f, paint)
            }

            // Small vermilion seal accent, shared across the custom icon family.
            paint.color = sealRed
            paint.style = Paint.Style.FILL
            canvas.drawCircle(14f, 14f, 2.1f, paint)
            canvas.restore()
            paint.style = Paint.Style.FILL
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    lastY = event.y
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = event.y - lastY
                    if (isDrawerOpen && !isAnimatingDrawer && downY > height * 0.19f) {
                        scrollY = (scrollY - dy).coerceIn(0f, maxScroll)
                        invalidate()
                    }
                    lastY = event.y
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (isAnimatingDrawer) return true
                    if (abs(dy) > 65f && abs(dy) > abs(dx) * 1.2f) {
                        if (!isDrawerOpen && dy < 0f) openDrawer()
                        else if (isDrawerOpen && dy > 0f && (scrollY <= 0f || downY < height * 0.19f)) closeDrawer()
                        return true
                    }

                    if (abs(dx) < 20f && abs(dy) < 20f) {
                        val hit = iconRects.firstOrNull { it.first.contains(event.x, event.y + if (isDrawerOpen) scrollY else 0f) }
                        hit?.second?.let { app ->
                            val launchIntent = packageManager.getLaunchIntentForPackage(app.packageName)
                            if (launchIntent != null) startActivity(launchIntent)
                        }
                    }
                    performClick()
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
}
