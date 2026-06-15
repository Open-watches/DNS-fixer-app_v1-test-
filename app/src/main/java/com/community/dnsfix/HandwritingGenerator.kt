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
    private val globalRandom = Random()
    private val pen = PenState()
    private var baselineDrift = 0f
    private val driftStep = 0.6f              // slightly higher for more movement
    private val driftClamp = 6.0f             // larger clamp
    private var lineIndex = 0                 // for sine wander

    private val layoutEngine = LayoutEngine(
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = fontSize
            typeface = customTypeface ?: Typeface.DEFAULT
        },
        width.toFloat(),
        marginRight
    )
    private val glyphRenderer = GlyphRenderer(baseInkColor, fontSize)

    // Error logging (unchanged)
    // ...

    fun generateBitmap(text: String): Bitmap = generateBitmap(text, width, height)
    fun generateBitmap(text: String, canvasWidth: Int, canvasHeight: Int): Bitmap {
        // ...
    }

    private fun realGenerateBitmap(text: String, w: Int, h: Int): Bitmap {
        pen.pressure = 0.88f; pen.slantOffset = 0f; pen.tremor = 1.0f; pen.fatigue = 0f
        pen.xDrift = 0f; pen.yDrift = 0f; pen.spacingBias = 0f; pen.errorAccumulation = 0f
        baselineDrift = 0f
        lineIndex = 0

        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        PaperRenderer(w, h).draw(canvas)
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

    // … error bitmap unchanged
}