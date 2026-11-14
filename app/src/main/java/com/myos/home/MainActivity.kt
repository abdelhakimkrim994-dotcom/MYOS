package com.myos.home

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
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

        private val background = android.graphics.Color.rgb(248, 247, 243)
        private val ink = android.graphics.Color.rgb(38, 43, 45)
        private val muted = android.graphics.Color.rgb(112, 117, 116)

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val apps = mutableListOf<LaunchableApp>()
        private var downX = 0f
        private var downY = 0f
        private var lastY = 0f
        private var scrollY = 0f
        private var maxScroll = 0f
        private var downTime = 0L
        private val iconRects = mutableListOf<Pair<RectF, LaunchableApp>>()
        private var drawerProgress = 0f
        private var drawerAnimator: ValueAnimator? = null
        private var isAnimatingDrawer = false

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
                duration = 560L
                interpolator = android.view.animation.DecelerateInterpolator(1.6f)
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
            canvas.drawColor(background)
            val w = width.toFloat()
            val h = height.toFloat()
            paint.color = muted
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            paint.textSize = 13f * resources.displayMetrics.scaledDensity
            canvas.drawText("Swipe up to open", w / 2f, h * 0.92f, paint)
        }

        private fun drawDrawer(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            canvas.drawColor(background)

            paint.color = ink
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            paint.textSize = 18f * resources.displayMetrics.scaledDensity
            canvas.drawText("Apps", w / 2f, h * 0.105f, paint)

            iconRects.clear()
            val columns = 4
            val horizontalPadding = w * 0.07f
            val cellWidth = (w - horizontalPadding * 2f) / columns
            val iconSize = min(cellWidth * 0.54f, 58f * resources.displayMetrics.density)
            val top = h * 0.23f
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

                paint.color = ink
                paint.textAlign = Paint.Align.CENTER
                paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                paint.textSize = 10f * resources.displayMetrics.scaledDensity
                val label = if (app.label.length > 13) app.label.take(12) + "…" else app.label
                canvas.drawText(label, centerX, rect.bottom + 22f * resources.displayMetrics.density, paint)
            }
            canvas.restore()

            if (apps.isEmpty()) {
                paint.color = muted
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
                    if (isDrawerOpen && !isAnimatingDrawer && downY > height * 0.2f) {
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
