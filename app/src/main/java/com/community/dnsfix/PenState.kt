package com.community.dnsfix.handwriting

import java.util.Random   // <-- java.util.Random, which has nextGaussian()

data class PenState(
    var pressure: Float = 0.7f,
    var slantOffset: Float = 0f,
    var tremor: Float = 1.0f,
    var fatigue: Float = 0f
) {
    companion object {
        /**
         * Gaussian (normal) distribution using java.util.Random.
         * All other modules (PathWarper, LayoutEngine) also use java.util.Random.
         */
        fun gaussian(mean: Float, stdDev: Float, random: Random): Float =
            (random.nextGaussian().toFloat() * stdDev + mean)
    }

    fun update(rest: Boolean, random: Random) {
        if (rest) {
            fatigue = (fatigue - 0.005f).coerceAtLeast(0f)
        } else {
            fatigue = (fatigue + 0.003f).coerceAtMost(0.4f)
        }
        pressure = (0.7f - fatigue * 0.3f + gaussian(0f, 0.03f, random)).coerceIn(0.4f, 0.9f)
        slantOffset = (slantOffset + gaussian(0f, 0.1f, random)).coerceIn(-4f, 4f)
        tremor = (1.0f + fatigue * 0.8f).coerceAtMost(1.8f)
    }

    fun evolve(random: Random) {
        fatigue = (fatigue + 0.0005f).coerceAtMost(0.4f)
        pressure = (pressure - 0.0002f + gaussian(0f, 0.01f, random)).coerceIn(0.4f, 0.9f)
        slantOffset = (slantOffset + gaussian(0f, 0.05f, random)).coerceIn(-4f, 4f)
        tremor = (1.0f + fatigue * 0.8f).coerceAtMost(1.8f)
    }
}