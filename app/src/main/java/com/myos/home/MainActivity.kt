package com.myos.home

import android.app.Activity
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity() {
    private lateinit var launcherView: LauncherView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = android.graphics.Color.WHITE
        window.navigationBarColor = android.graphics.Color.WHITE
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
        if (::launcherView.isInitialized && launcherView.isDrawerOpen) {
            launcherView.closeDrawer()
        } else {
            super.onBackPressed()
        }
    }

    private data class LaunchableApp(
        val label: String,
        val icon: Drawable,
        val packageName: String,
        val activityName: String
    )

    private inner class LauncherView : View(this@MainActivity) {
        var isDrawerOpen = false
            private set

        private val white = android.graphics.Color.rgb(250, 250, 248)
        private val ocean = android.graphics.Color.rgb(18, 105, 145)
        private val deepOcean = android.graphics.Color.rgb(8, 62, 91)
        private val ink = android.graphics.Color.rgb(28, 43, 49)
        private val muted = android.graphics.Color.rgb(105, 120, 125)

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val apps = mutableListOf<LaunchableApp>()
        private var downX = 0f
        private var downY = 0f
        private var lastY = 0f
        private var scrollY = 0f
        private var maxScroll = 0f
        private var downTime = 0L
        private val iconRects = mutableListOf<Pair<RectF, LaunchableApp>>()
        private val wavePath = Path()
        private val oceanPhoto: Bitmap? = loadOceanPhoto()

        private fun loadOceanPhoto(): Bitmap? = try {
            assets.open("myos_ocean.jpg").use { BitmapFactory.decodeStream(it) }
        } catch (_: Exception) {
            null
        }

        init {
            isFocusable = true
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
                            info.loadIcon(packageManager),
                            info.activityInfo.packageName,
                            info.activityInfo.name
                        )
                    )
                }
            invalidate()
        }

        fun closeDrawer() {
            isDrawerOpen = false
            scrollY = 0f
            invalidate()
        }

        private fun openDrawer() {
            isDrawerOpen = true
            scrollY = 0f
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (isDrawerOpen) drawDrawer(canvas) else drawHome(canvas)
        }

        private fun drawHome(canvas: Canvas) {
            canvas.drawColor(white)
            val w = width.toFloat()
            val h = height.toFloat()

            val photo = oceanPhoto
            if (photo != null) {
                // Center-crop the original landscape reference to fill the portrait launcher.
                val scale = max(w / photo.width, h / photo.height)
                val drawW = photo.width * scale
                val drawH = photo.height * scale
                val left = (w - drawW) / 2f
                val top = (h - drawH) / 2f
                paint.alpha = 255
                canvas.drawBitmap(photo, null, RectF(left, top, left + drawW, top + drawH), paint)
                // Keep the gesture hint subtle and readable over the turquoise water.
                paint.color = android.graphics.Color.WHITE
                paint.setShadowLayer(4f, 0f, 1f, android.graphics.Color.argb(110, 0, 45, 55))
                paint.textAlign = Paint.Align.CENTER
                paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                paint.textSize = 14f * resources.displayMetrics.scaledDensity
                canvas.drawText("Swipe up to open", w / 2f, h * 0.91f, paint)
                paint.clearShadowLayer()
                return
            }

            // Temporary vector fallback remains available until the photo asset is bundled.
            val horizon = h * 0.53f
            wavePath.reset()
            wavePath.moveTo(0f, horizon + h * 0.025f)
            wavePath.cubicTo(w * 0.25f, horizon - h * 0.055f, w * 0.53f, horizon + h * 0.09f, w, horizon - h * 0.015f)
            wavePath.lineTo(w, h)
            wavePath.lineTo(0f, h)
            wavePath.close()

            paint.color = ocean
            canvas.drawPath(wavePath, paint)

            paint.color = deepOcean
            val lowerWave = Path().apply {
                moveTo(0f, horizon + h * 0.12f)
                cubicTo(w * 0.32f, horizon + h * 0.04f, w * 0.7f, horizon + h * 0.19f, w, horizon + h * 0.08f)
                lineTo(w, h)
                lineTo(0f, h)
                close()
            }
            canvas.drawPath(lowerWave, paint)

            paint.color = ink
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            paint.textSize = 14f * resources.displayMetrics.scaledDensity
            canvas.drawText("Swipe up to open", w / 2f, h * 0.91f, paint)
        }

        private fun drawDrawer(canvas: Canvas) {
            canvas.drawColor(deepOcean)
            val w = width.toFloat()
            val h = height.toFloat()
            paint.color = ocean
            canvas.drawRect(0f, 0f, w, h * 0.22f, paint)

            paint.color = android.graphics.Color.WHITE
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            paint.textSize = 20f * resources.displayMetrics.scaledDensity
            canvas.drawText("Your apps", w / 2f, h * 0.105f, paint)

            iconRects.clear()
            val columns = 4
            val horizontalPadding = w * 0.07f
            val cellWidth = (w - horizontalPadding * 2f) / columns
            val iconSize = min(cellWidth * 0.54f, 58f * resources.displayMetrics.density)
            val top = h * 0.27f
            val rowHeight = max(iconSize + 44f * resources.displayMetrics.density, h * 0.13f)
            val contentHeight = top + ((apps.size + columns - 1) / columns) * rowHeight + h * 0.05f
            maxScroll = max(0f, contentHeight - h)
            scrollY = scrollY.coerceIn(0f, maxScroll)

            canvas.save()
            canvas.clipRect(0f, h * 0.2f, w, h)
            canvas.translate(0f, -scrollY)

            apps.forEachIndexed { index, app ->
                val row = index / columns
                val col = index % columns
                val stagger = if (row % 2 == 0) 0f else cellWidth * 0.08f
                val centerX = horizontalPadding + cellWidth * (col + 0.5f) + stagger
                val centerY = top + row * rowHeight
                val iconTop = centerY - iconSize / 2f
                val rect = RectF(centerX - iconSize / 2f, iconTop, centerX + iconSize / 2f, iconTop + iconSize)
                app.icon.setBounds(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt())
                app.icon.draw(canvas)
                iconRects.add(Pair(RectF(rect.left - 12f, rect.top - 12f, rect.right + 12f, rect.bottom + 42f), app))

                paint.color = android.graphics.Color.WHITE
                paint.textAlign = Paint.Align.CENTER
                paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                paint.textSize = 10f * resources.displayMetrics.scaledDensity
                val label = if (app.label.length > 13) app.label.take(12) + "…" else app.label
                canvas.drawText(label, centerX, rect.bottom + 22f * resources.displayMetrics.density, paint)
            }
            canvas.restore()

            if (apps.isEmpty()) {
                paint.color = android.graphics.Color.WHITE
                paint.textAlign = Paint.Align.CENTER
                paint.textSize = 14f * resources.displayMetrics.scaledDensity
                canvas.drawText("No launchable apps found", w / 2f, h * 0.5f, paint)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    lastY = event.y
                    downTime = System.currentTimeMillis()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = event.y - lastY
                    if (isDrawerOpen && downY > height * 0.2f) {
                        scrollY = (scrollY - dy).coerceIn(0f, maxScroll)
                        invalidate()
                    }
                    lastY = event.y
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (abs(dy) > 70f && abs(dy) > abs(dx) * 1.2f) {
                        if (!isDrawerOpen && dy < 0f) openDrawer()
                        else if (isDrawerOpen && dy > 0f && (scrollY <= 0f || downY < height * 0.2f)) closeDrawer()
                        return true
                    }

                    if (isDrawerOpen && abs(dx) < 18f && abs(dy) < 18f) {
                        val hit = iconRects.firstOrNull { it.first.contains(event.x, event.y + scrollY) }
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
