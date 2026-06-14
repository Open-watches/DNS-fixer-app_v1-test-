package com.community.dnsfix.handwriting

import android.graphics.*
import kotlin.math.tan
import java.util.Random

class GlyphRenderer(private val baseInkColor: Int) {
    private val spreadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DARKEN)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    fun drawWord(
        canvas: Canvas,
        wp: WordPlacement,
        x: Float,
        y: Float,
        baselineDrift: Float,
        globalSlant: Float,
        typeface: Typeface?
    ) {
        val t = wp.transforms
        textPaint.typeface = typeface ?: Typeface.DEFAULT

        val baseRed = Color.red(baseInkColor)
        val baseGreen = Color.green(baseInkColor)
        val baseBlue = Color.blue(baseInkColor)
        val alpha = (150 + t.pressure * 105).toInt().coerceIn(40, 255)
        val density = (0.5f + t.pressure * 0.5f).coerceIn(0.5f, 1f)

        val washRand = Random((wp.seed and 0x7FFFFFFF).toLong())
        val washR = (baseRed + PenState.gaussian(0f, 8f, washRand)).coerceIn(0f, 255f)
        val washG = (baseGreen + PenState.gaussian(0f, 8f, washRand)).coerceIn(0f, 255f)
        val washB = (baseBlue + PenState.gaussian(0f, 8f, washRand)).coerceIn(0f, 255f)

        val finalColor = Color.argb(alpha, (washR * density).toInt(), (washG * density).toInt(), (washB * density).toInt())
        textPaint.color = finalColor

        val rawPath = Path()
        textPaint.getTextPath(wp.text, 0, wp.text.length, 0f, 0f, rawPath)

        canvas.save()
        val matrix = Matrix()
        val slantDeg = globalSlant + t.slantOffset
        val skewX = -tan(Math.toRadians(slantDeg.toDouble())).toFloat()
        matrix.postSkew(skewX, 0f)
        matrix.postRotate(t.rotation)
        matrix.postScale(t.scaleX, t.scaleY)
        matrix.postTranslate(x + t.dx, y + baselineDrift + t.dy)
        canvas.concat(matrix)

        if (rawPath.isEmpty) {
            val spreadRand = Random((wp.seed * 31).toLong())
            for (i in 0..3) {
                val offX = PenState.gaussian(0f, 0.4f, spreadRand)
                val offY = PenState.gaussian(0f, 0.4f, spreadRand)
                spreadPaint.color = Color.argb((alpha * 0.12f).toInt().coerceIn(5, 20), washR.toInt(), washG.toInt(), washB.toInt())
                canvas.save()
                canvas.translate(offX, offY)
                canvas.drawText(wp.text, 0f, 0f, spreadPaint)
                canvas.restore()
            }
            canvas.drawText(wp.text, 0f, 0f, textPaint)
        } else {
            val baseStrength = when {
                wp.text.length <= 2 -> 2.5f
                wp.text.length <= 4 -> 2.0f
                else -> 1.5f
            }
            val lengthFactor = 1f + (wp.text.length - 1) * 0.06f
            val warpStrength = baseStrength * lengthFactor * t.tremor
            val warpedPath = PathWarper.warpWithGrid(rawPath, warpStrength, wp.seed xor 0x5A5A5A5A, wp.text.length)

            val spreadRand = Random((wp.seed * 31).toLong())
            for (i in 0..7) {
                val offX = PenState.gaussian(0f, 0.6f, spreadRand)
                val offY = PenState.gaussian(0f, 0.6f, spreadRand)
                spreadPaint.color = Color.argb(
                    (alpha * 0.15f * (1f - i / 8f)).toInt().coerceIn(5, 30),
                    washR.toInt(), washG.toInt(), washB.toInt()
                )
                canvas.save()
                canvas.translate(offX, offY)
                canvas.drawPath(warpedPath, spreadPaint)
                canvas.restore()
            }
            canvas.drawPath(warpedPath, textPaint)
        }
        canvas.restore()
    }
}