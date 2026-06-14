package com.community.dnsfix

import android.content.Context
import android.graphics.*
import android.icu.text.BreakIterator
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.util.*
import kotlin.math.*

class HandwritingGenerator(
    private val width: Int,
    private val height: Int,
    private val context: Context? = null
) {
    // ----- Geometry -----
    private val lineSpacing = 72f
    private val fontSize = (lineSpacing * 0.58f).coerceIn(40f, 48f)
    private val marginLeft = 100f
    private val marginRight = 80f
    private val globalSlant = 7f

    // ----- Paints -----
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = fontSize
        typeface = Typeface.DEFAULT
        style = Paint.Style.FILL
    }
    private val linePaint = Paint().apply {
        color = Color.parseColor("#A5C6E8")
        strokeWidth = 2f
    }
    private val marginPaint = Paint().apply {
        color = Color.parseColor("#E8A5A5")
        strokeWidth = 3f
    }
    private val bleedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        maskFilter = BlurMaskFilter(2.5f, BlurMaskFilter.Blur.NORMAL)
    }

    // ----- Baseline drift -----
    private var baselineDrift = 0f
    private val driftStep = 0.4f

    // ----- AGSL shader (with fallback) -----
    private var paperShader: Shader? = null

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                paperShader = android.graphics.RuntimeShader(
                    """
                    uniform float2 resolution;
                    uniform float time;
                    half4 main(float2 fragCoord) {
                        float2 uv = fragCoord / resolution;
                        float grain = fract(sin(dot(uv, float2(12.9898, 78.233))) * 43758.5453);
                        grain = (grain - 0.5) * 0.08;
                        float vignette = 1.0 - length(uv - 0.5) * 0.25;
                        half3 paper = half3(0.976, 0.965, 0.941);
                        half3 finalColor = (paper + grain) * vignette;
                        return half4(finalColor, 1.0);
                    }
                    """.trimIndent()
                )
            } catch (e: Exception) {
                logError("AGSL init failed", e)
                paperShader = null
            }
        }
    }

    private val fallbackPaperShader: Shader by lazy {
        val noiseSize = 256
        val noiseBitmap = Bitmap.createBitmap(noiseSize, noiseSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(noiseBitmap)
        val rand = Random()
        for (x in 0 until noiseSize) {
            for (y in 0 until noiseSize) {
                val grain = (rand.nextFloat() * 20).toInt()
                val color = Color.argb(grain, 0xF9, 0xF6, 0xF0)
                noiseBitmap.setPixel(x, y, color)
            }
        }
        BitmapShader(noiseBitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    private fun gaussian(mean: Float, stdDev: Float, random: Random): Float =
        (random.nextGaussian() * stdDev + mean).toFloat()

    private fun createWordIterator(text: String): BreakIterator {
        val locale = try { Locale.forLanguageTag("my") } catch (e: Exception) { Locale.ROOT }
        return try {
            BreakIterator.getWordInstance(locale).apply { setText(text) }
        } catch (e: Exception) {
            BreakIterator.getCharacterInstance().apply { setText(text) }
        }
    }

    private fun computeWordWidths(word: String, seed: Int): List<Float> {
        val boundary = BreakIterator.getCharacterInstance()
        boundary.setText(word)
        val widths = mutableListOf<Float>()
        var charIdx = 0
        var start = boundary.first()
        var end = boundary.next()
        while (end != BreakIterator.DONE) {
            val grapheme = word.substring(start, end)
            if (grapheme != " ") {
                val graphemeSeed = (seed + charIdx) and 0x7fffffff
                val random = Random(graphemeSeed.toLong())
                val charWidth = textPaint.measureText(grapheme)
                val advance = charWidth * 1.05f + gaussian(0f, charWidth * 0.08f, random)
                widths.add(advance)
                charIdx++
            } else {
                widths.add(textPaint.measureText(" ") * 0.8f)
            }
            start = end
            end = boundary.next()
        }
        return widths
    }

    // ----- Error logging -----
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
            } catch (ignored: Exception) {}
        }
    }

    fun getLastError(): Pair<String?, String?> = Pair(lastErrorMessage, lastErrorStackTrace)

    // ----- Public generation (crash‑safe) -----
    fun generateBitmap(text: String): Bitmap {
        return try {
            lastErrorMessage = null
            lastErrorStackTrace = null
            realGenerateBitmap(text)
        } catch (e: Exception) {
            logError("Generation failed", e)
            createErrorBitmap(e)
        }
    }

    private fun realGenerateBitmap(text: String): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && paperShader != null) {
                (paperShader as android.graphics.RuntimeShader).setFloatUniform("resolution", width.toFloat(), height.toFloat())
                (paperShader as android.graphics.RuntimeShader).setFloatUniform("time", System.currentTimeMillis() / 1000f)
                val shaderPaint = Paint().apply { shader = paperShader }
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shaderPaint)
            } else {
                val paint = Paint().apply { shader = fallbackPaperShader }
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            }
        } catch (e: Exception) {
            canvas.drawColor(Color.parseColor("#F9F6F0"))
        }

        var y = lineSpacing
        while (y < height) {
            val wiggle = sin(y * 0.02f) * 1.5f
            canvas.drawLine(wiggle, y, width.toFloat() + wiggle, y, linePaint)
            y += lineSpacing
        }
        canvas.drawLine(marginLeft, 0f, marginLeft, height.toFloat(), marginPaint)

        if (text.isNotEmpty()) {
            baselineDrift = 0f
            layoutAndDraw(canvas, text)
        }
        return bitmap
    }

    private fun layoutAndDraw(canvas: Canvas, text: String) {
        val wordIterator = createWordIterator(text)
        val tokens = mutableListOf<String>()
        var start = wordIterator.first()
        var end = wordIterator.next()
        while (end != BreakIterator.DONE) {
            val token = text.substring(start, end)
            when {
                token == "\n" -> tokens.add("\n")
                token.trim().isNotEmpty() -> tokens.add(token)
            }
            start = end
            end = wordIterator.next()
        }

        data class WordLayout(val text: String, val widths: List<Float>, val seed: Int)
        val wordLayouts = mutableListOf<WordLayout>()
        for (token in tokens) {
            if (token == "\n") {
                wordLayouts.add(WordLayout("\n", emptyList(), 0))
            } else {
                val seed = token.hashCode() and 0x7fffffff
                val widths = computeWordWidths(token, seed)
                wordLayouts.add(WordLayout(token, widths, seed))
            }
        }

        val lines = mutableListOf<MutableList<WordLayout>>()
        var currentLine = mutableListOf<WordLayout>()
        var currentWidth = 0f
        for (wl in wordLayouts) {
            if (wl.text == "\n") {
                if (currentLine.isNotEmpty()) {
                    lines.add(currentLine)
                    currentLine = mutableListOf()
                    currentWidth = 0f
                }
                lines.add(mutableListOf())
                continue
            }
            val wordTotalWidth = wl.widths.sum() + 8f
            if (currentWidth + wordTotalWidth > width - marginRight && currentLine.isNotEmpty()) {
                lines.add(currentLine)
                currentLine = mutableListOf()
                currentWidth = 0f
            }
            currentLine.add(wl)
            currentWidth += wordTotalWidth
        }
        if (currentLine.isNotEmpty()) lines.add(currentLine)

        var y = lineSpacing * 1.8f
        for (line in lines) {
            if (line.isEmpty()) {
                y += lineSpacing
                baselineDrift += gaussian(0f, driftStep, Random())
                continue
            }
            var x = marginLeft + 20f + gaussian(0f, 2f, Random())
            for (wl in line) {
                x = drawWord(canvas, wl.text, wl.widths, wl.seed, x, y)
                x += 8f
            }
            y += lineSpacing
            baselineDrift += gaussian(0f, driftStep, Random()).coerceIn(-2.5f, 2.5f)
        }
    }

    private fun drawWord(canvas: Canvas, word: String, widths: List<Float>, seed: Int, startX: Float, baselineY: Float): Float {
        val boundary = BreakIterator.getCharacterInstance()
        boundary.setText(word)
        var x = startX
        var charIdx = 0
        var start = boundary.first()
        var end = boundary.next()
        while (end != BreakIterator.DONE && charIdx < widths.size) {
            val grapheme = word.substring(start, end)
            if (grapheme != " ") {
                val graphemeSeed = (seed + charIdx) and 0x7fffffff
                drawGrapheme(canvas, grapheme, x, baselineY, graphemeSeed)
                x += widths[charIdx]
            } else {
                x += textPaint.measureText(" ") * 0.8f
            }
            start = end
            end = boundary.next()
            charIdx++
        }
        return x
    }

    private fun drawGrapheme(canvas: Canvas, grapheme: String, x: Float, baselineY: Float, graphemeSeed: Int) {
        val random = Random(graphemeSeed.toLong())
        val pressure = random.nextFloat()
        val alpha = (120 + pressure * 135).toInt()
        val gray = (100 + pressure * 100).toInt()
        textPaint.color = Color.argb(alpha, gray, gray, gray)

        canvas.save()
        val matrix = Matrix()
        val skewX = -tan(Math.toRadians(globalSlant.toDouble())).toFloat()
        matrix.postSkew(skewX, 0f)
        val scaleX = 1f + gaussian(0f, 0.04f, random)
        val scaleY = 1f + gaussian(0f, 0.02f, random)
        matrix.postScale(scaleX, scaleY)
        val dx = gaussian(0f, 1.5f, random)
        val dy = baselineDrift + gaussian(0f, 1.2f, random)
        matrix.postTranslate(x + dx, baselineY + dy)
        canvas.concat(matrix)

        bleedPaint.color = Color.argb((alpha * 0.3f).toInt(), 50, 40, 30)
        canvas.drawText(grapheme, 0f, 0f, bleedPaint)
        canvas.drawText(grapheme, 0f, 0f, textPaint)
        canvas.restore()
    }

    private fun createErrorBitmap(e: Exception): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED; textSize = 28f }
        canvas.drawText("Generation failed: ${e.message}", 30f, 100f, paint)
        canvas.drawText("Check notification for details", 30f, 140f, paint)
        return bitmap
    }
}