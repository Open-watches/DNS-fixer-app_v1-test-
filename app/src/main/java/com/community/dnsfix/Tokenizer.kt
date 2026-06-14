package com.community.dnsfix.handwriting

import java.text.BreakIterator
import java.util.*

data class TokenWithType(
    val text: String,
    val isMyanmarCluster: Boolean,
    val isLastInMyanmarWord: Boolean
)

object Tokenizer {

    /**
     * Tokenizes the input text. For Myanmar text, the token is kept **as a whole word**
     * to preserve OpenType shaping (ligatures, subscripts, vowel wrapping).
     * No sub‑cluster splitting occurs – that was the cause of the sign drift.
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
                    // Newline is always kept as a single token
                    rawToken == "\n" -> {
                        result.add(TokenWithType(rawToken, false, true))
                    }
                    // Myanmar (or any text with Myanmar characters) – keep whole, never split
                    containsMyanmar(rawToken) -> {
                        result.add(TokenWithType(rawToken, true, true))
                    }
                    // Everything else (Latin, digits, punctuation) – treat as a regular word
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

    // The old cluster‑splitting method is kept only for reference; it is **not used**.
    private fun splitMyanmarClusters(text: String): List<String> {
        val pattern = java.util.regex.Pattern.compile(
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