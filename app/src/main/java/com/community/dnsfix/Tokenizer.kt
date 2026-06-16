package com.community.dnsfix.handwriting

import java.text.BreakIterator
import java.util.*
import java.util.regex.Pattern

data class TokenWithType(
    val text: String,
    val isMyanmarCluster: Boolean,
    val isLastInMyanmarWord: Boolean
)

object Tokenizer {

    private val myanmarClusterPattern = Pattern.compile(
        "\\p{IsMyanmar}(?:\\p{M}|\\u1039\\p{IsMyanmar})*|\\s+|\\n"
    )

    /**
     * Tokenizes incoming text streams cleanly.
     * Breaks Latin text into word/whitespace tokens, and breaks Myanmar text 
     * down into individual orthographic clusters while preserving word boundary flags.
     */
    fun tokenize(text: String): List<TokenWithType> {
        val wordIterator = try {
            BreakIterator.getWordInstance(Locale.getDefault()).apply { setText(text) }
        } catch (e: Exception) {
            BreakIterator.getCharacterInstance().apply { setText(text) }
        }

        val result = mutableListOf<TokenWithType>()
        var start = wordIterator.first()
        var end = wordIterator.next()

        while (end != BreakIterator.DONE) {
            val rawToken = text.substring(start, end)
            if (rawToken.isNotEmpty()) {
                when {
                    rawToken == "\n" -> {
                        result.add(TokenWithType(rawToken, false, true))
                    }
                    containsMyanmar(rawToken) -> {
                        // Split the Myanmar word run into individual syllables/clusters
                        val clusters = splitMyanmarClusters(rawToken)
                        for (i in clusters.indices) {
                            val isLast = (i == clusters.size - 1)
                            result.add(TokenWithType(clusters[i], true, isLast))
                        }
                    }
                    else -> {
                        // Standard Latin words or spaces
                        result.add(TokenWithType(rawToken, false, true))
                    }
                }
            }
            start = end
            end = wordIterator.next()
        }
        return result
    }

    private fun containsMyanmar(s: String): Boolean {
        for (ch in s) if (ch in '\u1000'..'\u109F') return true
        return false
    }

    /**
     * Splits Myanmar text into true orthographic clusters (syllables).
     * Now optimized to reuse a pre-compiled pattern instance.
     */
    fun splitMyanmarClusters(text: String): List<String> {
        val matcher = myanmarClusterPattern.matcher(text)
        val result = mutableListOf<String>()
        while (matcher.find()) {
            val cluster = matcher.group()
            if (cluster.isNotEmpty()) {
                result.add(cluster)
            }
        }
        return result
    }
}
