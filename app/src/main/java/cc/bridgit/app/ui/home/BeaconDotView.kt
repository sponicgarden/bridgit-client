package cc.bridgit.app.ui.home

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import cc.bridgit.app.ui.common.dp

/**
 * The beacon dot (UI-04, UI-09): a 12 dp dot, and while live a ring that grows
 * 0 → 7 dp and fades over 1.6 s. With Remove animations on, the dot stays static.
 * Decorative: the state is always also written as text.
 */
class BeaconDotView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG)
    private var progress = 0f
    private var animator: ValueAnimator? = null
    private var pulsing = false
    private val radius = ctx.dp(6).toFloat()
    private val ringMax = ctx.dp(7).toFloat()

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun set(color: Int, pulse: Boolean) {
        dot.color = color
        ring.color = color
        if (pulse != pulsing) {
            pulsing = pulse
            if (pulse) start() else stop()
        }
        invalidate()
    }

    private fun start() {
        stop()
        if (!ValueAnimator.areAnimatorsEnabled() || !isAttachedToWindow) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1600
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun stop() {
        animator?.cancel(); animator = null; progress = 0f
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (pulsing) start() }
    override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = ((radius + ringMax) * 2).toInt()
        setMeasuredDimension(resolveSize(size, widthMeasureSpec), resolveSize(size, heightMeasureSpec))
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        if (animator != null && progress > 0f) {
            ring.alpha = ((1f - progress) * 0.7f * 255).toInt()
            c.drawCircle(cx, cy, radius + ringMax * progress, ring)
        }
        c.drawCircle(cx, cy, radius, dot)
    }
}
