package com.freedom.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator

/**
 * Draws the exact path submitted to AccessibilityService.dispatchGesture(), or marks
 * the bounds of a node activated through ACTION_CLICK. The window never receives touch.
 */
object GestureTrailOverlay {
    private const val SWIPE_HOLD_AFTER_COMPLETE_MS = 650L
    private const val CANCEL_HOLD_MS = 250L
    private const val CLICK_VISIBLE_MS = 760L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var trailView: TrailView? = null
    private var attached = false

    private val removeRunnable = Runnable { removeOnMainThread() }

    fun showSwipe(context: Context, path: Path, durationMs: Long) {
        runOnMain {
            val view = ensureAttached(context) ?: return@runOnMain
            mainHandler.removeCallbacks(removeRunnable)
            view.showSwipe(path, durationMs.coerceAtLeast(1L))
        }
    }

    fun finishSwipe() {
        runOnMain {
            val view = trailView ?: return@runOnMain
            view.finishSwipe()
            mainHandler.removeCallbacks(removeRunnable)
            mainHandler.postDelayed(removeRunnable, SWIPE_HOLD_AFTER_COMPLETE_MS)
        }
    }

    fun cancelSwipe() {
        runOnMain {
            val view = trailView ?: return@runOnMain
            view.stopAnimation()
            mainHandler.removeCallbacks(removeRunnable)
            mainHandler.postDelayed(removeRunnable, CANCEL_HOLD_MS)
        }
    }

    /** Shows the actual accessibility node bounds and its center, not a fabricated tap coordinate. */
    fun showClick(context: Context, screenBounds: Rect) {
        if (screenBounds.isEmpty) return
        runOnMain {
            val view = ensureAttached(context) ?: return@runOnMain
            mainHandler.removeCallbacks(removeRunnable)
            view.showClick(Rect(screenBounds))
            mainHandler.postDelayed(removeRunnable, CLICK_VISIBLE_MS)
        }
    }

    fun clear() {
        runOnMain {
            mainHandler.removeCallbacks(removeRunnable)
            removeOnMainThread()
        }
    }

    private fun ensureAttached(context: Context): TrailView? {
        if (attached && trailView != null) return trailView
        val appContext = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !android.provider.Settings.canDrawOverlays(appContext)
        ) return null

