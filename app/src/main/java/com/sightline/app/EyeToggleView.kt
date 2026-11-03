// SPDX-License-Identifier: GPL-3.0-or-later

package com.sightline.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.text.method.PasswordTransformationMethod
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * Password dots rendered as little hearts (♥) instead of circles.
 * The glyph follows the field's text color.
 */
object HeartsTransformation : PasswordTransformationMethod() {
    override fun getTransformation(source: CharSequence, view: View): CharSequence {
        return HeartCharSequence(source)
    }

    private class HeartCharSequence(val src: CharSequence) : CharSequence {
        override val length: Int get() = src.length
        override fun get(index: Int): Char = '♥'
        override fun subSequence(start: Int, end: Int): CharSequence =
            HeartCharSequence(src.subSequence(start, end))
    }
}

/**
 * Show/hide password toggle drawn by hand: an open eye whose pupil is a
 * heart, or a closed eye with lashes. Brand pink on any background.
 */
class EyeToggleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var open: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var pink: Int = Color.parseColor("#F4A7C4")

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        stroke.color = pink
        fill.color = pink
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f * 0.62f
        stroke.strokeWidth = maxOf(3f, r * 0.14f)
        if (open) {
            val almond = Path().apply {
                moveTo(cx - r, cy)
                quadTo(cx, cy - r * 0.95f, cx + r, cy)
                quadTo(cx, cy + r * 0.95f, cx - r, cy)
            }
            canvas.drawPath(almond, stroke)
            canvas.drawPath(heartPath(cx, cy, r * 0.42f), fill)
        } else {
            val lashes = Path().apply {
                moveTo(cx - r, cy - r * 0.1f)
                quadTo(cx, cy + r * 0.9f, cx + r, cy - r * 0.1f)
            }
            canvas.drawPath(lashes, stroke)
            for (f in floatArrayOf(-0.5f, 0f, 0.5f)) {
                val x = cx + f * r
                val y = cy + r * 0.72f
                canvas.drawLine(x, y, x + f * r * 0.3f, y + r * 0.32f, stroke)
            }
        }
    }

    private fun heartPath(cx: Float, cy: Float, s: Float): Path {
        return Path().apply {
            moveTo(cx, cy + s * 0.85f)
            cubicTo(
                cx - s * 1.35f, cy - s * 0.05f,
                cx - s * 0.65f, cy - s * 1.05f,
                cx, cy - s * 0.25f,
            )
            cubicTo(
                cx + s * 0.65f, cy - s * 1.05f,
                cx + s * 1.35f, cy - s * 0.05f,
                cx, cy + s * 0.85f,
            )
            close()
        }
    }
}
