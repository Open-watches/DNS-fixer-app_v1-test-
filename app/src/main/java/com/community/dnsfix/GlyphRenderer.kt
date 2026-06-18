package com.community.dnsfix.handwriting

import android.graphics.*
import kotlin.math.*
import java.util.Random

class GlyphRenderer(
    private val baseInkColor: Int,
    private val fontSize: Float
) {
    private val slicePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textSize = fontSize
    }

    private val scratchBuffer = ThreadLocal.withInitial { ScratchBitmap(600, 300) }
    private val complexBuffer = ThreadLocal.withInitial { ScratchBitmap(100, 100) }
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

        val rand = Random((wp.seed and 0x7FFFFFFF).toLong())
        val alpha = (150 + t.pressure * 105).toInt().coerceIn(40, 255)
        val (r, g, b) = washColor(baseInkColor, rand)

        textPaint.color = Color.argb(alpha, r, g, b)

        canvas.save()
        val matrix = Matrix().apply {
            val skewX = -tan(Math.toRadians((globalSlant + t.slantOffset).toDouble())).toFloat()
            setSkew(skewX, 0f)
            postRotate(t.rotation)
            postScale(t.scaleX, t.scaleY)
            postTranslate(x + t.dx, y + baselineDrift + t.dy)
        }
        canvas.concat(matrix)

        if (wp.isPartOfMyanmarWord) {
            drawComplexWordMesh(canvas, wp, alpha, r, g, b, typeface)
        } else {
            drawTextMesh(canvas, wp.text, wp.seed, alpha, r, g, b)
        }

        canvas.restore()
    }

    private fun drawTextMesh(
        canvas: Canvas,
        text: String,
        seed: Int,
        alpha: Int,
        washR: Int,
        washG: Int,
        washB: Int
    ) {
        val path = Path()
        textPaint.getTextPath(text, 0, text.length, 0f, 0f, path)

        val warped = PathWarper.warpWithGrid(path, 0.8f, seed, text.length, RectF())
        val warpedBounds = RectF()
        warped.computeBounds(warpedBounds, true)
        if (warpedBounds.width() <= 0f || warpedBounds.height() <= 0f) return

        val margin = 4f
        val bmpWidth = ceil(warpedBounds.width() + margin * 2).toInt()
        val bmpHeight = ceil(warpedBounds.height() + margin * 2).toInt()

        val buf = scratchBuffer.get()
        buf.ensureSize(bmpWidth, bmpHeight)
        buf.clear()

        buf.canvas.save()
        buf.canvas.translate(-warpedBounds.left + margin, -warpedBounds.top + margin)
        drawVariableStrokePath(buf.canvas, warped, seed, alpha, washR, washG, washB)
        buf.canvas.restore()

        val meshWidth = 10
        val meshHeight = (bmpHeight / 2).coerceAtLeast(2)
        val verts = buildClampedSineWarpVertices(bmpWidth, bmpHeight, meshWidth, meshHeight, seed)

        canvas.drawBitmapMesh(buf.bitmap, meshWidth, meshHeight, verts, 0, null, 0, slicePaint)
    }

    private fun drawComplexWordMesh(
        canvas: Canvas,
        wp: WordPlacement,
        alpha: Int,
        washR: Int,
        washG: Int,
        washB: Int,
        typeface: Typeface?
    ) {
        val clusters = Tokenizer.splitMyanmarClusters(wp.text)
        if (clusters.isEmpty()) return

        val paint = Paint(textPaint).apply { this.typeface = typeface ?: Typeface.DEFAULT }
        val fm = paint.fontMetrics
        val baselineY = -fm.ascent

        val rand = Random(wp.seed.toLong())
        data class ClusterLayout(
            val text: String,
            val sx: Float, val sy: Float,
            val warpedPath: Path,
            val width: Float, val height: Float,
            val offsetX: Float, val offsetY: Float
        )
        val layouts = mutableListOf<ClusterLayout>()
        var totalWidth = 0f
        var maxHeight = 0f

        for (cluster in clusters) {
            val sx = (0.8f + rand.nextFloat() * 0.4f).coerceIn(0.8f, 1.2f)
            val sy = (0.8f + rand.nextFloat() * 0.35f).coerceIn(0.8f, 1.15f)

            val clusterPath = Path()
            paint.getTextPath(cluster, 0, cluster.length, 0f, 0f, clusterPath)
            val warped = PathWarper.warpWithGrid(
                clusterPath, 0.6f, wp.seed + totalWidth.toInt(), cluster.length, RectF()
            )
            val b = RectF()
            warped.computeBounds(b, true)

            val w = b.width() * sx
            val h = b.height() * sy
            layouts.add(ClusterLayout(cluster, sx, sy, warped, w, h, b.left, b.top))
            totalWidth += w + 2f
            maxHeight = maxOf(maxHeight, h)
        }

        val padding = ceil(fontSize * 0.5f).toInt()
        val bmpWidth = ceil(totalWidth + 2 * padding).toInt()
        val bmpHeight = ceil(maxHeight + fm.descent - fm.ascent + 2 * padding).toInt()

        val buf = complexBuffer.get()
        buf.ensureSize(bmpWidth, bmpHeight)
        buf.clear()

        var cursorX = padding.toFloat()
        for (layout in layouts) {
            buf.canvas.save()
            buf.canvas.translate(cursorX - layout.offsetX, baselineY - layout.offsetY + padding)
            buf.canvas.scale(layout.sx, layout.sy)
            drawVariableStrokePath(buf.canvas, layout.warpedPath, wp.seed + cursorX.toInt(), alpha, washR, washG, washB)
            buf.canvas.restore()
            cursorX += layout.width + 2f
        }

        val meshWidth = 10
        val meshHeight = (bmpHeight / 2).coerceAtLeast(2)
        val verts = buildClampedSineWarpVertices(bmpWidth, bmpHeight, meshWidth, meshHeight, wp.seed)

        canvas.drawBitmapMesh(buf.bitmap, meshWidth, meshHeight, verts, 0, null, 0, slicePaint)
    }

    private fun drawVariableStrokePath(
        canvas: Canvas,
        path: Path,
        seed: Int,
        alpha: Int,
        washR: Int,
        washG: Int,
        washB: Int
    ) {
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(alpha, washR, washG, washB)
        }

        pathMeasure.setPath(path, false)
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

    private fun buildClampedSineWarpVertices(w: Int, h: Int, meshWidth: Int, meshHeight: Int, seed: Int): FloatArray {
        val rand = Random(seed.toLong())
        val waveSeedX = rand.nextFloat() * 10f
        val freqX = 0.22f + rand.nextFloat() * 0.12f
        val ampX = 0.45f + rand.nextFloat() * 0.45f

        val cols = meshWidth + 1
        val rows = meshHeight + 1
        val verts = FloatArray(cols * rows * 2)

        val maxOffsetX = w * 0.04f
        val maxOffsetY = h * 0.04f

        for (row in 0 until rows) {
            val y = row.toFloat() / meshHeight * h
            val sineOffset = sin(y * freqX + waveSeedX) * ampX

            for (col in 0 until cols) {
                val x = col.toFloat() / meshWidth * w
                val idx = (row * cols + col) * 2

                val noiseX = (rand.nextFloat() - 0.5f) * 0.15f
                val noiseY = (rand.nextFloat() - 0.5f) * 0.2f

                val offsetX = (sineOffset + noiseX).coerceIn(-maxOffsetX, maxOffsetX)
                val offsetY = noiseY.coerceIn(-maxOffsetY, maxOffsetY)

                verts[idx]     = (x + offsetX).coerceIn(-2f, w + 2f)
                verts[idx + 1] = (y + offsetY).coerceIn(-2f, h + 2f)
            }
        }
        return verts
    }

    private fun washColor(base: Int, rand: Random): Triple<Int, Int, Int> {
        val r = (Color.red(base) + PenState.gaussian(0f, 8f, rand)).toInt().coerceIn(0, 255)
        val g = (Color.green(base) + PenState.gaussian(0f, 8f, rand)).toInt().coerceIn(0, 255)
        val b = (Color.blue(base) + PenState.gaussian(0f, 8f, rand)).toInt().coerceIn(0, 255)
        return Triple(r, g, b)
    }
}