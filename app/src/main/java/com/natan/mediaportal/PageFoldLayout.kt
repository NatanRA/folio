package com.natan.mediaportal

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.hypot

/** A horizontal forward swipe starts one committed, uninterrupted paper fold. */
class PageFoldLayout(context: Context) : FrameLayout(context) {
    var beginTurn: ((Boolean) -> Unit) -> Unit = { complete -> complete(false) }
    var turnReleased: () -> Unit = {}
    var folding = false
        private set
    private var candidate = false
    private var downX = 0f
    private var downY = 0f
    private var fingerX = 0f
    private var fingerY = 0f
    private var paper: Bitmap? = null
    private var destination: Bitmap? = null
    private var animator: ValueAnimator? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (folding) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                candidate = true
                downX = event.x; downY = event.y
            }
            MotionEvent.ACTION_MOVE -> if (candidate && downX - event.x > slop && downX - event.x > kotlin.math.abs(downY - event.y) * 1.25f) {
                candidate = false
                val snapshot = runCatching {
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { draw(Canvas(it)) }
                }.getOrNull() ?: return false
                paper = snapshot
                folding = true
                parent?.requestDisallowInterceptTouchEvent(true)
                updateFinger(width - 1f, height - 1f)
                // Let ViewGroup deliver CANCEL to the reader before requesting navigation.
                // Starting navigation inside interception races its native drag cleanup.
                post {
                    if (folding && isAttachedToWindow) {
                        beginTurn { advanced ->
                            if (folding && isAttachedToWindow) {
                                if (advanced) {
                                    // Freeze the prepared destination too: chapter WebViews can
                                    // still invalidate while the paper is being composited.
                                    destination = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                                        superDispatchDraw(Canvas(it))
                                    }
                                    settle()
                                } else clearFold()
                            }
                        }
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> candidate = false
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return folding || super.onTouchEvent(event)
    }

    private fun updateFinger(x: Float, y: Float) {
        fingerX = x.coerceIn(-2f * width, width - 1f)
        fingerY = y.coerceIn(height * .15f, height - 1f)
        invalidate()
    }

    private fun settle() {
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (ValueAnimator.areAnimatorsEnabled()) 700 else 0
            interpolator = android.view.animation.PathInterpolator(.35f, 0f, .65f, 1f)
            addUpdateListener {
                val t = it.animatedValue as Float
                // Lift the corner, then move the entire folded sheet beyond the left edge.
                updateFinger(width * (1f - 3f * t) - 1f, height - 1f - height * .28f * kotlin.math.sin(Math.PI * t).toFloat())
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    clearFold(); turnReleased()
                }
            })
            start()
        }
    }

    private fun clearFold() {
        paper?.recycle(); paper = null
        destination?.recycle(); destination = null
        folding = false; candidate = false
        parent?.requestDisallowInterceptTouchEvent(false)
        invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        destination?.let { canvas.drawBitmap(it, 0f, 0f, null) } ?: super.dispatchDraw(canvas)
        val image = paper ?: return
        val w = width.toFloat(); val h = height.toFloat()
        val distance = hypot(w - fingerX, h - fingerY).coerceAtLeast(1f)
        val nx = (w - fingerX) / distance; val ny = (h - fingerY) / distance
        val c = nx * (w + fingerX) / 2 + ny * (h + fingerY) / 2
        val rectangle = listOf(PointF(0f, 0f), PointF(w, 0f), PointF(w, h), PointF(0f, h))
        fun clipped(keepFront: Boolean): List<PointF> {
            val result = mutableListOf<PointF>()
            for (i in rectangle.indices) {
                val a = rectangle[i]; val b = rectangle[(i + 1) % 4]
                val da = nx * a.x + ny * a.y - c; val db = nx * b.x + ny * b.y - c
                if ((da <= 0) == keepFront) result.add(a)
                if ((da <= 0) != (db <= 0)) {
                    val t = da / (da - db)
                    result.add(PointF(a.x + t * (b.x - a.x), a.y + t * (b.y - a.y)))
                }
            }
            return result
        }
        fun path(points: List<PointF>) = Path().apply {
            points.firstOrNull()?.let { moveTo(it.x, it.y) }
            points.drop(1).forEach { lineTo(it.x, it.y) }; close()
        }
        paint.reset(); paint.isAntiAlias = true; paint.isFilterBitmap = true
        canvas.save(); canvas.clipPath(path(clipped(true))); canvas.drawBitmap(image, 0f, 0f, paint); canvas.restore()
        val folded = clipped(false).map { p ->
            val d = nx * p.x + ny * p.y - c
            PointF(p.x - 2 * nx * d, p.y - 2 * ny * d)
        }
        val flap = path(folded)
        paint.color = Color.argb(50, 0, 0, 0)
        canvas.save(); canvas.translate(7f, 10f); canvas.drawPath(flap, paint); canvas.restore()
        paint.alpha = 255
        paint.shader = LinearGradient(fingerX, fingerY, fingerX + nx * distance * .5f, fingerY + ny * distance * .5f,
            intArrayOf(Color.rgb(247, 243, 231), Color.rgb(255, 253, 245), Color.rgb(184, 181, 171)), floatArrayOf(0f, .8f, 1f), Shader.TileMode.CLAMP)
        canvas.drawPath(flap, paint); paint.shader = null
        val reflection = Matrix().apply { setValues(floatArrayOf(1 - 2 * nx * nx, -2 * nx * ny, 2 * c * nx, -2 * nx * ny, 1 - 2 * ny * ny, 2 * c * ny, 0f, 0f, 1f)) }
        canvas.save(); canvas.clipPath(flap); paint.alpha = 28; canvas.drawBitmap(image, reflection, paint); canvas.restore(); paint.alpha = 255
    }

    private fun superDispatchDraw(canvas: Canvas) = super.dispatchDraw(canvas)

    override fun onDetachedFromWindow() {
        animator?.removeAllListeners(); animator?.cancel(); clearFold()
        super.onDetachedFromWindow()
    }
}
