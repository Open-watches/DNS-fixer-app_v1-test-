package com.community.dnsfix.handwriting

import android.graphics.*
import kotlin.math.*
import java.util.Random

class GlyphRenderer(
    private val baseInkColor: Int,
    private val fontSize: Float
) {
    // Shared immutable paints
    private val slicePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textSize = fontSize
    }

    // Thread‑safe scratch buffers
    private val scratchBuffer = ThreadLocal.withInitial { ScratchBitmap(600, 300) }
    private val complexBuffer = ThreadLocal.withInitial { ScratchBitmap(100, 100) }

    // Reusable PathMeasure to avoid GC (1 allocation per renderer)
    private val pathMeasure = PathMeasure()

    private class ScratchBitmap(width: Int, height: Int) {
        var bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        var canvas = Canvas(bitmap)

        fun ensureSize(minWidth: Int, minHeight: Int) {
            if (bitmap.width < minWidth || bitmap.height < minHeight) {
                bitmap.recycle()
                bitmap = Bitmap.createBitmap(minWidth, minHeight, Bitmap.Config.ARGB_8888)
                canvas = Canvas(bitmap)
            }
        }

        fun clear() {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        }
    }

    /**
     * Draws a single word at the given canvas position, with all natural‑handwriting transforms.
     */
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

        // Per‑word ink colour wash
        val rand = Random((wp.seed and 0x7FFFFFFF).toLong())
        val alpha = (150 + t.pressure * 105).toInt().coerceIn(40, 255)
        val (washR, washG, washB) = washColor(baseInkColor, rand)

        textPaint.color = Color.argb(alpha, washR, washG, washB)

        canvas.save()
        val matrix = Matrix().apply {
            val skewX = -tan(Math.toRadians((globalSlant + t.slantOffset).toDouble())).toFloat()
            setSkew(skewX, 0f)
            postRotate(t.rotation)
            postScale(t.scaleX, t.scaleY)
            postTranslate(x + t.dx, y + baselineDrift + t.dy)
        }
        canvas.concat(matrix)

        if (wp.text.any { it in '\u1000'..'\u109F' }) {
            drawComplexWordMesh(canvas, wp, alpha, washR, washG, washB, typeface)
        } else {
            drawTextMesh(canvas, wp.text, wp.seed, alpha, washR, washG, washB)
        }

        canvas.restore()
    }

    // -----------------------------------------------------------------------
    //  Simple text: render to scratch bitmap, then draw with mesh deformation
    // -----------------------------------------------------------------------

    private fun drawTextMesh(
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

        val padding = ceil(fontSize * 1.6f).toInt()
        val textX = padding - bounds.left.toFloat()
        val textY = padding - bounds.top.toFloat()

        val buf = scratchBuffer.get()
        buf.ensureSize(bounds.width() + padding * 2, bounds.height() + padding * 2)
        buf.clear()

        // Render warped path to scratch bitmap
        val path = Path()
        textPaint.getTextPath(text, 0, text.length, textX, textY, path)
        val pathBounds = RectF()
        path.computeBounds(pathBounds, true)

        val warped = PathWarper.warpWithGrid(path, 1.5f, seed, text.length, pathBounds)
        drawVariableStrokePath(buf.canvas, warped, seed, alpha, washR, washG, washB)

        // Compute mesh vertices for the entire bitmap (replaces slice loop)
        val meshWidth = 10  // number of columns in mesh
        val meshHeight = (buf.bitmap.height / 2).coerceAtLeast(2)  // ~2px row spacing
        val verts = buildSineWarpVertices(buf.bitmap.width, buf.bitmap.height, meshWidth, meshHeight, seed)

        canvas.drawBitmapMesh(buf.bitmap, meshWidth, meshHeight, verts, 0, null, 0, slicePaint)
    }

    // -----------------------------------------------------------------------
    //  Complex (Myanmar) text: render per‑cluster warped clusters, then mesh
    // -----------------------------------------------------------------------

    private fun drawComplexWordMesh(
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
        val baselineY = (padding - fm.ascent).toFloat()

        val rand = Random(wp.seed.toLong())

        // Layout each cluster with random scale and measure total size
        data class ClusterLayout(val text: String, val sx: Float, val sy: Float, val width: Float)
        val layouts = mutableListOf<ClusterLayout>()
        var totalWidth = 0f
        var maxHeight = 0f

        for (cluster in clusters) {
            val sx = (0.8f + rand.nextFloat() * 0.4f).coerceIn(0.8f, 1.2f)
            val sy = (0.8f + rand.nextFloat() * 0.35f).coerceIn(0.8f, 1.15f)
            val origWidth = paint.measureText(cluster)
            val scaledWidth = origWidth * sx
            layouts.add(ClusterLayout(cluster, sx, sy, scaledWidth))
            totalWidth += scaledWidth + 2f
            maxHeight = maxOf(maxHeight, (fm.descent - fm.ascent) * sy)
        }

        val buf = complexBuffer.get()
        buf.ensureSize(ceil(totalWidth + 2 * padding).toInt(), ceil(maxHeight + 2 * padding).toInt())
        buf.clear()

        // Draw each cluster onto the complex bitmap
        var cursorX = padding.toFloat()
        for (layout in layouts) {
            buf.canvas.save()
            buf.canvas.translate(cursorX, baselineY)
            buf.canvas.scale(layout.sx, layout.sy)

            val clusterPath = Path()
            paint.getTextPath(layout.text, 0, layout.text.length, 0f, 0f, clusterPath)
            val clusterBounds = RectF()
            clusterPath.computeBounds(clusterBounds, true)

            val clusterSeed = wp.seed + cursorX.toInt()
            val warped = PathWarper.warpWithGrid(clusterPath, 1.2f, clusterSeed, layout.text.length, clusterBounds)
            drawVariableStrokePath(buf.canvas, warped, clusterSeed, alpha, washR, washG, washB)

            buf.canvas.restore()
            cursorX += layout.width + 2f
        }

        // Mesh‑based deformation for the whole word bitmap
        val meshWidth = 10
        val meshHeight = (buf.bitmap.height / 2).coerceAtLeast(2)
        val verts = buildSineWarpVertices(buf.bitmap.width, buf.bitmap.height, meshWidth, meshHeight, wp.seed)

        canvas.drawBitmapMesh(buf.bitmap, meshWidth, meshHeight, verts, 0, null, 0, slicePaint)
    }

    // -----------------------------------------------------------------------
    //  Variable‑stroke rendering (GC‑optimised)
    // -----------------------------------------------------------------------

    private fun drawVariableStrokePath(
        canvas: Canvas,
        path: Path,
        seed: Int,
        alpha: Int,
        washR: Float,
        washG: Float,
        washB: Float
    ) {
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(alpha, washR, washG, washB)
        }

        pathMeasure.setPath(path, false)   // reuse the instance

        val pos = FloatArray(2)
        val tan = FloatArray(2)
        val pen = PenState()
        pen.update(false, false, Random(seed.toLong()))

        do {
            val length = pathMeasure.length
            if (length == 0f) continue

            val numPoints = ceil(length / 1.2f).toInt().coerceAtLeast(2)
            var prevAngle = 0f

            for (i in 0..numPoints) {
                val distance = (i.toFloat() / numPoints) * length
                pathMeasure.getPosTan(distance, pos, tan)

                val curAngle = if (tan[0] != 0f || tan[1] != 0f) atan2(tan[1], tan[0]) else prevAngle
                var delta = abs(curAngle - prevAngle)
                if (delta > PI.toFloat()) delta = (2 * PI).toFloat() - delta

                val curvature = (delta / (PI.toFloat() * 0.5f)).coerceIn(0f, 1f)
                val strokeW = pen.calculateStrokeWidth(1f - curvature, curvature)

                val progress = i.toFloat() / numPoints
                val taper = when {
                    progress < 0.15f -> progress / 0.15f
                    progress > 0.85f -> (1f - progress) / 0.15f
                    else -> 1f
                }

                val radius = (strokeW * 0.5f * taper).coerceAtLeast(0.2f)
                canvas.drawCircle(pos[0], pos[1], radius, strokePaint)
                prevAngle = curAngle
            }
        } while (pathMeasure.nextContour())
    }

    // -----------------------------------------------------------------------
    //  Mesh deformation builder (replaces slice‑warp loop)
    // -----------------------------------------------------------------------

    /**
     * Computes vertex positions for a `drawBitmapMesh` that applies the same
     * sine‑wave + random jitter as the old per‑slice loop.
     *
     * @param w  bitmap width (in pixels)
     * @param h  bitmap height
     * @param meshWidth  number of horizontal subdivisions
     * @param meshHeight number of vertical subdivisions
     * @param seed  random seed for wave parameters
     * @return float array of size (meshWidth+1)*(meshHeight+1)*2, alternating x,y
     */
    private fun buildSineWarpVertices(
        w: Int, h: Int,
        meshWidth: Int, meshHeight: Int,
        seed: Int
    ): FloatArray {
        val rand = Random(seed.toLong())
        val waveSeedX = rand.nextFloat() * 10f
        val freqX = 0.22f + rand.nextFloat() * 0.12f
        val ampX = 0.45f + rand.nextFloat() * 0.45f

        val cols = meshWidth + 1
        val rows = meshHeight + 1
        val verts = FloatArray(cols * rows * 2)

        for (row in 0 until rows) {
            val y = row.toFloat() / meshHeight * h  // original y in bitmap
            val relY = y - 0f  // we can also track relative to baseline, but it works globally

            val sineOffset = sin(y * freqX + waveSeedX) * ampX
            for (col in 0 until cols) {
                val x = col.toFloat() / meshWidth * w
                val idx = (row * cols + col) * 2
                // Add sine deformation + tiny per‑vertex noise (same flavour as old code)
                val noiseX = (rand.nextFloat() - 0.5f) * 0.15f
                val noiseY = (rand.nextFloat() - 0.5f) * 0.2f
                verts[idx] = x + sineOffset + noiseX
                verts[idx + 1] = y + noiseY
            }
        }
        return verts
    }

    // -----------------------------------------------------------------------
    //  Colour wash helper
    // -----------------------------------------------------------------------

    private fun washColor(base: Int, rand: Random): Triple<Int, Int, Int> {
        val r = (Color.red(base) + PenState.gaussian(0f, 8f, rand)).coerceIn(0f, 255f)
        val g = (Color.green(base) + PenState.gaussian(0f, 8f, rand)).coerceIn(0f, 255f)
        val b = (Color.blue(base) + PenState.gaussian(0f, 8f, rand)).coerceIn(0f, 255f)
        return Triple(r.toInt(), g.toInt(), b.toInt())
    }
}