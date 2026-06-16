package com.community.dnsfix.handwriting

import java.util.regex.Pattern

class NumberModifier(
    var convertToMyanmarNumerals: Boolean = false,
    var extraDigitSpacing: Boolean = true,
    var customNumberPrefix: String = "",
    var customNumberSuffix: String = ""
) {
    private val westernDigits = "0123456789"
    private val myanmarDigits = "၀၁၂၃၄၅၆၇၈၉"

    /**
     * Transforms raw numeric tokens based on selected readability settings
     * before they hit the canvas layout engine.
     */
    fun modifyString(input: String): String {
        if (input.isEmpty()) return input
        
        var processed = input
        
        // 1. Convert numerals if toggled
        if (convertToMyanmarNumerals) {
            processed = processed.map { char ->
                val index = westernDigits.indexOf(char)
                if (index != -1) myanmarDigits[index] else char
            }.joinToString("")
        }

        // 2. Add structural prefixes/suffixes if present (e.g., "No. 1")
        val isNumeric = processed.any { it.isDigit() || it in '၀'..'၉' }
        if (isNumeric && (customNumberPrefix.isNotEmpty() || customNumberSuffix.isNotEmpty())) {
            processed = "$customNumberPrefix$processed$customNumberSuffix"
        }

        // 3. Inject micro-spaces around numbers to prevent ink collision ("too many blood")
        if (extraDigitSpacing && isNumeric) {
            processed = processed.replace(Regex("([0-9၀-၉])"), " $1 ").trim().replace(Regex("\\s+"), " ")
        }

        return processed
    }
}
