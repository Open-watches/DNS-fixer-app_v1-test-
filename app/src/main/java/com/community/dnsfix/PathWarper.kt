package com.community.dnsfix.handwriting

import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import kotlin.math.*
import java.util.Random

object PathWarper {

    fun warpWithGrid(
        source: Path,
        strength: Float,
        seed: Int,
        tokenLength: Int,
        bounds: RectF
    ): Path {
        val boxWidth = bounds.width()
        val boxHeight = bounds.height()
        if (boxWidth <= 0f || boxHeight <= 0f) return source

        // ---- Low‑frequency grid (organic shape) ----
        val lowFreqCellSize = max(boxWidth, boxHeight) * 0.6f
        val lfCols = max(2, ceil(boxWidth / lowFreqCellSize).toInt())
        val lfRows = max(2, ceil(boxHeight / lowFreqCellSize).toInt())

        val lfTotal = (lfRows + 1) * (lfCols + 1)
        val lfX = FloatArray(lfTotal)
        val lfY = FloatArray(lfTotal)
        val randLow = Random(seed.toLong())
        for (r in 0..lfRows) {
            val rowBase = r * (lfCols + 1)
            for (c in 0..lfCols) {
                val idx = rowBase + c
                lfX[idx] = PenState.gaussian(0f, strength * 0.6f, randLow)
                lfY[idx] = PenState.gaussian(0f, strength * 0.6f, randLow)
            }
        }

        // ---- High‑frequency grid (edge roughness) ----
        val hfCellSize = 6f   // very fine
        val hfCols = max(2, ceil(boxWidth / hfCellSize).toInt())
        val hfRows = max(2, ceil(boxHeight / hfCellSize).toInt())
        val hfTotal = (hfRows + 1) * (hfCols + 1)
        val hfX = FloatArray(hfTotal)
        val hfY = FloatArray(hfTotal)
        val randHigh = Random((seed xor 0x12345678).toLong())
        // High‑freq noise is much smaller amplitude
        val hfStrength = strength * 0.15f
        for (r in 0..hfRows) {
            val rowBase = r * (hfCols + 1)
            for (c in 0..hfCols) {
                val idx = rowBase + c
                hfX[idx] = PenState.gaussian(0f, hfStrength, randHigh)
                hfY[idx] = PenState.gaussian(0f, hfStrength, randHigh)
            }
        }

        // Helper to get value from a flat grid
        fun gridVal(x: FloatArray, row: Int, col: Int, cols: Int) = x[row * (cols + 1) + col]

        val lfCellW = boxWidth / lfCols
        val lfCellH = boxHeight / lfRows
        val hfCellW = boxWidth / hfCols
        val hfCellH = boxHeight / hfRows

        val pm = PathMeasure(source, false)
        val result = Path().apply { fillType = source.fillType }
        val pos = FloatArray(2)
        val disconnectRand = Random(seed.toLong() xor 0xDEADBEEF)

        do {
            val contourLength = pm.length
            if (contourLength == 0f) continue

            val step = 2.5f
            val numSamples = ceil(contourLength / step).toInt().coerceAtLeast(1)
            val realStep = contourLength / numSamples
            var startX = 0f
            var startY = 0f
            var firstPoint = true

            for (i in 0..numSamples) {
                pm.getPosTan(i * realStep, pos, null)

                val localX = pos[0] - bounds.left
                val localY = pos[1] - bounds.top

                // Low‑frequency displacement
                val lfCol = (localX / lfCellW).coerceIn(0f, lfCols.toFloat())
                val lfRow = (localY / lfCellH).coerceIn(0f, lfRows.toFloat())
                var lfC0 = lfCol.toInt(); var lfC1 = lfC0 + 1
                if (lfC1 > lfCols) { lfC1 = lfCols; lfC0 = lfCols - 1 }
                var lfR0 = lfRow.toInt(); var lfR1 = lfR0 + 1
                if (lfR1 > lfRows) { lfR1 = lfRows; lfR0 = lfRows - 1 }
                val lfFx = (lfCol - lfC0).coerceIn(0f, 1f)
                val lfFy = (lfRow - lfR0).coerceIn(0f, 1f)

                val lfDx = (1-lfFx)*(1-lfFy)*gridVal(lfX, lfR0, lfC0, lfCols) +
                          lfFx*(1-lfFy)*gridVal(lfX, lfR0, lfC1, lfCols) +
                          (1-lfFx)*lfFy*gridVal(lfX, lfR1, lfC0, lfCols) +
                          lfFx*lfFy*gridVal(lfX, lfR1, lfC1, lfCols)
                val lfDy = (1-lfFx)*(1-lfFy)*gridVal(lfY, lfR0, lfC0, lfCols) +
                          lfFx*(1-lfFy)*gridVal(lfY, lfR0, lfC1, lfCols) +
                          (1-lfFx)*lfFy*gridVal(lfY, lfR1, lfC0, lfCols) +
                          lfFx*lfFy*gridVal(lfY, lfR1, lfC1, lfCols)

                // High‑frequency roughness
                val hfCol = (localX / hfCellW).coerceIn(0f, hfCols.toFloat())
                val hfRow = (localY / hfCellH).coerceIn(0f, hfRows.toFloat())
                var hfC0 = hfCol.toInt(); var hfC1 = hfC0 + 1
                if (hfC1 > hfCols) { hfC1 = hfCols; hfC0 = hfCols - 1 }
                var hfR0 = hfRow.toInt(); var hfR1 = hfR0 + 1
                if (hfR1 > hfRows) { hfR1 = hfRows; hfR0 = hfRows - 1 }
                val hfFx = (hfCol - hfC0).coerceIn(0f, 1f)
                val hfFy = (hfRow - hfR0).coerceIn(0f, 1f)

                val hfDx = (1-hfFx)*(1-hfFy)*gridVal(hfX, hfR0, hfC0, hfCols) +
                          hfFx*(1-hfFy)*gridVal(hfX, hfR0, hfC1, hfCols) +
                          (1-hfFx)*hfFy*gridVal(hfX, hfR1, hfC0, hfCols) +
                          hfFx*hfFy*gridVal(hfX, hfR1, hfC1, hfCols)
                val hfDy = (1-hfFx)*(1-hfFy)*gridVal(hfY, hfR0, hfC0, hfCols) +
                          hfFx*(1-hfFy)*gridVal(hfY, hfR0, hfC1, hfCols) +
                          (1-hfFx)*hfFy*gridVal(hfY, hfR1, hfC0, hfCols) +
                          hfFx*hfFy*gridVal(hfY, hfR1, hfC1, hfCols)

                val totalDx = lfDx + hfDx
                val totalDy = lfDy + hfDy

                val warpedX = pos[0] + totalDx
                val warpedY = pos[1] + totalDy

                if (firstPoint) {
                    startX = warpedX
                    startY = warpedY
                    result.moveTo(warpedX, warpedY)
                    firstPoint = false
                } else {
                    result.lineTo(warpedX, warpedY)
                }
            }

            // Terminal disconnection (unchanged)
            val r = disconnectRand.nextFloat()
            when {
                r < 0.4f -> {
                    val overX = startX + PenState.gaussian(0f, 2.0f, disconnectRand)
                    val overY = startY + PenState.gaussian(0f, 2.0f, disconnectRand)
                    result.lineTo(overX, overY)
                }
                r < 0.7f -> { /* open */ }
                else -> result.close()
            }
        } while (pm.nextContour())

        return result
    }
}