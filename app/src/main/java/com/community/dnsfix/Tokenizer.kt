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
    // ... other methods ...

    // Make this public so GlyphRenderer can use it for per-cluster variation
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