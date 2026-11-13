package com.myos.home

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.WindowManager
import android.view.animation.PathInterpolator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

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
        if (::launcherView.isInitialized) launcherView.reloadApps()
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
    )

    private inner class LauncherView : View(this@MainActivity) {
        var drawerProgress = 0f
            private set
        val isDrawerOpen: Boolean get() = drawerProgress >= 0.999f

        private val density = resources.displayMetrics.density
        private val scaledDensity = resources.displayMetrics.scaledDensity
        private val ivory = Color.rgb(250, 248, 243)
        private val white = Color.WHITE
        private val apps = mutableListOf<LaunchableApp>()

        private val photo: Bitmap? = loadOceanPhoto()
        private val photoRect = RectF()
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
        private var homeWaterline = 0f
        private var fadeShader: LinearGradient? = null
        private var tintShader: LinearGradient? = null
        private var animator: ValueAnimator? = null
        private var downX = 0f
        private var downY = 0f
        private var lastY = 0f
        private var downProgress = 0f
        private var scrollY = 0f
        private var maxScroll = 0f
        private var velocityTracker: VelocityTracker? = null
        private var gestureMode = GestureMode.NONE
        private var isAnimating = false
        private var hitRects = ArrayList<HitRect>()
        private val tempOval = RectF()
        private val columns = 4

        private enum class GestureMode { NONE, OPENING, CLOSING, SCROLLING }

        private data class HitRect(val bounds: RectF, val app: LaunchableApp)

        private fun loadOceanPhoto(): Bitmap? = try {
            assets.open("myos_ocean.jpg").use { BitmapFactory.decodeStream(it) }
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
            val fadeBottom = homeWaterline + 48f * density
            fadeShader = LinearGradient(
                0f, homeWaterline, 0f, fadeBottom,
                ivory, Color.TRANSPARENT, Shader.TileMode.CLAMP
            )
            homeFadePaint.shader = fadeShader
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
            if (drawerProgress <= 0f || isAnimating) return
            settleDrawer(false)
        }

        private fun settleDrawer(open: Boolean) {
            animator?.cancel()
            isAnimating = true
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            val target = if (open) 1f else 0f
            val start = drawerProgress
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
            canvas.drawColor(ivory)
            if (photo != null) {
                canvas.save()
                canvas.clipRect(0f, homeWaterline, width.toFloat(), height.toFloat())
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
                canvas.drawText(text, i, i + 1, x, baseline, hintPaint)
                x = next + letterSpacing
            }
        }

        private fun drawWater(canvas: Canvas) {
            canvas.save()
            canvas.clipPath(wave.fill)
            if (photo == null) {
                canvas.drawColor(Color.rgb(14, 124, 140))
            } else {
                drawPhoto(canvas)
                tintPaint.alpha = (115f * drawerProgress).toInt().coerceIn(0, 115)
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), tintPaint)
            }
            canvas.restore()

            canvas.save()
            canvas.translate(0f, 6f * density)
            canvas.drawPath(wave.edge, softFoamPaint)
            canvas.restore()
            canvas.drawPath(wave.edge, foamPaint)
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
                val columnDelay = col * 12f * density
                val reveal = ((centerY - waterY - columnDelay) / (120f * density)).coerceIn(0f, 1f)
                if (reveal <= 0f) continue

                val eased = reveal * reveal * (3f - 2f * reveal)
                val alpha = (255f * eased).toInt()
                val scale = 0.92f + 0.08f * eased
                val translateY = (1f - eased) * 24f * density
                val cy = centerY + translateY
                val half = iconSize * scale / 2f

                tempOval.set(centerX - 18f * density, cy + half + 5f * density,
                    centerX + 18f * density, cy + half + 11f * density)
                iconShadowPaint.alpha = (26f * eased).toInt()
                canvas.drawOval(tempOval, iconShadowPaint)

                val app = apps[index]
                val save = canvas.save()
                canvas.translate(centerX, cy)
                canvas.scale(scale, scale)
                app.icon.alpha = alpha
                app.icon.setBounds((-iconSize / 2f).toInt(), (-iconSize / 2f).toInt(),
                    (iconSize / 2f).toInt(), (iconSize / 2f).toInt())
                app.icon.draw(canvas)
                canvas.restoreToCount(save)
                app.icon.alpha = 255

                labelPaint.alpha = alpha
                canvas.drawText(app.displayLabel, centerX, cy + half + labelOffset, labelPaint)

                val hitTop = cy - half - 8f * density
                val hitBottom = cy + half + 34f * density
                hitRects[index].bounds.set(
                    centerX - cellWidth * 0.46f, hitTop,
                    centerX + cellWidth * 0.46f, hitBottom
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
            val waterY = h - drawerProgress * h * 1.08f
            val amplitude = 28f * density * sin(PI.toFloat() * drawerProgress)
            wave.build(w, h, waterY, amplitude, drawerProgress)
            if (drawerProgress < 1f) drawHome(canvas)
            drawWater(canvas)
            drawApps(canvas)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    animator?.cancel()
                    isAnimating = false
                    setLayerType(View.LAYER_TYPE_NONE, null)
                    downX = event.x
                    downY = event.y
                    lastY = event.y
                    downProgress = drawerProgress
                    velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
                    gestureMode = when {
                        drawerProgress <= 0f -> GestureMode.OPENING
                        drawerProgress >= 0.999f -> GestureMode.SCROLLING
                        else -> GestureMode.CLOSING
                    }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    velocityTracker?.addMovement(event)
                    val dy = event.y - downY
                    when (gestureMode) {
                        GestureMode.OPENING -> {
                            drawerProgress = (downProgress - dy / (height * 0.70f)).coerceIn(0f, 1f)
                            invalidate()
                        }
                        GestureMode.CLOSING -> {
                            if (dy > 0f) {
                                drawerProgress = (downProgress - dy / (height * 0.70f)).coerceIn(0f, 1f)
                                invalidate()
                            } else if (drawerProgress >= 0.999f && scrollY > 0f) {
                                gestureMode = GestureMode.SCROLLING
                            }
                        }
                        GestureMode.SCROLLING -> {
                            val delta = event.y - lastY
                            if (scrollY <= 0f && delta > 0f) {
                                gestureMode = GestureMode.CLOSING
                                drawerProgress = (1f - (event.y - downY) / (height * 0.70f)).coerceIn(0f, 1f)
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

        fun build(w: Float, h: Float, waterY: Float, amp: Float, phase: Float) {
            val shift = phase * 0.06f * w
            edge.rewind()
            var previousX = xs[0] * w + shift
            var previousY = pointY(0, waterY, amp, phase)
            edge.moveTo(previousX, previousY)
            for (i in 1 until xs.size) {
                val x = xs[i] * w + shift
                val y = pointY(i, waterY, amp, phase)
                val dx = x - previousX
                edge.cubicTo(
                    previousX + dx * 0.45f, previousY,
                    x - dx * 0.45f, y,
                    x, y
                )
                previousX = x
                previousY = y
            }
            fill.set(edge)
            fill.lineTo(previousX, h)
            fill.lineTo(xs[0] * w + shift, h)
            fill.close()
        }

        private fun pointY(index: Int, baseline: Float, amplitude: Float, phase: Float): Float {
            val wobble = 1f + 0.18f * sin(phase * 2f * PI.toFloat() + index * 1.7f)
            return baseline + ys[index] * amplitude * wobble
        }
    }
}
