package com.community.dnsfix.handwriting

import android.graphics.*
import kotlin.math.*
import java.util.Random

class GlyphRenderer(
    private val baseInkColor: Int,
    private val fontSize: Float
) {
    // Paint that forces bilinear filtering on bitmap draws (replaces canvas.imageSmoothingEnabled)
    private val slicePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textSize = fontSize
    }

    // Reusable scratch bitmap – dynamically resized if needed
    private var scratchBitmap = Bitmap.createBitmap(600, 300, Bitmap.Config.ARGB_8888)
    private var scratchCanvas = Canvas(scratchBitmap)

    /**
     * Ensure the scratch bitmap is at least the required width and height.
     * Recycles the old one and creates a new one only when necessary.
     */
    private fun ensureScratchSize(minWidth: Int, minHeight: Int) {
        if (scratchBitmap.width < minWidth || scratchBitmap.height < minHeight) {
            scratchBitmap.recycle()
            scratchBitmap = Bitmap.createBitmap(
                max(scratchBitmap.width, minWidth),
                max(scratchBitmap.height, minHeight),
                Bitmap.Config.ARGB_8888
            )
            scratchCanvas = Canvas(scratchBitmap)
        }
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
            // Use per‑cluster scaling with baseline alignment + slice warp (NO extra bitmaps)
            drawComplexWordWithSliceWarp(canvas, wp, alpha, washR, washG, washB, typeface)
        } else {
            // Latin (or other simple script) – directly warp the whole word
            drawTextWithSliceWarp(canvas, wp.text, wp.seed, alpha, washR, washG, washB)
        }
        canvas.restore()
    }

    /**
     * Renders a text string by first drawing it to the reusable scratch bitmap,
     * then copying it slice‑by‑slice with a sinusoidal horizontal displacement.
     */
    private fun drawTextWithSliceWarp(
        canvas: Canvas,
        text: String,
        seed: Int,
        alpha: Int,
        washR: Float,
        washG: Float,
        washB: Float
    ) {
        // Measure the word's bounding box
        val bounds = Rect()
        textPaint.getTextBounds(text, 0, text.length, bounds)
        if (bounds.width() <= 0 || bounds.height() <= 0) return

        val wordWidth = bounds.width()
        val wordHeight = bounds.height()
        val padding = ceil(fontSize * 1.6f).toInt()

        // Ensure scratch bitmap is large enough
        ensureScratchSize(wordWidth + padding * 2, wordHeight + padding * 2)

        // Draw clean text onto scratch bitmap at a fixed position
        scratchCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        textPaint.color = Color.argb(alpha, washR.toInt(), washG.toInt(), washB.toInt())
        val textX = padding.toFloat() - bounds.left
        val textY = padding.toFloat() - bounds.top
        scratchCanvas.drawText(text, textX, textY, textPaint)

        // Ink shadow (pooling effect) – a blurred under‑layer
        val shadowPaint = Paint(textPaint).apply {
            color = Color.argb((alpha * 0.4f).toInt(), washR.toInt(), washG.toInt(), washB.toInt())
            maskFilter = BlurMaskFilter(2.5f, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawText(text, 0f, 0f, shadowPaint)

        // Slice the scratch bitmap with sine‑wave offset
        val sliceHeight = 2
        val topY = max(0, textY.toInt() - padding)
        val bottomY = min(scratchBitmap.height, textY.toInt() + padding)

        val rand = Random(seed.toLong())
        val waveSeedX = rand.nextFloat() * 10f
        val freqX = 0.22f + rand.nextFloat() * 0.12f
        val ampX = 0.45f + rand.nextFloat() * 0.45f

        canvas.save()
        for (sliceY in topY until bottomY step sliceHeight) {
            val relativeY = sliceY - textY
            val deformX = sin(relativeY * freqX + waveSeedX) * ampX + (rand.nextFloat() - 0.5f) * 0.15f
            val deformY = (rand.nextFloat() - 0.5f) * 0.2f

            canvas.drawBitmap(
                scratchBitmap,
                Rect(0, sliceY, scratchBitmap.width, (sliceY + sliceHeight).coerceAtMost(bottomY)),
                RectF(
                    -textX + deformX,
                    (sliceY - textY) + deformY,
                    -textX + deformX + scratchBitmap.width.toFloat(),
                    (sliceY - textY) + deformY + sliceHeight
                ),
                slicePaint   // <-- bilinear filtering applied
            )
        }
        canvas.restore()
    }

    /**
     * For complex scripts, we draw each cluster with its own random scaling
     * directly onto a word‑sized bitmap, aligned to a shared baseline.
     * Then the whole word is slice‑warped. No per‑cluster bitmaps are created.
     */
    private fun drawComplexWordWithSliceWarp(
        canvas: Canvas,
        wp: WordPlacement,
        alpha: Int,
        washR: Float,
        washG: Float,
        washB: Float,
        typeface: Typeface?
    ) {
        val clusters = Tokenizer.splitMyanmarClusters(wp.text)
        if (clusters.isEmpty()) return

        val paint = Paint(textPaint).apply { this.typeface = typeface ?: Typeface.DEFAULT }
        val fm = paint.fontMetrics
        // Shared baseline Y: top of the word bitmap will have a fixed padding, then we draw text at baselineY
        val padding = ceil(fontSize * 1.0f).toInt()
        val baselineY = padding - fm.ascent   // baseline relative to top of bitmap

        // Measure total advance and maximum cluster height
        val clusterRand = Random(wp.seed.toLong())
        var totalWidth = 0f
        var maxHeight = 0f

        // First pass: compute layout dimensions
        data class ClusterLayout(val text: String, val sx: Float, val sy: Float, val width: Float)
        val layouts = mutableListOf<ClusterLayout>()

        for (cluster in clusters) {
            val sx = (0.8f + clusterRand.nextFloat() * 0.4f).coerceIn(0.8f, 1.2f)
            val sy = (0.8f + clusterRand.nextFloat() * 0.35f).coerceIn(0.8f, 1.15f)

            // Measure original width
            val origWidth = paint.measureText(cluster)
            val scaledWidth = origWidth * sx
            layouts.add(ClusterLayout(cluster, sx, sy, scaledWidth))

            totalWidth += scaledWidth + 2f   // small gap
            // Height: the cluster's effective bounding box after scaling; roughly (descent - ascent)*sy
            val clusterHeight = (fm.descent - fm.ascent) * sy
            if (clusterHeight > maxHeight) maxHeight = clusterHeight
        }

        if (layouts.isEmpty()) return

        // Create a word bitmap of the exact required size (one allocation per word, then recycled)
        val bitmapWidth = ceil(totalWidth + 2 * padding).toInt()
        val bitmapHeight = ceil(maxHeight + 2 * padding).toInt()
        val wordBitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        val wordCanvas = Canvas(wordBitmap)

        // Draw each cluster onto the word bitmap, aligned to the same baseline
        var cursorX = padding.toFloat()
        for (layout in layouts) {
            wordCanvas.save()
            // Move to the drawing position (baseline aligned)
            wordCanvas.translate(cursorX, baselineY)
            // Apply per‑cluster scale around the baseline point
            wordCanvas.scale(layout.sx, layout.sy)
            // Draw the cluster at (0,0) because we are already at the baseline
            wordCanvas.drawText(layout.text, 0f, 0f, paint)
            wordCanvas.restore()

            cursorX += layout.width + 2f
        }

        // Apply slice warp to the assembled word bitmap
        val sliceHeight = 2
        val rand = Random(wp.seed.toLong())
        val waveSeedX = rand.nextFloat() * 10f
        val freqX = 0.2f + rand.nextFloat() * 0.1f
        val ampX = 0.5f + rand.nextFloat() * 0.5f

        canvas.save()
        for (sliceY in 0 until wordBitmap.height step sliceHeight) {
            val relativeY = sliceY.toFloat()
            val deformX = sin(relativeY * freqX + waveSeedX) * ampX + (rand.nextFloat() - 0.5f) * 0.15f
            val deformY = (rand.nextFloat() - 0.5f) * 0.2f

            canvas.drawBitmap(
                wordBitmap,
                Rect(0, sliceY, wordBitmap.width, (sliceY + sliceHeight).coerceAtMost(wordBitmap.height)),
                RectF(
                    deformX,
                    relativeY + deformY,
                    deformX + wordBitmap.width,
                    relativeY + deformY + sliceHeight
                ),
                slicePaint
            )
        }
        canvas.restore()

        wordBitmap.recycle()
    }

    private fun containsComplexScript(text: String): Boolean {
        for (ch in text) if (ch in '\u1000'..'\u109F') return true
        return false
    }
}