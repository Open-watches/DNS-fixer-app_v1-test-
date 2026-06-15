package com.community.dnsfix.handwriting

import android.graphics.*
import kotlin.math.*
import java.util.Random

class GlyphRenderer(
    private val baseInkColor: Int,
    private val fontSize: Float
) {
    // Paint that forces bilinear filtering on bitmap draws
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
        val baseBlue = Color.blue(baseInkColor)        val alpha = (150 + t.pressure * 105).toInt().coerceIn(40, 255)

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
            drawComplexWordWithSliceWarp(canvas, wp, alpha, washR, washG, washB, typeface)
        } else {
            drawTextWithSliceWarp(canvas, wp.text, wp.seed, alpha, washR, washG, washB)
        }
        canvas.restore()
    }

    /**
     * NEW: Renders a Path using overlapping circles with variable stroke width.
     * This simulates real pen pressure and speed variation.
     */
    private fun drawPathWithVariableStrokes(
        canvas: Canvas,
        path: Path,
        seed: Int,
        penState: PenState,
        alpha: Int,
        washR: Float,
        washG: Float,
        washB: Float
    ) {
        val pm = PathMeasure(path, false)
        val pos = FloatArray(2)
        val tan = FloatArray(2)
        
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL            color = Color.argb(alpha, washR.toInt(), washG.toInt(), washB.toInt())
            // Optional: slight blur for ink bleed into paper
            maskFilter = BlurMaskFilter(0.6f, BlurMaskFilter.Blur.NORMAL) 
        }
        
        do {
            val length = pm.length
            if (length == 0f) continue
            
            // High resolution sampling for smooth circles
            val stepSize = 1.2f 
            val numPoints = ceil(length / stepSize).toInt().coerceAtLeast(2)
            
            var prevAngle = 0f
            
            for (i in 0..numPoints) {
                val distance = (i.toFloat() / numPoints) * length
                pm.getPosTan(distance, pos, tan)
                
                val currentX = pos[0]
                val currentY = pos[1]
                
                // Calculate curvature (change in angle) to simulate speed
                // Writers slow down on tight curves, making the ink thicker
                val currentAngle = if (tan[0] != 0f || tan[1] != 0f) atan2(tan[1], tan[0]) else prevAngle
                var angleDelta = abs(currentAngle - prevAngle)
                if (angleDelta > PI.toFloat()) angleDelta = (2 * PI).toFloat() - angleDelta
                
                // Map 90-degree turn to 1.0 curvature
                val curvature = (angleDelta / (PI.toFloat() * 0.5f)).coerceIn(0f, 1f)
                
                // Proxy for speed: straight lines = fast (thin), curves = slow (thick)
                val normalizedSpeed = 1f - curvature 
                
                // Get variable stroke width from PenState
                val strokeWidth = penState.calculateStrokeWidth(normalizedSpeed, curvature)
                
                // Tapering: thin out the start and end of every stroke contour
                val progress = i.toFloat() / numPoints
                var taperMultiplier = 1f
                if (progress < 0.15f) taperMultiplier = progress / 0.15f
                else if (progress > 0.85f) taperMultiplier = (1f - progress) / 0.15f
                
                val finalRadius = (strokeWidth * 0.5f * taperMultiplier).coerceAtLeast(0.2f)
                
                // Draw the overlapping circle
                canvas.drawCircle(currentX, currentY, finalRadius, strokePaint)
                
                prevAngle = currentAngle
            }        } while (pm.nextContour())
    }

    /**
     * UPDATED: Now extracts text as a Path, warps it, and draws with variable strokes.
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
        val bounds = Rect()
        textPaint.getTextBounds(text, 0, text.length, bounds)
        if (bounds.width() <= 0 || bounds.height() <= 0) return

        val wordWidth = bounds.width()
        val wordHeight = bounds.height()
        val padding = ceil(fontSize * 1.6f).toInt()

        ensureScratchSize(wordWidth + padding * 2, wordHeight + padding * 2)
        scratchCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        val textX = padding.toFloat() - bounds.left
        val textY = padding.toFloat() - bounds.top

        // --- NEW PATH RENDERING PIPELINE ---
        val textPath = Path()
        textPaint.getTextPath(text, 0, text.length, textX, textY, textPath)
        
        val boundsF = RectF()
        textPath.computeBounds(boundsF, true)
        
        // Apply your existing grid warper to the actual geometry
        val warpedPath = PathWarper.warpWithGrid(textPath, 1.5f, seed, text.length, boundsF)
        
        // Draw with variable strokes instead of flat text
        val penState = PenState()
        penState.update(false, false, Random(seed.toLong()))
        drawPathWithVariableStrokes(scratchCanvas, warpedPath, seed, penState, alpha, washR, washG, washB)
        // -------------------------------------

        // Slice the scratch bitmap with sine-wave offset (unchanged)
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
                slicePaint
            )
        }
        canvas.restore()
    }

    /**
     * UPDATED: Now extracts clusters as Paths, warps them, and draws with variable strokes.
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
        val padding = ceil(fontSize * 1.0f).toInt()
        val baselineY = padding - fm.ascent

        val clusterRand = Random(wp.seed.toLong())
        var totalWidth = 0f
        var maxHeight = 0f
        data class ClusterLayout(val text: String, val sx: Float, val sy: Float, val width: Float)
        val layouts = mutableListOf<ClusterLayout>()

        for (cluster in clusters) {
            val sx = (0.8f + clusterRand.nextFloat() * 0.4f).coerceIn(0.8f, 1.2f)
            val sy = (0.8f + clusterRand.nextFloat() * 0.35f).coerceIn(0.8f, 1.15f)

            val origWidth = paint.measureText(cluster)
            val scaledWidth = origWidth * sx
            layouts.add(ClusterLayout(cluster, sx, sy, scaledWidth))

            totalWidth += scaledWidth + 2f
            val clusterHeight = (fm.descent - fm.ascent) * sy
            if (clusterHeight > maxHeight) maxHeight = clusterHeight
        }

        if (layouts.isEmpty()) return

        val bitmapWidth = ceil(totalWidth + 2 * padding).toInt()
        val bitmapHeight = ceil(maxHeight + 2 * padding).toInt()
        val wordBitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        val wordCanvas = Canvas(wordBitmap)

        var cursorX = padding.toFloat()
        for (layout in layouts) {
            wordCanvas.save()
            wordCanvas.translate(cursorX, baselineY)
            wordCanvas.scale(layout.sx, layout.sy)
            
            // --- NEW PATH RENDERING PIPELINE ---
            val clusterPath = Path()
            paint.getTextPath(layout.text, 0, layout.text.length, 0f, 0f, clusterPath)
            
            val clusterBounds = RectF()
            clusterPath.computeBounds(clusterBounds, true)
            
            val clusterSeed = wp.seed + cursorX.toInt()
            val warpedClusterPath = PathWarper.warpWithGrid(clusterPath, 1.2f, clusterSeed, layout.text.length, clusterBounds)
            
            val penState = PenState()
            penState.update(false, true, Random(clusterSeed.toLong()))
            drawPathWithVariableStrokes(wordCanvas, warpedClusterPath, clusterSeed, penState, alpha, washR, washG, washB)
            // -------------------------------------
            
            wordCanvas.restore()
            cursorX += layout.width + 2f
        }

        // Apply slice warp to the assembled word bitmap (unchanged)
        val sliceHeight = 2        val rand = Random(wp.seed.toLong())
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