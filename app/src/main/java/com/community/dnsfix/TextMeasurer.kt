package com.community.dnsfix.handwriting

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface

data class TextMetrics(
    val width: Float,
    val height: Float,
    val baseline: Float,
    val bounds: Rect
)

class TextMeasurer(
    textSize: Float,
    typeface: Typeface? = null
) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.textSize = textSize
        this.typeface = typeface ?: Typeface.DEFAULT
    }

    fun measure(text: String): TextMetrics {
        val bounds = Rect()
        paint.getTextBounds(text, 0, text.length, bounds)
        val fm = paint.fontMetrics
        return TextMetrics(
            width = paint.measureText(text),
            height = fm.descent - fm.ascent,
            baseline = -fm.ascent,
            bounds = bounds
        )
    }
}