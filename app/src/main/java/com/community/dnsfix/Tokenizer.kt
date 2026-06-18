package com.community.dnsfix.handwriting

import java.text.BreakIterator
import java.util.Locale
import java.util.regex.Pattern

data class TokenWithType(
    val text: String,
    val isMyanmarCluster: Boolean,
    val isLastInMyanmarWord: Boolean
)

/**
 * Tokenizes mixed Latin / Myanmar text into a flat list of [TokenWithType] items.
 *
 * **Latin script** – each word and each whitespace sequence becomes one token.
 * **Myanmar script** – each orthographic syllable (cluster) becomes a token;
 * the last cluster in a Myanmar word is flagged with [TokenWithType.isLastInMyanmarWord] = true.
 * Newline characters are kept as individual tokens.
 */
object Tokenizer {

    // Pre‑compiled pattern for splitting a Myanmar text run into orthographic clusters.
    // Matches a base character followed by any combining marks or virama sequences.
    private val myanmarClusterPattern = Pattern.compile(
        "\\p{IsMyanmar}(?:\\p{M}|\\u1039\\p{IsMyanmar})*"
    )

    /**
     * Main entry point: tokenizes [text] for the handwriting layout engine.
     */
    fun tokenize(text: String): List<TokenWithType> {
        // Use the platform’s word‑break iterator.  A character‑based fallback is very unlikely
        // to be needed, so we keep it simple.
        val wordIterator = BreakIterator.getWordInstance(Locale.getDefault())
        wordIterator.setText(text)

        val result = mutableListOf<TokenWithType>()
        var start = wordIterator.first()
        var end = wordIterator.next()

        while (end != BreakIterator.DONE) {
            val rawToken = text.substring(start, end)
            if (rawToken.isNotEmpty()) {
                when {
                    rawToken == "\n" ->
                        result.add(TokenWithType(rawToken, false, true))

                    rawToken.any { it in '\u1000'..'\u109F' } -> {
                        // The word‑break iterator gave us a whole Myanmar “word”.
                        // Split it into orthographic clusters while preserving the last‑cluster flag.
                        val clusters = splitMyanmarClusters(rawToken)
                        for (i in clusters.indices) {
                            result.add(
                                TokenWithType(
                                    text = clusters[i],
                                    isMyanmarCluster = true,
                                    isLastInMyanmarWord = (i == clusters.size - 1)
                                )
                            )
                        }
                    }

                    else ->
                        // Latin words, punctuation, spaces, etc.
                        result.add(TokenWithType(rawToken, false, true))
                }
            }
            start = end
            end = wordIterator.next()
        }
        return result
    }

    /**
     * Splits a contiguous run of Myanmar text into individual orthographic clusters.
     * The regex does not match whitespace – it is expected that the caller already
     * separated Myanmar words from surrounding spaces.
     */
    private fun splitMyanmarClusters(text: String): List<String> {
        val matcher = myanmarClusterPattern.matcher(text)
        val clusters = mutableListOf<String>()
        while (matcher.find()) {
            clusters.add(matcher.group())
        }
        return clusters
    }
}