        val manager = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: return null
        val view = TrailView(appContext)
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            windowType,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Android 12+ may block pass-through touches behind non-touchable SAW windows
            // whose combined opacity exceeds 0.8. Keep this visual-only window translucent.
            alpha = 0.75f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        return try {
            manager.addView(view, params)
            windowManager = manager
            trailView = view
            attached = true
            view
        } catch (_: Exception) {
            windowManager = null
            trailView = null
            attached = false
            null
        }
    }

    private fun removeOnMainThread() {
        val view = trailView
        val manager = windowManager
        val wasAttached = attached
        attached = false
        trailView = null
        windowManager = null
        view?.stopAnimation()
        if (wasAttached && view != null && manager != null) {
            try {
                manager.removeViewImmediate(view)
            } catch (_: IllegalArgumentException) {
                // The system already detached the view.
            } catch (_: WindowManager.BadTokenException) {
                // The overlay token is no longer valid.
            } catch (_: Exception) {
                // A transient WindowManager failure must not interrupt accessibility work.
            }
        }
    }

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post { action() }
    }

    private class TrailView(context: Context) : View(context) {
        private enum class Mode { NONE, SWIPE, CLICK }

        private val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
        private val fullPathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x6652B8FF
            style = Paint.Style.STROKE
            strokeWidth = 3.5f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF32D6FF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 4.5f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFB74D.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 2.5f * density
        }
        private val clickRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFF7043.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 3f * density
        }
        private val clickFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xCCFF7043.toInt()
            style = Paint.Style.FILL
        }
        private val progressPath = Path()
        private var mode = Mode.NONE
        private var swipePath: Path? = null
        private var pathMeasure: PathMeasure? = null
        private var pathLength = 0f
        private var progress = 0f
        private var swipeAnimator: ValueAnimator? = null
        private var clickBounds: RectF? = null
        private var clickProgress = 0f
        private var clickAnimator: ValueAnimator? = null

        init {
            isClickable = false
            isFocusable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        fun showSwipe(path: Path, durationMs: Long) {
            stopAnimation()
            mode = Mode.SWIPE
            swipePath = Path(path)
            pathMeasure = PathMeasure(swipePath, false)
            pathLength = pathMeasure?.length ?: 0f
            progress = 0f
            clickBounds = null
            invalidate()

            if (pathLength <= 0f) return
            swipeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = durationMs
                interpolator = LinearInterpolator()
                addUpdateListener {
                    progress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        fun finishSwipe() {
            swipeAnimator?.cancel()
            swipeAnimator = null
            progress = 1f
            invalidate()
        }

        fun showClick(bounds: Rect) {
            stopAnimation()
            mode = Mode.CLICK
            clickBounds = RectF(bounds)
            clickProgress = 0f
            swipePath = null
            pathMeasure = null
            pathLength = 0f
            clickAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = CLICK_VISIBLE_MS - 80L
                interpolator = LinearInterpolator()
                addUpdateListener {
                    clickProgress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        fun stopAnimation() {
            swipeAnimator?.cancel()
            swipeAnimator = null
            clickAnimator?.cancel()
            clickAnimator = null
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val screenLocation = IntArray(2)
            getLocationOnScreen(screenLocation)
            canvas.save()
            // AccessibilityNodeInfo bounds and generated gesture paths use screen coordinates.
            canvas.translate(-screenLocation[0].toFloat(), -screenLocation[1].toFloat())
            when (mode) {
                Mode.SWIPE -> drawSwipe(canvas)
                Mode.CLICK -> drawClick(canvas)
                Mode.NONE -> Unit
            }
            canvas.restore()
        }

        private fun drawSwipe(canvas: Canvas) {
            val path = swipePath ?: return
            val measure = pathMeasure ?: return
            val start = FloatArray(2)
            val end = FloatArray(2)
            if (!measure.getPosTan(0f, start, null)) return
            if (!measure.getPosTan(pathLength, end, null)) return

            // The dim line is the complete Path passed to dispatchGesture; the bright part
            // advances with the gesture's elapsed duration.
            canvas.drawPath(path, fullPathPaint)
            progressPath.reset()
            val stopDistance = (pathLength * progress).coerceIn(0f, pathLength)
            if (stopDistance > 0f) {
                measure.getSegment(0f, stopDistance, progressPath, true)
                canvas.drawPath(progressPath, progressPaint)
            }

            markerPaint.color = 0xFF69F0AE.toInt()
            canvas.drawCircle(start[0], start[1], 6f * density, markerPaint)
            markerPaint.color = 0xFFFF8A65.toInt()
            canvas.drawCircle(end[0], end[1], 6f * density, markerPaint)

            if (stopDistance > 0f) {
                val current = FloatArray(2)
                if (measure.getPosTan(stopDistance, current, null)) {
                    markerPaint.color = Color.WHITE
                    canvas.drawCircle(current[0], current[1], 8f * density, markerPaint)
                    markerPaint.color = 0xFF00B8D4.toInt()
                    canvas.drawCircle(current[0], current[1], 5f * density, markerPaint)
                }
            }
        }

        private fun drawClick(canvas: Canvas) {
            val bounds = clickBounds ?: return
            val pulse = clickProgress.coerceIn(0f, 1f)
            val centerX = bounds.centerX()
            val centerY = bounds.centerY()
            canvas.drawRoundRect(bounds, 8f * density, 8f * density, outlinePaint)

            val baseRadius = 10f * density
            val expandingRadius = baseRadius + pulse * 22f * density
            clickRingPaint.alpha = ((1f - pulse) * 230f).toInt().coerceIn(0, 230)
            canvas.drawCircle(centerX, centerY, expandingRadius, clickRingPaint)
            clickFillPaint.alpha = 210
            canvas.drawCircle(centerX, centerY, 5f * density, clickFillPaint)

            // Small crosshair makes the center of the activated node easy to locate.
            val cross = 13f * density
            clickRingPaint.alpha = 255
            canvas.drawLine(centerX - cross, centerY, centerX - 6f * density, centerY, clickRingPaint)
            canvas.drawLine(centerX + 6f * density, centerY, centerX + cross, centerY, clickRingPaint)
            canvas.drawLine(centerX, centerY - cross, centerX, centerY - 6f * density, clickRingPaint)
            canvas.drawLine(centerX, centerY + 6f * density, centerX, centerY + cross, clickRingPaint)
        }
    }
}
