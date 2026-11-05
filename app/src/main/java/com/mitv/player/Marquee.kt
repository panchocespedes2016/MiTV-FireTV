package com.mitv.player

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.Gravity
import android.view.animation.LinearInterpolator
import android.widget.TextView
import java.util.WeakHashMap

/** Marquee for truncated text, Fire TV style: when a text is longer than
 *  its view, it scrolls right-to-left until the last letter is visible,
 *  holds 5 s, scrolls back to the start, and repeats forever — but only
 *  while its row/card has the focus. Text that fits never moves.
 *
 *  Timing: hold 2 s at the start (so the beginning stays readable),
 *  scroll at 40dp/s, hold 5 s at the end, scroll back, repeat.
 *
 *  This is a custom animator, not the platform marquee: the platform one
 *  jumps back to the start and its end pause is fixed and short. Here
 *  the return is a scroll, and the 5 s hold is the one asked for.
 *
 *  Views are single-line with ellipsize END at rest. While scrolling,
 *  the ellipsis is switched off so the hidden tail is laid out instead
 *  of replaced by "…", and the view is set horizontally scrolling: a
 *  single-line TextView otherwise lays its text out only as wide as the
 *  view, so scrolling would just slide the truncated text out and show
 *  blank. Stopping restores the ellipsis, the scroll position, the
 *  horizontal-scroll flag and the original gravity (centered titles
 *  scroll from the start, not from the middle). */
object Marquee {
    private const val SPEED_DP_PER_S = 40f
    private const val END_HOLD_MS = 5000L
    private const val START_HOLD_MS = 2000L

    private class Job(val tv: TextView) {
        var cancelled = false
        var animator: ValueAnimator? = null
        var delayed: Runnable? = null
        var savedGravity: Int? = null
    }

    private val jobs = WeakHashMap<TextView, Job>()

    fun start(vararg tvs: TextView) = tvs.forEach { startOne(it) }
    fun stop(vararg tvs: TextView) = tvs.forEach { stopOne(it) }

    private fun startOne(tv: TextView) {
        stopOne(tv)
        val job = Job(tv)
        jobs[tv] = job
        // Let the text lay out wider than the view (see the class doc);
        // the relayout lands before the posted cycle below runs. None of
        // the marquee'd views scrolls horizontally by default, so stop
        // restores false.
        tv.setHorizontallyScrolling(true)
        tv.post { if (!job.cancelled && tv.isAttachedToWindow) runCycle(job) }
    }

    private fun stopOne(tv: TextView) {
        val job = jobs.remove(tv) ?: return
        job.cancelled = true
        job.animator?.cancel()
        job.delayed?.let { tv.removeCallbacks(it) }
        tv.scrollX = 0
        tv.ellipsize = android.text.TextUtils.TruncateAt.END
        tv.setHorizontallyScrolling(false)
        job.savedGravity?.let { tv.gravity = it }
    }

    private fun runCycle(job: Job) {
        val tv = job.tv
        if (job.cancelled || !tv.isAttachedToWindow) { jobs.remove(tv); return }
        val avail = tv.width - tv.paddingLeft - tv.paddingRight
        if (avail <= 0) {
            // Not laid out yet: one retry on the next frame.
            val r = Runnable { if (!job.cancelled) runCycle(job) }
            job.delayed = r
            tv.post(r)
            return
        }
        // Do NOT measure the layout's line width: with ellipsize END the
        // layout truncates the line to fit, so its width always looks
        // like the text fits and nothing would ever scroll. Measure the
        // full text with the paint instead.
        val max = (tv.paint.measureText(tv.text.toString()) - avail).toInt()
        if (max <= 0) {
            // Text fits: leave the view exactly as it was.
            tv.setHorizontallyScrolling(false)
            jobs.remove(tv)
            return
        }
        if (job.savedGravity == null) {
            job.savedGravity = tv.gravity
            if (tv.gravity and Gravity.HORIZONTAL_GRAVITY_MASK == Gravity.CENTER_HORIZONTAL) {
                tv.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            }
        }
        tv.ellipsize = null
        val speedPx = SPEED_DP_PER_S * tv.resources.displayMetrics.density
        val scrollMs = ((max / speedPx) * 1000).toLong().coerceIn(800, 15000)
        animateTo(job, 0, max, scrollMs) {
            hold(job, END_HOLD_MS) {
                animateTo(job, max, 0, scrollMs) {
                    hold(job, START_HOLD_MS) { runCycle(job) }
                }
            }
        }
    }

    private fun animateTo(job: Job, from: Int, to: Int, ms: Long, onEnd: () -> Unit) {
        val tv = job.tv
        if (job.cancelled || !tv.isAttachedToWindow) return
        val anim = ValueAnimator.ofInt(from, to).apply {
            duration = ms
            interpolator = LinearInterpolator()
            addUpdateListener { tv.scrollX = it.animatedValue as Int }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) { if (!job.cancelled) onEnd() }
            })
        }
        job.animator = anim
        anim.start()
    }

    private fun hold(job: Job, ms: Long, next: () -> Unit) {
        val tv = job.tv
        if (job.cancelled) return
        val r = Runnable { if (!job.cancelled && tv.isAttachedToWindow) next() }
        job.delayed = r
        tv.postDelayed(r, ms)
    }
}
