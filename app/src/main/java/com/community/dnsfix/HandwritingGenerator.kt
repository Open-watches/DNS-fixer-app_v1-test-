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
    private val marginLeft = 140f 
    private val marginRight = 80f
    private val globalSlant = 7f
    private val globalRandom = Random()
    private val pen = PenState()
    private var baselineDrift = 0f
    private val driftStep = 0.6f
    private val driftClamp = 6.0f
    private var lineIndex = 0

    // Human Error Simulation Configurations (2.5% natural mistake probability)
    private val mistakeProbability = 0.025f 

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

    /**
     * Data structure holding explicit placement configuration parameters 
     * to write text anywhere on the canvas via selection coordinates.
     */
    data class AbsoluteTextChunk(
        val text: String,
        val x: Float,
        val y: Float,
        val forceToMarginGutter: Boolean = false
    )

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

    // ---------- Mode 1: Standard Flow Paper API ----------
    
    fun generateBitmap(
        text: String, 
        pageNumber: String = "", 
        dateText: String = "",
        autoMarginNumbers: Boolean = false // Numbers stay normally inline unless explicitly toggled true
    ): Bitmap = generateBitmap(text, width, height, pageNumber, dateText, autoMarginNumbers)

    fun generateBitmap(
        text: String, 
        canvasWidth: Int, 
        canvasHeight: Int, 
        pageNumber: String = "", 
        dateText: String = "",
        autoMarginNumbers: Boolean = false
    ): Bitmap {
        return try {
            lastErrorMessage = null
            lastErrorStackTrace = null
            resetPenState()

            val bitmap = Bitmap.createBitmap(canvasWidth, canvasHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            
            val marginTop = 180f
            val marginBottom = 160f
            
            drawPaperBackground(canvas, canvasWidth, canvasHeight, marginTop, marginBottom)
            drawHeaders(canvas, canvasWidth, marginTop, pageNumber, dateText)

            if (text.isNotEmpty()) {
                layoutAndDrawSequential(canvas, text, canvasWidth, marginTop, marginBottom, autoMarginNumbers)
            }
            bitmap
        } catch (e: Exception) {
            logError("Generation failed", e)
            createErrorBitmap(canvasWidth, canvasHeight, e)
        }
    }

    // ---------- Mode 2: Absolute Position API (Write Anywhere Control) ----------
    
    fun generateBitmapAtCoordinates(
        chunks: List<AbsoluteTextChunk>,
        pageNumber: String = "",
        dateText: String = ""
    ): Bitmap {
        return try {
            lastErrorMessage = null
            lastErrorStackTrace = null
            resetPenState()

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            
            val marginTop = 180f
            val marginBottom = 160f

            drawPaperBackground(canvas, width, height, marginTop, marginBottom)
            drawHeaders(canvas, width, marginTop, pageNumber, dateText)

            // Render every explicit text fragment onto its chosen coordinate target selection
            for (chunk in chunks) {
                renderSingleCoordinateChunk(canvas, chunk)
            }

            bitmap
        } catch (e: Exception) {
            logError("Coordinate generation failed", e)
            createErrorBitmap(width, height, e)
        }
    }

    private fun resetPenState() {
        pen.pressure = 0.88f; pen.slantOffset = 0f; pen.tremor = 1.0f; pen.fatigue = 0f
        pen.xDrift = 0f; pen.yDrift = 0f; pen.spacingBias = 0f; pen.errorAccumulation = 0f
        baselineDrift = 0f
        lineIndex = 0
    }

    private fun drawPaperBackground(canvas: Canvas, w: Int, h: Int, marginTop: Float, marginBottom: Float) {
        PaperRenderer(w, h).draw(canvas)
        PaperRenderer.drawLinesAndMargin(
            canvas = canvas, 
            width = w.toFloat(), 
            height = h.toFloat(), 
            marginTop = marginTop, 
            marginBottom = marginBottom, 
            marginLeft = marginLeft, 
            lineSpacing = lineSpacing
        )
    }

    private fun drawHeaders(canvas: Canvas, w: Int, marginTop: Float, pageNumber: String, dateText: String) {
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
    }

    private fun layoutAndDrawSequential(
        canvas: Canvas, 
        text: String, 
        pageWidth: Int, 
        marginTop: Float, 
        marginBottom: Float,
        autoMarginNumbers: Boolean
    ) {
        // FIXED: Using project native Tokenizer utility to return expected List<TokenWithType>
        val nativeTokens = Tokenizer.tokenize(text)
        val wrapWidth = pageWidth.toFloat() - marginLeft - marginRight
        val placements = layoutEngine.computePlacements(nativeTokens, pen, wrapWidth, globalRandom)
        if (placements.isEmpty()) return

        var y = marginTop + lineSpacing * 1.5f
        val maxDrawY = height - marginBottom

        val mistakePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = baseInkColor
            style = Paint.Style.STROKE
            strokeWidth = 3.5f
            alpha = 210
        }

        for (line in placements) {
            if (y > maxDrawY) break 
            
            if (line.isEmpty()) {
                y += lineSpacing * (0.9f + globalRandom.nextFloat() * 0.2f)
                baselineDrift += PenState.gaussian(0f, driftStep, globalRandom).coerceIn(-driftClamp, driftClamp)
                pen.update(rest = true, isMyanmar = false, random = globalRandom)
                lineIndex++
                continue
            }

            val sineDrift = sin(lineIndex * 0.5f) * 2.0f
            baselineDrift = (baselineDrift + PenState.gaussian(0f, driftStep, globalRandom) + sineDrift)
                .coerceIn(-driftClamp, driftClamp)

            var x = marginLeft + 20f + PenState.gaussian(0f, 2.5f, globalRandom)
            
            for ((index, wp) in line.withIndex()) {
                val isMyanmar = wp.text.any { it in '\u1000'..'\u109F' }
                val isDigit = wp.text.any { it.isDigit() || it in '၀'..'၉' }

                // Controlled Gutter Shift logic based on the explicit toggle configuration
                if (autoMarginNumbers && index == 0 && isDigit && wp.text.length <= 3) {
                    val marginX = marginLeft - wp.estimatedWidth - 30f
                    glyphRenderer.drawWord(canvas, wp, marginX, y, baselineDrift, globalSlant, customTypeface)
                    continue 
                }

                // NATURAL HUMAN ERROR SIMULATION
                if (!isDigit && wp.text.trim().length > 2 && globalRandom.nextFloat() < mistakeProbability) {
                    val wordW = wp.estimatedWidth
                    val crossY = y - (fontSize * 0.25f) + baselineDrift
                    
                    glyphRenderer.drawWord(canvas, wp, x, y, baselineDrift, globalSlant, customTypeface)
                    canvas.drawLine(x - 4f, crossY, x + wordW + 4f, crossY, mistakePaint)
                    x += wordW + 24f 
                }

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

    /**
     * Renders a custom floating text selection fragment anywhere on the canvas,
     * maintaining token cluster structures to safeguard character placement.
     */
    private fun renderSingleCoordinateChunk(canvas: Canvas, chunk: AbsoluteTextChunk) {
        // FIXED: Using native Tokenizer here as well to fix compilation mismatch parameters
        val tokens = Tokenizer.tokenize(chunk.text)
        val placements = layoutEngine.computePlacements(tokens, pen, width.toFloat(), globalRandom)
        
        var currentY = chunk.y
        
        for (line in placements) {
            var currentX = if (chunk.forceToMarginGutter) {
                marginLeft - 80f
            } else {
                chunk.x
            }

            for ((index, wp) in line.withIndex()) {
                val isMyanmar = wp.text.any { it in '\u1000'..'\u109F' }
                
                glyphRenderer.drawWord(canvas, wp, currentX, currentY, 0f, globalSlant, customTypeface)
                currentX += wp.estimatedWidth
                
                if (index < line.size - 1) {
                    val nextWp = line[index + 1]
                    if (!(wp.isPartOfMyanmarWord && nextWp.isPartOfMyanmarWord)) {
                        currentX += 6f + PenState.gaussian(0f, 1.5f, globalRandom).coerceIn(-2f, 2f)
                    }
                }
                pen.update(rest = false, isMyanmar = isMyanmar, random = globalRandom)
            }
            currentY += lineSpacing
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
