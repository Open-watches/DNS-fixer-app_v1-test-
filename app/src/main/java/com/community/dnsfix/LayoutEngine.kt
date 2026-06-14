package com.community.dnsfix.handwriting

import android.graphics.Paint
import kotlin.random.Random

data class WordTransforms(
    val pressure: Float,
    val scaleX: Float,
    val scaleY: Float,
    val slantOffset: Float,
    val rotation: Float,
    val dx: Float,
    val dy: Float,
    val tremor: Float
)

data class WordPlacement(
    val text: String,
    val seed: Int,
    val estimatedWidth: Float,
    val transforms: WordTransforms,
    val isPartOfMyanmarWord: Boolean = false
)

class LayoutEngine(
    private val paint: Paint,
    private val pageWidth: Float,
    private val marginRight: Float
) {
    fun computePlacements(
        tokens: List<TokenWithType>,
        pen: PenState,
        globalRandom: Random
    ): List<List<WordPlacement>> {
        val lines = mutableListOf<MutableList<WordPlacement>>()
        var currentLine = mutableListOf<WordPlacement>()
        var currentWidth = 0f
        var wordIndex = 0

        for (tok in tokens) {
            if (tok.text == "\n") {
                if (currentLine.isNotEmpty()) lines.add(currentLine)
                lines.add(mutableListOf())
                currentLine = mutableListOf()
                currentWidth = 0f
                continue
            }

            val seed = (tok.text.hashCode() * 31 + wordIndex) and 0x7fffffff
            wordIndex++
            val rand = Random(seed.toLong())

            val pressure = (pen.pressure + PenState.gaussian(0f, 0.05f, rand)).coerceIn(0.4f, 0.95f)
            val scaleX = 1f + PenState.gaussian(0f, 0.07f + pen.fatigue * 0.04f, rand)
            val scaleY = 1f + PenState.gaussian(0f, 0.05f, rand)
            val slantOffset = pen.slantOffset + PenState.gaussian(0f, 3.0f, rand)
            val rotation = PenState.gaussian(0f, 2.0f + pen.fatigue * 1.5f, rand)
            val dx = PenState.gaussian(0f, 2.0f, rand)
            val dy = PenState.gaussian(0f, 2.0f, rand)
            val tremor = pen.tremor * (1f + pen.fatigue * 0.5f)

            val transforms = WordTransforms(pressure, scaleX, scaleY, slantOffset, rotation, dx, dy, tremor)
            val nativeWidth = paint.measureText(tok.text)
            val finalWidth = nativeWidth * scaleX

            if (currentWidth + finalWidth > pageWidth - marginRight && currentLine.isNotEmpty()) {
                lines.add(currentLine)
                currentLine = mutableListOf()
                currentWidth = 0f
            }
            currentLine.add(
                WordPlacement(tok.text, seed, finalWidth, transforms,
                    isPartOfMyanmarWord = tok.isMyanmarCluster && !tok.isLastInMyanmarWord)
            )
            currentWidth += finalWidth
            pen.evolve(globalRandom)
        }
        if (currentLine.isNotEmpty()) lines.add(currentLine)
        return lines
    }
}