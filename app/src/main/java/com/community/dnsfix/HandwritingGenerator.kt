package com.community.dnsfix.handwriting

import android.content.Context
import android.graphics.*
import java.io.File
import java.io.FileOutputStream
import java.util.Random
import kotlin.math.sin

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
    private val globalRandom = Random()
    private val pen = PenState()
    private var baselineDrift = 0f
    private val driftStep = 0.6f
    private val driftClamp = 6.0f
    private var lineIndex = 0

    // FIXED: Constructor matches the single-argument layout engine signature
    private val layoutEngine = LayoutEngine(
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = fontSize
            typeface = customTypeface ?: Typeface.DEFAULT
        }
    )
    private val glyphRenderer = GlyphRenderer(baseInkColor, fontSize)

    // ---------- Error logging ----------
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

    // ---------- Public API ----------
    
    // FIXED: Overloaded signatures accept metadata strings to prevent MainActivity compilation crashes
    fun generateBitmap(text: String, pageNumber: String = "", dateText: String = ""): Bitmap = 
        generateBitmap(text, width, height, pageNumber, dateText)

    fun generateBitmap(
        text: String, 
        canvasWidth: Int, 
        canvasHeight: Int, 
        pageNumber: String = "", 
        dateText: String = ""
    ): Bitmap {
        return try {
            lastErrorMessage = null
            lastErrorStackTrace = null
            realGenerateBitmap(text, canvasWidth, canvasHeight, pageNumber, dateText)
        } catch (e: Exception) {
            logError("Generation failed", e)
            createErrorBitmap(canvasWidth, canvasHeight, e)
        }
    }

    private fun realGenerateBitmap(text: String, w: Int, h: Int, pageNumber: String, dateText: String): Bitmap {
        pen.pressure = 0.88f; pen.slantOffset = 0f; pen.tremor = 1.0f; pen.fatigue = 0f
        pen.xDrift = 0f; pen.yDrift = 0f; pen.spacingBias = 0f; pen.errorAccumulation = 0f
        baselineDrift = 0f
        lineIndex = 0

        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        
        // Define clean top/bottom header structures for metadata rendering
        val marginTop = 180f
        val marginBottom = 160f
        
        PaperRenderer(w, h).draw(canvas)
        
        // FIXED: Invocation fields match the updated PaperRenderer boundary specifications
        PaperRenderer.drawLinesAndMargin(
            canvas = canvas, 
            width = w.toFloat(), 
            height = h.toFloat(), 
            marginTop = marginTop, 
            marginBottom = marginBottom, 
            marginLeft = marginLeft, 
            lineSpacing = lineSpacing
        )
        
        // Render out-of-bounds metadata blocks securely inside the unruled header region
        val metadataPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = baseInkColor
            textSize = fontSize * 0.85f
            typeface = customTypeface ?: Typeface.DEFAULT
            alpha = 180
        }
        
        if (dateText.isNotBlank()) {
            canvas.drawText(dateText, w.toFloat() - marginRight - 260f, marginTop - 40f, metadataPaint)
        }
        if (pageNumber.isNotBlank()) {
            canvas.drawText("No. $pageNumber", w.toFloat() - marginRight - 120f, marginTop - 90f, metadataPaint)
        }

        if (text.isNotEmpty()) {
            layoutAndDraw(canvas, text, w, marginTop, marginBottom)
        }
        return bitmap
    }

    private fun layoutAndDraw(canvas: Canvas, text: String, pageWidth: Int, marginTop: Float, marginBottom: Float) {
        val tokens = Tokenizer.tokenize(text)
        
        // FIXED: Compute dynamic wrapping boundaries to pass directly down to the engine
        val wrapWidth = pageWidth.toFloat() - marginLeft - marginRight
        val placements = layoutEngine.computePlacements(tokens, pen, wrapWidth, globalRandom)
        if (placements.isEmpty()) return

        // Text rendering coordinates safely skip past the top header block
        var y = marginTop + lineSpacing * 1.5f
        val maxDrawY = height - marginBottom

        for (line in placements) {
            if (y > maxDrawY) break // Safeguard page bounds overflow
            
            if (line.isEmpty()) {
                y += lineSpacing * (0.9f + globalRandom.nextFloat() * 0.2f)
                baselineDrift += PenState.gaussian(0f, driftStep, globalRandom).coerceIn(-driftClamp, driftClamp)
                pen.update(rest = true, isMyanmar = false, random = globalRandom)
                lineIndex++
                continue
            }
            // Baseline sine wander
            val sineDrift = sin(lineIndex * 0.5f) * 2.0f
            baselineDrift = (baselineDrift + PenState.gaussian(0f, driftStep, globalRandom) + sineDrift)
                .coerceIn(-driftClamp, driftClamp)

            var x = marginLeft + 20f + PenState.gaussian(0f, 2.5f, globalRandom)
            for ((index, wp) in line.withIndex()) {
                val isMyanmar = wp.text.any { it in '\u1000'..'\u109F' }
                glyphRenderer.drawWord(canvas, wp, x, y, baselineDrift, globalSlant, customTypeface)
                x += wp.estimatedWidth
                if (index < line.size - 1) {
                    val nextWp = line[index + 1]
                    if (!(wp.isPartOfMyanmarWord && nextWp.isPartOfMyanmarWord)) {
                        x += 6f + PenState.gaussian(0f, 2.5f, globalRandom).coerceIn(-4f, 4f)
                    }
                }
                pen.update(rest = false, isMyanmar = isMyanmar, random = globalRandom)
            }
            y += lineSpacing * (0.9f + globalRandom.nextFloat() * 0.2f)
            lineIndex++
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
