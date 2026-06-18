package com.community.dnsfix.handwriting

import java.text.BreakIterator
import java.util.Locale
import java.util.regex.Pattern

data class TokenWithType(
    val text: String,
    val isMyanmarCluster: Boolean,
    val isLastInMyanmarWord: Boolean
)

object Tokenizer {

    // Expanded Myanmar cluster regex (handles kinzi, medials, stacked)
    private val myanmarClusterPattern = Pattern.compile(
        "\\p{IsMyanmar}(?:\\p{Mn}|\\p{Mc}|\\u1039\\p{IsMyanmar}|\\u103A)*"
    )

    fun tokenize(text: String): List<TokenWithType> {
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
                    containsMyanmar(rawToken) -> {
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
                        result.add(TokenWithType(rawToken, false, true))
                }
            }
            start = end
            end = wordIterator.next()
        }
        return result
    }

    fun splitMyanmarClusters(text: String): List<String> {
        val matcher = myanmarClusterPattern.matcher(text)
        val clusters = mutableListOf<String>()
        var lastEnd = 0
        while (matcher.find()) {
            if (matcher.start() > lastEnd) {
                // Keep unmatched characters as individual tokens
                val unmatched = text.substring(lastEnd, matcher.start())
                for (c in unmatched) {
                    clusters.add(c.toString())
                }
            }
            clusters.add(matcher.group())
            lastEnd = matcher.end()
        }
        if (lastEnd < text.length) {
            val unmatched = text.substring(lastEnd)
            for (c in unmatched) {
                clusters.add(c.toString())
            }
        }
        return clusters.ifEmpty { listOf(text) }
    }

    fun containsMyanmar(s: String): Boolean {
        for (ch in s) {
            if (ch in '\u1000'..'\u109F' ||
                ch in '\uAA60'..'\uAA7F' ||
                ch in '\uA9E0'..'\uA9FF') return true
        }
        return false
    }
}