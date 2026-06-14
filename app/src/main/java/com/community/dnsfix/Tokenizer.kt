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
    private fun containsMyanmar(s: String): Boolean {
        for (ch in s) if (ch in '\u1000'..'\u109F') return true
        return false
    }

    private fun splitMyanmarClusters(text: String): List<String> {
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
                if (containsMyanmar(rawToken)) {
                    val clusters = splitMyanmarClusters(rawToken)
                    for ((i, cluster) in clusters.withIndex()) {
                        result.add(TokenWithType(cluster, true, i == clusters.lastIndex))
                    }
                } else {
                    result.add(TokenWithType(rawToken, false, true))
                }
            }
            start = end
            end = wordIterator.next()
        }
        return result
    }
}