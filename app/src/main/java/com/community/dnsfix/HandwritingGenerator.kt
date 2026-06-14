package com.community.dnsfix

import android.graphics.*
import android.icu.text.BreakIterator
import android.os.Build
import java.util.*
import kotlin.math.*

class HandwritingGenerator(
    private val width: Int,
    private val height: Int
) {
    // ----- Page geometry -----
    private val lineSpacing = 72f
    private val fontSize = (lineSpacing * 0.58f).coerceIn(40f, 48f)
    private val marginLeft = 100f
    private val marginRight = 80f
    private val globalSlant = 7f     // degrees

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
    private val paperColor = Color.parseColor("#F9F6F0")
    private val bleedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        maskFilter = BlurMaskFilter(2.5f, BlurMaskFilter.Blur.NORMAL)
    }

    // ----- Baseline drift (fatigue) -----
    private var baselineDrift = 0f
    private val driftStep = 0.4f

    // ----- AGSL shader for paper texture (API 33+) -----
    private var paperShader: Shader? = null

    // ----- Fallback paper texture (tiled noise bitmap) for API < 33 -----
    private val fallbackPaperShader: Shader by lazy {
        val noiseSize = 256
        val noiseBitmap = Bitmap.createBitmap(noiseSize, noiseSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(noiseBitmap)
        val random = Random()
        for (x in 0 until noiseSize) {
            for (y in 0 until noiseSize) {
                val grain = (random.nextFloat() * 20).toInt()
                val color = Color.argb(grain, 0xF9, 0xF6, 0xF0)
                noiseBitmap.setPixel(x, y, color)
            }
        }
        BitmapShader(noiseBitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            paperShader = android.graphics.RuntimeShader(
                """
                uniform float2 resolution;
                uniform float time;
                half4 main(float2 fragCoord) {
                    float2 uv = fragCoord / resolution;
                    // high-frequency grain
                    float grain = fract(sin(dot(uv, float2(12.9898, 78.233))) * 43758.5453);
                    grain = (grain - 0.5) * 0.08;
                    // vignette
                    float vignette = 1.0 - length(uv - 0.5) * 0.25;
                    half3 paper = half3(0.976, 0.965, 0.941);
                    half3 finalColor = (paper + grain) * vignette;
                    return half4(finalColor, 1.0);
                }
                """.trimIndent()
            )
        }
    }

    // ----- Helper: Gaussian (Box‑Muller) -----
    private fun gaussian(mean: Float, stdDev: Float, random: Random): Float =
        (random.nextGaussian() * stdDev + mean).toFloat()

    // ----- Word splitting (Myanmar‑aware) -----
    private fun createWordIterator(text: String): BreakIterator {
        val locale = try { Locale.forLanguageTag("my") } catch (e: Exception) { Locale.ROOT }
        return BreakIterator.getWordInstance(locale).apply { setText(text) }
    }

    // ----- Deterministic width measurement (per‑grapheme seeding) -----
    private data class WordToken(val text: String, val widths: List<Float>, val seed: Int)

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

    // ----- Main generation -----
    fun generateBitmap(text: String): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Paper background with AGSL or fallback tiled noise
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && paperShader != null) {
            (paperShader as android.graphics.RuntimeShader).setFloatUniform("resolution", width.toFloat(), height.toFloat())
            (paperShader as android.graphics.RuntimeShader).setFloatUniform("time", System.currentTimeMillis() / 1000f)
            val shaderPaint = Paint().apply { shader = paperShader }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shaderPaint)
        } else {
            val paint = Paint().apply { shader = fallbackPaperShader }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }

        // Ruled lines with tiny waviness
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

    // ----- Layout engine (preserves newlines, uses per‑grapheme widths) -----
    private fun layoutAndDraw(canvas: Canvas, text: String) {
        // Tokenise words + newlines
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

        // Build word tokens with per‑grapheme widths
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

        // Line composition
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

        // Render each line
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

    // ----- Draw a word using pre‑computed widths and per‑grapheme seeds -----
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

    // ----- Draw a single grapheme with ink bleed (both inside same matrix) -----
    private fun drawGrapheme(canvas: Canvas, grapheme: String, x: Float, baselineY: Float, graphemeSeed: Int) {
        val random = Random(graphemeSeed.toLong())
        val charWidth = textPaint.measureText(grapheme)

        // Pressure and advance (advance not used for placement, but kept for consistency)
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

        // 1. Ink bleed (blurred shadow) – drawn with same transformation
        bleedPaint.color = Color.argb((alpha * 0.3f).toInt(), 50, 40, 30)
        canvas.drawText(grapheme, 0f, 0f, bleedPaint)

        // 2. Crisp ink on top
        canvas.drawText(grapheme, 0f, 0f, textPaint)

        canvas.restore()
    }
}