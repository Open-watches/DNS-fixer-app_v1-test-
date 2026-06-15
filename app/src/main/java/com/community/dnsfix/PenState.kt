package com.community.dnsfix.handwriting

import java.util.Random
import kotlin.math.abs
import kotlin.math.atan2

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
     * Calculates the dynamic stroke width for a specific point on a path.
     * This creates the "variable width" look of a real pen.
     * 
     * @param normalizedSpeed 0.0 (slow/stopped) to 1.0 (fast). 
     *                        Real pens get thicker when slowing down.
     * @param curvature 0.0 (straight line) to 1.0 (tight loop). 
     *                    Writers unconsciously press harder on tight curves.
     */
    fun calculateStrokeWidth(normalizedSpeed: Float, curvature: Float = 0f): Float {
        // Base width derived from the current pressure state (which decays with fatigue)
        // Adjust the multipliers (3.5f and 2.5f) to fit your canvas scale
        val baseWidth = 3.5f + (pressure * 2.5f) 
        
        // 1. Speed mapping: Slow = thick, Fast = thin
        val speedInfluence = (1f - normalizedSpeed.coerceIn(0f, 1f)) * 0.8f
        
        // 2. Curvature mapping: Tight curves = slightly thicker
        val curveInfluence = curvature.coerceIn(0f, 1f) * 0.4f
        
        // 3. Combine and apply a tiny bit of high-frequency noise (micro-jitter in width)
        val widthMultiplier = 0.5f + (speedInfluence + curveInfluence)
        val microNoise = (kotlin.random.Random.nextFloat() - 0.5f) * 0.4f 
        
        return (baseWidth * widthMultiplier) + microNoise
    }

    /**
     * Called after every token – emulates the JS engine's `pen.update()`.
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