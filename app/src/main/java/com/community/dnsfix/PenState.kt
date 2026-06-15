package com.community.dnsfix.handwriting

import java.util.Random

data class PenState(
    var pressure: Float = 0.88f,
    var slantOffset: Float = 0f,
    var tremor: Float = 1.0f,
    var fatigue: Float = 0f,
    var xDrift: Float = 0f,               // continuous horizontal drift
    var yDrift: Float = 0f,               // continuous vertical drift
    var spacingBias: Float = 0f,          // affects inter‑character gaps
    var errorAccumulation: Float = 0f     // builds up with mistakes
) {
    companion object {
        fun gaussian(mean: Float, stdDev: Float, random: Random): Float =
            (random.nextGaussian().toFloat() * stdDev + mean)
    }

    /**
     * Called after every token – emulates the JS engine's `pen.update()`.
     * @param rest true after line breaks, false during writing.
     * @param isMyanmar true if the current token is Myanmar (adjusts error probability).
     */
    fun update(rest: Boolean, isMyanmar: Boolean, random: Random) {
        if (rest) {
            fatigue = (fatigue - 0.005f).coerceAtLeast(0f)
        } else {
            fatigue = (fatigue + 0.003f).coerceAtMost(0.4f)
        }

        // Continuous drift (slowly wanders)
        xDrift += gaussian(0f, 0.08f, random)
        yDrift += gaussian(0f, 0.06f, random)
        xDrift *= 0.97f
        yDrift *= 0.97f

        // Tremor oscillates
        tremor = (tremor + 0.04f + fatigue * 0.08f) % 62.83185f

        // Pressure decays with fatigue
        pressure = (0.88f - fatigue * 0.15f).coerceIn(0.72f, 0.92f)

        // Slant drifts slowly
        slantOffset = (slantOffset + gaussian(0f, 0.008f, random)).coerceIn(-6f, 6f)

        // Spacing bias changes over time (slight random walk)
        spacingBias += gaussian(0f, 0.04f, random)
        spacingBias *= 0.94f

        // Occasional errors (more frequent for Myanmar)
        if (random.nextFloat() < if (isMyanmar) 0.012f else 0.022f) {
            errorAccumulation += 0.15f
        }
        errorAccumulation *= 0.993f
    }
}