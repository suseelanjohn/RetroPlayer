package com.retro.player

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

/** CRT-green vinyl turntable. Drag the tonearm onto the record to play, lift it off to stop. */
class VinylView @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : View(c, a) {
    interface Listener { fun onDrop(); fun onLift() }

    var listener: Listener? = null
    var speed = 200f
    var needleDown = false
        private set

    private val g = Color.parseColor("#33FF33")
    private val dim = Color.parseColor("#0F4A0F")
    private val dp = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; typeface = Typeface.MONOSPACE }
    private val rect = RectF()
    private var rot = 0f
    private var last = 0L
    private var cx = 0f; private var cy = 0f; private var r = 0f
    private var px = 0f; private var py = 0f; private var len = 0f
    private var rest = 0f; private var drop = 0f; private var ang = 0f
    private var dragging = false
    private var anim: ValueAnimator? = null

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        r = min(w * 0.38f, h * 0.44f); cx = w * 0.40f; cy = h * 0.5f
        px = w * 0.90f; py = h * 0.14f
        len = hypot(cx - px, cy - py) - 0.62f * r
        drop = atan2(cy - py, cx - px)
        rest = drop - 0.87f
        ang = if (needleDown) drop else rest
    }

    fun setNeedle(down: Boolean) {
        if (down == needleDown) return
        needleDown = down
        animateTo(if (down) drop else rest)
        invalidate()
    }

    private fun animateTo(t: Float) {
        anim?.cancel()
        anim = ValueAnimator.ofFloat(ang, t).apply {
            duration = 500
            addUpdateListener { ang = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(cv: Canvas) {
        val now = System.nanoTime()
        if (needleDown && last != 0L) rot = (rot + min((now - last) / 1e9f, 0.05f) * speed) % 360f
        last = if (needleDown) now else 0L
        cv.drawColor(Color.BLACK)

        // record + grooves
        p.style = Paint.Style.FILL; p.color = Color.parseColor("#061406"); cv.drawCircle(cx, cy, r, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * dp; p.color = g; cv.drawCircle(cx, cy, r, p)
        p.strokeWidth = dp; p.color = dim
        var rr = r * 0.34f
        while (rr < r - 4 * dp) { cv.drawCircle(cx, cy, rr, p); rr += 5 * dp }
        // rotating sheen
        p.color = g; p.strokeWidth = 2 * dp
        rect.set(cx - r * 0.8f, cy - r * 0.8f, cx + r * 0.8f, cy + r * 0.8f)
        cv.drawArc(rect, rot, 25f, false, p); cv.drawArc(rect, rot + 180f, 25f, false, p)
        // label
        p.style = Paint.Style.FILL; cv.drawCircle(cx, cy, r * 0.3f, p)
        p.color = Color.BLACK; cv.drawCircle(cx, cy, r * 0.04f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 2 * dp
        cv.save(); cv.rotate(rot, cx, cy); cv.drawLine(cx, cy - r * 0.1f, cx, cy - r * 0.26f, p); cv.restore()

        // tonearm
        val dx = cos(ang); val dy = sin(ang)
        val tx = px + len * dx; val ty = py + len * dy
        p.color = g; p.strokeWidth = 3 * dp
        cv.drawLine(px - 24 * dp * dx, py - 24 * dp * dy, tx, ty, p)
        p.strokeWidth = 7 * dp
        cv.drawLine(tx - 14 * dp * dx, ty - 14 * dp * dy, tx, ty, p)
        cv.drawLine(px - 28 * dp * dx, py - 28 * dp * dy, px - 18 * dp * dx, py - 18 * dp * dy, p)
        p.style = Paint.Style.FILL
        cv.drawCircle(px, py, 9 * dp, p)
        p.color = Color.BLACK; cv.drawCircle(px, py, 4 * dp, p); cv.drawCircle(tx, ty, 1.5f * dp, p)

        // text + frame
        p.color = g; p.textSize = 11 * dp
        cv.drawText(if (needleDown) "> NOW SPINNING" else "> DRAG NEEDLE ONTO DISC", 8 * dp, height - 8 * dp, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = dp; p.color = dim
        cv.drawRect(0.5f * dp, 0.5f * dp, width - 0.5f * dp, height - 0.5f * dp, p)

        if (needleDown) postInvalidateOnAnimation()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val tx = px + len * cos(ang); val ty = py + len * sin(ang)
                if (hypot(e.x - tx, e.y - ty) > 60 * dp) return false
                dragging = true; anim?.cancel()
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                var a = atan2(e.y - py, e.x - px)
                if (a < 0) a += (2 * PI).toFloat()
                ang = a.coerceIn(rest, drop + 0.52f)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false
                val d = hypot(px + len * cos(ang) - cx, py + len * sin(ang) - cy)
                if (e.actionMasked == MotionEvent.ACTION_UP && d > r * 0.3f && d < r * 0.98f) {
                    needleDown = true; invalidate(); listener?.onDrop()
                } else {
                    needleDown = false; animateTo(rest); listener?.onLift()
                }
            }
        }
        return true
    }
}
