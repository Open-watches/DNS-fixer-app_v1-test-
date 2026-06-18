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

class LayoutEngine(private val paint: Paint) {

    /**
     * Groups a list of [WordPlacement] items into lines, respecting
     * Myanmar‑word integrity and the given [wrapWidth].
     *
     * @param tokens The parsed tokens from [Tokenizer].
     * @param pen The current pen state, mutated as tokens are processed.
     * @param wrapWidth Available width for one line (in pixels).
     * @param globalRandom Shared randomness source.
     * @return A list of lines, each containing placed words.
     */
    fun computePlacements(
        tokens: List<TokenWithType>,
        pen: PenState,
        wrapWidth: Float,
        globalRandom: Random
    ): List<List<WordPlacement>> {

        // 1. Build packets – each packet is a whole Myanmar word or a single non‑Myanmar token
        val packets = buildPackets(tokens, pen, globalRandom)

        // 2. Greedy line fitting
        val lines = mutableListOf<MutableList<WordPlacement>>()
        var currentLine = mutableListOf<WordPlacement>()
        var currentWidth = 0f

        for (packet in packets) {
            // Newline packet → start a new line
            if (packet.placements.size == 1 && packet.placements[0].text == "\n") {
                lines.add(currentLine)               // push current line (may be empty)
                lines.add(mutableListOf())           // empty line for newline
                currentLine = mutableListOf()
                currentWidth = 0f
                continue
            }

            // If packet fits in remaining space, or if the line is completely empty
            // (allows oversized single words to occupy a line alone)
            if (currentWidth + packet.totalWidth <= wrapWidth || currentLine.isEmpty()) {
                currentLine.addAll(packet.placements)
                currentWidth += packet.totalWidth
            } else {
                // Packet doesn't fit → start a new line
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
    //  Packet construction
    // ---------------------------------------------------------------

    private fun buildPackets(
        tokens: List<TokenWithType>,
        pen: PenState,
        globalRandom: Random
    ): List<WordPacket> {
        val packets = mutableListOf<WordPacket>()
        var i = 0
        var wordIndex = 0   // increments for every token, guarantees unique seeds

        while (i < tokens.size) {
            val tok = tokens[i]

            // Newline token → special packet
            if (tok.text == "\n") {
                pen.update(rest = true, isMyanmar = false, random = globalRandom)
                val seed = (tok.text.hashCode() * 31 + wordIndex) and 0x7fffffff
                wordIndex++
                val transforms = dummyTransforms()
                packets.add(
                    WordPacket(
                        listOf(WordPlacement(tok.text, seed, 0f, transforms, false)),
                        0f
                    )
                )
                i++
                continue
            }

            // Myanmar word (one or more consecutive clusters)
            if (tok.isMyanmarCluster) {
                val wordClusters = mutableListOf<TokenWithType>()
                while (i < tokens.size && tokens[i].isMyanmarCluster) {
                    val cluster = tokens[i]
                    wordClusters.add(cluster)
                    i++
                    if (cluster.isLastInMyanmarWord) break
                }

                val placements = mutableListOf<WordPlacement>()
                var totalWidth = 0f

                // Per‑word random offset (dy) stays consistent across all clusters of the word
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

                    // Slightly compress inter‑cluster spacing (Myanmar words are compact)
                    if (ci < wordClusters.size - 1) {
                        width *= 0.80f
                    }

                    val isPart = ci < wordClusters.size - 1
                    placements.add(WordPlacement(cluster.text, seed, width, transforms, isPart))
                    totalWidth += width
                }

                packets.add(WordPacket(placements, totalWidth))
            } else {
                // Non‑Myanmar token (single)
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

    // ---------------------------------------------------------------
    //  Internal helpers
    // ---------------------------------------------------------------

    private data class WordPacket(
        val placements: List<WordPlacement>,
        val totalWidth: Float
    )

    private fun dummyTransforms() = WordTransforms(0.7f, 1f, 1f, 0f, 0f, 0f, 0f, 1f)
}