package com.retro.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/** Full-screen CRT scanline overlay. Touches pass straight through to the views underneath. */
class ScanlineView @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : View(c, a) {
    private val p = Paint().apply { color = Color.argb(60, 0, 0, 0) }
    override fun onDraw(cv: Canvas) {
        val d = resources.displayMetrics.density
        var y = 0f
        while (y < height) { cv.drawRect(0f, y, width.toFloat(), y + d, p); y += 3 * d }
    }
}
