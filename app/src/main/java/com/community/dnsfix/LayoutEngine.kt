package com.community.dnsfix.handwriting

import android.graphics.Paint
import java.util.Random

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

/** Internal packet: a whole word (or single non‑Myanmar token) with its placements ready. */
private data class WordPacket(
    val placements: List<WordPlacement>,
    val totalWidth: Float
)

class LayoutEngine(private val paint: Paint) {

    /**
     * Returns lines of [WordPlacement]s, respecting Myanmar word integrity.
     * @param tokens The parsed text stream.
     * @param pen The ongoing pen mechanics state.
     * @param wrapWidth The maximum allowable width for a single line of flowing text (e.g., pageWidth - marginLeft - marginRight).
     * @param globalRandom Shared random entropy source.
     */
    fun computePlacements(
        tokens: List<TokenWithType>,
        pen: PenState,
        wrapWidth: Float,
        globalRandom: Random
    ): List<List<WordPlacement>> {

        // ---- 1. Group consecutive Myanmar clusters into word packets ----
        val packets = groupIntoPackets(tokens, pen, globalRandom)

        // ---- 2. Place packets onto lines using greedy fitting ----
        val lines = mutableListOf<MutableList<WordPlacement>>()
        var currentLine = mutableListOf<WordPlacement>()
        var currentWidth = 0f

        for (packet in packets) {
            // Special case: a packet containing only a newline token
            if (packet.placements.size == 1 && packet.placements[0].text == "\n") {
                if (currentLine.isNotEmpty()) lines.add(currentLine)
                lines.add(mutableListOf())      // empty line for newline
                currentLine = mutableListOf()
                currentWidth = 0f
                continue
            }

            // If the whole packet fits within the wrap budget, or if the current line is completely empty
            // (prevents infinite loop if a single compound word is wider than the column width)
            if (currentWidth + packet.totalWidth <= wrapWidth || currentLine.isEmpty()) {
                currentLine.addAll(packet.placements)
                currentWidth += packet.totalWidth
            } else {
                // Packet doesn't fit → start a new line gracefully
                lines.add(currentLine)
                currentLine = mutableListOf()
                currentLine.addAll(packet.placements)
                currentWidth = packet.totalWidth
            }
        }

        if (currentLine.isNotEmpty()) lines.add(currentLine)
        return lines
    }

    // ---------------------------------------------------------------
    // Packet construction – keeps Myanmar words whole
    // ---------------------------------------------------------------
    private fun groupIntoPackets(
        tokens: List<TokenWithType>,
        pen: PenState,
        globalRandom: Random
    ): List<WordPacket> {
        val packets = mutableListOf<WordPacket>()
        var i = 0
        var wordIndex = 0

        while (i < tokens.size) {
            val tok = tokens[i]

            // ---- newline as its own packet ----
            if (tok.text == "\n") {
                pen.update(rest = true, isMyanmar = false, random = globalRandom)
                val seed = (tok.text.hashCode() * 31 + wordIndex) and 0x7fffffff
                wordIndex++
                val transforms = dummyTransforms()
                val placement = WordPlacement(tok.text, seed, 0f, transforms, false)
                packets.add(WordPacket(listOf(placement), 0f))
                i++
                continue
            }

            // ---- Myanmar word grouping ----
            if (tok.isMyanmarCluster) {
                val wordClusters = mutableListOf<TokenWithType>()
                while (i < tokens.size && tokens[i].isMyanmarCluster) {
                    val cluster = tokens[i]
                    wordClusters.add(cluster)
                    i++
                    if (cluster.isLastInMyanmarWord) break   // word boundary
                }

                val placements = mutableListOf<WordPlacement>()
                var totalWidth = 0f

                // FIXED: Removed the '- wordClusters.size' modifier. 
                // The raw wordIndex at this moment is already unique and forward-stepping.
                val firstRand = Random(
                    (wordClusters.first().text.hashCode() * 31 + wordIndex).toLong()
                )
                val wordBaseDy = PenState.gaussian(0f, 2.0f, firstRand)

                for (ci in wordClusters.indices) {
                    val cluster = wordClusters[ci]
                    val seed = (cluster.text.hashCode() * 31 + wordIndex) and 0x7fffffff
                    wordIndex++
                    val rand = Random(seed.toLong())

                    pen.update(rest = false, isMyanmar = true, random = globalRandom)

                    val pressure = (pen.pressure + PenState.gaussian(0f, 0.05f, rand)).coerceIn(0.4f, 0.95f)
                    val scaleX = (0.94f + PenState.gaussian(0f, 0.04f, rand)).coerceIn(0.85f, 1.05f)
                    val scaleY = (1.00f + PenState.gaussian(0f, 0.03f, rand)).coerceIn(0.95f, 1.08f)
                    val slantOffset = pen.slantOffset + PenState.gaussian(0f, 3.0f, rand)
                    val rotation = PenState.gaussian(0f, 2.0f + pen.fatigue * 1.5f, rand)
                    val dx = PenState.gaussian(0f, 2.0f + pen.errorAccumulation, rand) + pen.xDrift * 0.35f
                    val dy = wordBaseDy + PenState.gaussian(0f, 0.3f, rand)

                    val tremor = pen.tremor * (1f + pen.fatigue * 0.5f)
                    val transforms = WordTransforms(pressure, scaleX, scaleY, slantOffset, rotation, dx, dy, tremor)

                    val nativeWidth = paint.measureText(cluster.text)
                    val spacingBias = pen.spacingBias * 1.2f
                    var width = nativeWidth * scaleX + spacingBias

                    if (ci < wordClusters.size - 1) {
                        width *= 0.80f
                    }

                    val isPart = ci < wordClusters.size - 1
                    placements.add(WordPlacement(cluster.text, seed, width, transforms, isPart))
                    totalWidth += width
                }

                packets.add(WordPacket(placements, totalWidth))
            } else {
                // ---- Non‑Myanmar token (single) ----
                val seed = (tok.text.hashCode() * 31 + wordIndex) and 0x7fffffff
                wordIndex++
                val rand = Random(seed.toLong())

                pen.update(rest = false, isMyanmar = false, random = globalRandom)

                val pressure = (pen.pressure + PenState.gaussian(0f, 0.05f, rand)).coerceIn(0.4f, 0.95f)
                val scaleX = (0.94f + PenState.gaussian(0f, 0.04f, rand)).coerceIn(0.85f, 1.05f)
                val scaleY = (1.00f + PenState.gaussian(0f, 0.03f, rand)).coerceIn(0.95f, 1.08f)
                val slantOffset = pen.slantOffset + PenState.gaussian(0f, 3.0f, rand)
                val rotation = PenState.gaussian(0f, 2.0f + pen.fatigue * 1.5f, rand)
                val dx = PenState.gaussian(0f, 2.0f + pen.errorAccumulation, rand) + pen.xDrift * 0.35f
                val dy = PenState.gaussian(0f, 2.0f, rand)
                val tremor = pen.tremor * (1f + pen.fatigue * 0.5f)

                val transforms = WordTransforms(pressure, scaleX, scaleY, slantOffset, rotation, dx, dy, tremor)
                val nativeWidth = paint.measureText(tok.text)
                val spacingBias = pen.spacingBias * 1.2f
                val width = nativeWidth * scaleX + spacingBias

                val placement = WordPlacement(tok.text, seed, width, transforms, false)
                packets.add(WordPacket(listOf(placement), width))
                i++
            }
        }
        return packets
    }

    private fun dummyTransforms() = WordTransforms(0.7f, 1f, 1f, 0f, 0f, 0f, 0f, 1f)
}
