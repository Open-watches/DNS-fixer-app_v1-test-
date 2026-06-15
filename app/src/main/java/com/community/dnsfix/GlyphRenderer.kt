package com.community.dnsfix.handwriting

import android.graphics.*
import kotlin.math.*
import java.util.Random

class GlyphRenderer(
    private val baseInkColor: Int,
    private val fontSize: Float
) {
    private val spreadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textSize = fontSize
    }

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

        val washRand = Random((wp.seed and 0x7FFFFFFF).toLong())
        val washR = (baseRed + PenState.gaussian(0f, 8f, washRand)).coerceIn(0f, 255f)
        val washG = (baseGreen + PenState.gaussian(0f, 8f, washRand)).coerceIn(0f, 255f)
        val washB = (baseBlue + PenState.gaussian(0f, 8f, washRand)).coerceIn(0f, 255f)

        val finalColor = Color.argb(alpha, washR.toInt(), washG.toInt(), washB.toInt())
        textPaint.color = finalColor

        val spreadBaseStd = 0.3f + t.pressure * 0.7f

        val isComplex = containsComplexScript(wp.text)

        canvas.save()
        val matrix = Matrix()
        val slantDeg = globalSlant + t.slantOffset
        val skewX = -tan(Math.toRadians(slantDeg.toDouble())).toFloat()
        matrix.postSkew(skewX, 0f)
        matrix.postRotate(t.rotation)
        matrix.postScale(t.scaleX, t.scaleY)
        matrix.postTranslate(x + t.dx, y + baselineDrift + t.dy)
        canvas.concat(matrix)

        if (isComplex) {
            // ---- Bitmap mesh warp for complex scripts ----
            drawComplexWordMesh(canvas, wp, alpha, washR, washG, washB, spreadBaseStd)
        } else {
            // ---- Normal vector path warping ----
            val rawPath = Path()
            textPaint.getTextPath(wp.text, 0, wp.text.length, 0f, 0f, rawPath)
            val bounds = RectF()
            rawPath.computeBounds(bounds, true)

            if (rawPath.isEmpty || bounds.width() < 2f || bounds.height() < 2f) {
                drawTextFallback(canvas, wp.text, wp.seed, alpha, washR, washG, washB, spreadBaseStd)
            } else {
                val baseStrength = when {
                    wp.text.length <= 2 -> 2.5f
                    wp.text.length <= 4 -> 2.0f
                    else -> 1.5f
                }
                val lengthFactor = 1f + (wp.text.length - 1) * 0.06f
                val warpStrength = baseStrength * lengthFactor * t.tremor
                val warpedPath = PathWarper.warpWithGrid(
                    rawPath, warpStrength, wp.seed xor 0x5A5A5A5A, wp.text.length, bounds
                )

                val spreadRand = Random((wp.seed * 31).toLong())
                for (i in 0..7) {
                    val offX = PenState.gaussian(0f, spreadBaseStd, spreadRand)
                    val offY = PenState.gaussian(0f, spreadBaseStd, spreadRand)
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
        }
        canvas.restore()
    }

    // ---------------------------------------------------------------
    // Bitmap mesh warp for complex scripts (breaks perfect circles)
    // ---------------------------------------------------------------
    private fun drawComplexWordMesh(
        canvas: Canvas,
        wp: WordPlacement,
        alpha: Int,
        washR: Float,
        washG: Float,
        washB: Float,
        spreadStd: Float
    ) {
        // 1. Measure the word's bounding box
        val bounds = Rect()
        textPaint.getTextBounds(wp.text, 0, wp.text.length, bounds)
        val bw = bounds.width().toFloat()
        val bh = bounds.height().toFloat()
        if (bw <= 0f || bh <= 0f) return

        // 2. Create a temporary bitmap and draw the shaped text onto it
        val wordBitmap = Bitmap.createBitmap(
            ceil(bw).toInt() + 4,   // small padding
            ceil(bh).toInt() + 4,
            Bitmap.Config.ARGB_8888
        )
        val tempCanvas = Canvas(wordBitmap)
        // Draw text offset so that its bounding box fits nicely
        tempCanvas.drawText(wp.text, -bounds.left.toFloat() + 2f, -bounds.top.toFloat() + 2f, textPaint)

        // 3. Build a low-frequency warp grid (same style as PathWarper)
        val cellSize = max(bw, bh) * 0.7f
        val gridCols = max(2, ceil(bw / cellSize).toInt())
        val gridRows = max(2, ceil(bh / cellSize).toInt())
        val meshWidth = gridCols + 1
        val meshHeight = gridRows + 1

        val rand = Random((wp.seed xor 0x5A5A5A5A).toLong())
        val strength = 2.0f * wp.transforms.tremor  // adjust as needed

        // 4. Generate the vertex array for drawBitmapMesh
        val verts = FloatArray((meshWidth) * (meshHeight) * 2)
        for (r in 0 until meshHeight) {
            for (c in 0 until meshWidth) {
                val idx = (r * meshWidth + c) * 2
                // Original position in the bitmap
                val origX = c * (bw / gridCols)
                val origY = r * (bh / gridRows)
                // Displacement
                val dx = PenState.gaussian(0f, strength * 0.6f, rand)
                val dy = PenState.gaussian(0f, strength * 0.6f, rand)
                verts[idx] = origX + dx
                verts[idx + 1] = origY + dy
            }
        }

        // 5. Draw the warped bitmap with ink spread
        val spreadRand = Random((wp.seed * 31).toLong())
        for (i in 0..3) {
            val offX = PenState.gaussian(0f, spreadStd * 0.6f, spreadRand)
            val offY = PenState.gaussian(0f, spreadStd * 0.6f, spreadRand)
            spreadPaint.color = Color.argb(
                (alpha * 0.12f).toInt().coerceIn(5, 20),
                washR.toInt(), washG.toInt(), washB.toInt()
            )
            canvas.save()
            canvas.translate(offX, offY)
            canvas.drawBitmapMesh(
                wordBitmap,
                gridCols, gridRows,
                verts, 0,
                null, 0,
                spreadPaint
            )
            canvas.restore()
        }
        // Main ink layer
        textPaint.color = Color.argb(alpha, washR.toInt(), washG.toInt(), washB.toInt())
        canvas.drawBitmapMesh(
            wordBitmap,
            gridCols, gridRows,
            verts, 0,
            null, 0,
            textPaint
        )

        wordBitmap.recycle()
    }

    // Original fallback (kept for degenerate Latin paths)
    private fun drawTextFallback(
        canvas: Canvas,
        text: String,
        seed: Int,
        alpha: Int,
        washR: Float,
        washG: Float,
        washB: Float,
        spreadStd: Float
    ) {
        val spreadRand = Random((seed * 31).toLong())
        for (i in 0..3) {
            val offX = PenState.gaussian(0f, spreadStd * 0.6f, spreadRand)
            val offY = PenState.gaussian(0f, spreadStd * 0.6f, spreadRand)
            spreadPaint.color = Color.argb(
                (alpha * 0.12f).toInt().coerceIn(5, 20),
                washR.toInt(), washG.toInt(), washB.toInt()
            )
            canvas.save()
            canvas.translate(offX, offY)
            canvas.drawText(text, 0f, 0f, spreadPaint)
            canvas.restore()
        }
        canvas.drawText(text, 0f, 0f, textPaint)
    }

    private fun containsComplexScript(text: String): Boolean {
        for (ch in text) {
            if (ch in '\u1000'..'\u109F') return true
        }
        return false
    }
}