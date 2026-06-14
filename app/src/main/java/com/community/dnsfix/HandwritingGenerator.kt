package com.community.dnsfix.handwriting

import android.content.Context
import android.graphics.*
import java.io.File
import java.io.FileOutputStream
import java.util.Random

class HandwritingGenerator(
    private val width: Int = 1000,
    private val height: Int = 1200,
    private val context: Context? = null,
    private val baseInkColor: Int = Color.rgb(0, 51, 153),
    private val lineSpacing: Float = 72f,
    private val customTypeface: Typeface? = null
) {
    private val fontSize = (lineSpacing * 0.58f).coerceIn(40f, 48f)
    private val marginLeft = 100f
    private val marginRight = 80f
    private val globalSlant = 7f
    private val globalRandom = java.util.Random()
    private val pen = PenState()
    private var baselineDrift = 0f
    private val driftStep = 0.4f
    private val driftClamp = 5.0f

    private val layoutEngine = LayoutEngine(
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = fontSize
            typeface = customTypeface ?: Typeface.DEFAULT
        },
        width.toFloat(),
        marginRight
    )
    private val glyphRenderer = GlyphRenderer(baseInkColor)
    private val paperRenderer = PaperRenderer(width, height)

    // Error logging (unchanged)
    private var lastErrorMessage: String? = null
    private var lastErrorStackTrace: String? = null
    private fun logError(msg: String, e: Exception?) {
        lastErrorMessage = msg + (e?.let { ": ${it.message}" } ?: "")
        lastErrorStackTrace = e?.stackTraceToString() ?: "No stack trace"
        context?.let {
            try {
                val crashDir = File(it.cacheDir, "handwriting_logs")
                if (!crashDir.exists()) crashDir.mkdirs()
                val file = File(crashDir, "error_${System.currentTimeMillis()}.txt")
                FileOutputStream(file).use { fos ->
                    fos.write("$msg\n".toByteArray())
                    fos.write(lastErrorStackTrace!!.toByteArray())
                }
            } catch (_: Exception) {}
        }
    }
    fun getLastError(): Pair<String?, String?> = Pair(lastErrorMessage, lastErrorStackTrace)

    fun generateBitmap(text: String): Bitmap = generateBitmap(text, width, height)

    fun generateBitmap(text: String, canvasWidth: Int, canvasHeight: Int): Bitmap {
        return try {
            lastErrorMessage = null
            lastErrorStackTrace = null
            realGenerateBitmap(text, canvasWidth, canvasHeight)
        } catch (e: Exception) {
            logError("Generation failed", e)
            createErrorBitmap(canvasWidth, canvasHeight, e)
        }
    }

    private fun realGenerateBitmap(text: String, w: Int, h: Int): Bitmap {
        pen.pressure = 0.7f; pen.slantOffset = 0f; pen.tremor = 1.0f; pen.fatigue = 0f
        baselineDrift = 0f

        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        paperRenderer.draw(canvas)
        PaperRenderer.drawLinesAndMargin(canvas, lineSpacing, marginLeft, w.toFloat(), h.toFloat())

        if (text.isNotEmpty()) layoutAndDraw(canvas, text, w)
        return bitmap
    }

    private fun layoutAndDraw(canvas: Canvas, text: String, pageWidth: Int) {
        val tokens = Tokenizer.tokenize(text)
        val placements = layoutEngine.computePlacements(tokens, pen, globalRandom)
        if (placements.isEmpty()) return

        var y = lineSpacing * 1.8f
        for (line in placements) {
            if (line.isEmpty()) {
                y += lineSpacing * (0.9f + globalRandom.nextFloat() * 0.2f)
                baselineDrift += PenState.gaussian(0f, driftStep, globalRandom).coerceIn(-driftClamp, driftClamp)
                pen.update(rest = true, random = globalRandom)
                continue
            }
            var x = marginLeft + 20f + PenState.gaussian(0f, 2.5f, globalRandom)
            for ((index, wp) in line.withIndex()) {
                glyphRenderer.drawWord(canvas, wp, x, y, baselineDrift, globalSlant, customTypeface)
                x += wp.estimatedWidth
                if (index < line.size - 1) {
                    val nextWp = line[index + 1]
                    if (!(wp.isPartOfMyanmarWord && nextWp.isPartOfMyanmarWord)) {
                        x += 6f + PenState.gaussian(0f, 2.5f, globalRandom).coerceIn(-4f, 4f)
                    }
                }
                pen.update(rest = false, random = globalRandom)
            }
            y += lineSpacing * (0.9f + globalRandom.nextFloat() * 0.2f)
            baselineDrift += PenState.gaussian(0f, driftStep, globalRandom).coerceIn(-driftClamp, driftClamp)
            pen.update(rest = true, random = globalRandom)
        }
    }

    private fun createErrorBitmap(w: Int, h: Int, e: Exception): Bitmap {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED; textSize = 28f }
        canvas.drawText("Generation failed: ${e.message}", 30f, 100f, paint)
        canvas.drawText("Check notification for details", 30f, 140f, paint)
        return bitmap
    }
}