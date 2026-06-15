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

    /**
     * Tokenizes the input text. For Myanmar text, the token is kept as a whole word
     * to preserve OpenType shaping. No sub‑cluster splitting occurs.
     */
    fun tokenize(text: String): List<TokenWithType> {
        // Use BreakIterator for the primary segmentation (spaces, punctuation).
        // It may fail to split continuous Myanmar text – that’s fine; we keep the whole run.
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
                        result.add(TokenWithType(rawToken, true, true))
                    }
                    else -> {
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
     * Splits Myanmar text into orthographic clusters (syllables) using the
     * Unicode 29 segmentation rules. This regex correctly groups base consonants,
     * medial consonants, vowel signs, and tone marks.
     * Now public for use by GlyphRenderer.
     */
    fun splitMyanmarClusters(text: String): List<String> {
        val pattern = Pattern.compile(
            "\\p{IsMyanmar}" +
            "(?:" +
            "\\p{M}" +
            "|\\u1039\\p{IsMyanmar}" +
            ")*" +
            "|\\s+|\\n"
        )
        val matcher = pattern.matcher(text)
        val result = mutableListOf<String>()
        while (matcher.find()) result.add(matcher.group())
        return result
    }
}