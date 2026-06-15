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
            drawComplexWordWithPerClusterVariation(canvas, wp, alpha, washR, washG, washB, spreadBaseStd, typeface)
        } else {
            // existing vector warp for Latin scripts remains unchanged
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
    // Burmese handwriting rules: per‑cluster variance + global flow
    // ---------------------------------------------------------------
    private fun drawComplexWordWithPerClusterVariation(
        canvas: Canvas,
        wp: WordPlacement,
        alpha: Int,
        washR: Float,
        washG: Float,
        washB: Float,
        spreadStd: Float,
        typeface: Typeface?
    ) {
        // 1. Split the word into clusters using the Tokenizer
        val clusters = Tokenizer.splitMyanmarClusters(wp.text)
        if (clusters.isEmpty()) return

        val clusterRand = Random(wp.seed.toLong())

        // 2. Measure each cluster and build individual bitmaps with random scaling
        data class ClusterBitmap(
            val bitmap: Bitmap,
            val width: Float,    // original (unscaled) width for positioning
            val scaleX: Float,
            val scaleY: Float,
            val offsetY: Float   // vertical nudge
        )

        val paintForMeasure = Paint(textPaint)
        val clusterBitmaps = mutableListOf<ClusterBitmap>()

        for (cluster in clusters) {
            // Random anisotropic scale for this cluster (break perfect circles)
            val sx = (0.85f + clusterRand.nextFloat() * 0.30f).coerceIn(0.8f, 1.15f)  // 0.8–1.15
            val sy = (0.85f + clusterRand.nextFloat() * 0.25f).coerceIn(0.8f, 1.1f)   // 0.8–1.1

            val bounds = Rect()
            paintForMeasure.getTextBounds(cluster, 0, cluster.length, bounds)
            val bw = bounds.width().toFloat()
            val bh = bounds.height().toFloat()
            if (bw <= 0f || bh <= 0f) continue

            // Slightly larger bitmap to accommodate scaling
            val bmp = Bitmap.createBitmap(
                ceil(bw * sx).toInt() + 8,
                ceil(bh * sy).toInt() + 8,
                Bitmap.Config.ARGB_8888
            )
            val tempCanvas = Canvas(bmp)
            tempCanvas.save()
            // Center the scaled text in the new bitmap
            tempCanvas.translate((bmp.width - bw * sx) / 2f, (bmp.height - bh * sy) / 2f)
            tempCanvas.scale(sx, sy)
            tempCanvas.drawText(cluster, -bounds.left.toFloat(), -bounds.top.toFloat(), paintForMeasure)
            tempCanvas.restore()

            clusterBitmaps.add(ClusterBitmap(bmp, bw, sx, sy, bounds.top.toFloat()))
        }

        if (clusterBitmaps.isEmpty()) return

        // 3. Assemble clusters onto a word-level bitmap with micro‑offsets
        val totalWidth = wp.estimatedWidth  // use the layout width to ensure consistent spacing
        val wordHeight = clusterBitmaps.maxOf { it.bitmap.height.toFloat() } + 10f
        val wordBitmap = Bitmap.createBitmap(
            ceil(totalWidth).toInt() + 20,
            ceil(wordHeight).toInt() + 10,
            Bitmap.Config.ARGB_8888
        )
        val wordCanvas = Canvas(wordBitmap)
        var cursorX = 10f

        for (cb in clusterBitmaps) {
            // Random vertical nudge to simulate uneven baseline
            val nudgeY = PenState.gaussian(0f, 1.2f, clusterRand) + (wordHeight - cb.bitmap.height) / 2f
            wordCanvas.drawBitmap(cb.bitmap, cursorX, nudgeY, null)
            // Advance cursor by the ORIGINAL width (layout) plus a small random gap
            cursorX += cb.width + (0.5f + clusterRand.nextFloat() * 1.5f)
            cb.bitmap.recycle()
        }

        // 4. Global mesh warp on the assembled word (low‑frequency flow)
        val meshRand = Random((wp.seed xor 0x5A5A5A5A).toLong())
        val bw = wordBitmap.width.toFloat()
        val bh = wordBitmap.height.toFloat()
        val cellSize = max(bw, bh) * 0.7f
        val gridCols = max(2, ceil(bw / cellSize).toInt())
        val gridRows = max(2, ceil(bh / cellSize).toInt())
        val meshWidth = gridCols + 1
        val meshHeight = gridRows + 1
        val verts = FloatArray(meshWidth * meshHeight * 2)
        val strength = 2.0f * wp.transforms.tremor

        for (r in 0 until meshHeight) {
            for (c in 0 until meshWidth) {
                val idx = (r * meshWidth + c) * 2
                verts[idx] = c * (bw / gridCols) + PenState.gaussian(0f, strength * 0.6f, meshRand)
                verts[idx + 1] = r * (bh / gridRows) + PenState.gaussian(0f, strength * 0.6f, meshRand)
            }
        }

        // 5. Draw the warped word with ink spread
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
            canvas.drawBitmapMesh(wordBitmap, gridCols, gridRows, verts, 0, null, 0, spreadPaint)
            canvas.restore()
        }
        textPaint.color = Color.argb(alpha, washR.toInt(), washG.toInt(), washB.toInt())
        canvas.drawBitmapMesh(wordBitmap, gridCols, gridRows, verts, 0, null, 0, textPaint)

        wordBitmap.recycle()
    }

    // Original fallback (unchanged)
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