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

        // ---------- Low‑frequency asymmetry ----------
        // Large cells so that whole curves shift smoothly
        val cellSize = max(boxWidth, boxHeight) * 0.7f   // ~70% of the character size
        val gridCols = max(2, ceil(boxWidth / cellSize).toInt())
        val gridRows = max(2, ceil(boxHeight / cellSize).toInt())

        val totalCells = (gridRows + 1) * (gridCols + 1)
        val gridX = FloatArray(totalCells)
        val gridY = FloatArray(totalCells)
        val rand = Random(seed.toLong())

        for (r in 0..gridRows) {
            val rowBase = r * (gridCols + 1)
            for (c in 0..gridCols) {
                val idx = rowBase + c
                gridX[idx] = PenState.gaussian(0f, strength * 0.6f, rand)
                gridY[idx] = PenState.gaussian(0f, strength * 0.6f, rand)
            }
        }

        fun gridValue(x: FloatArray, row: Int, col: Int): Float =
            x[row * (gridCols + 1) + col]

        val cellWidth = boxWidth / gridCols
        val cellHeight = boxHeight / gridRows

        val pm = PathMeasure(source, false)
        val result = Path().apply { fillType = source.fillType }
        val pos = FloatArray(2)

        // ---------- Terminal disconnection randomiser ----------
        val disconnectRand = Random(seed.toLong() xor 0xDEADBEEF)

        do {
            val contourLength = pm.length
            if (contourLength == 0f) continue

            val step = 2.5f
            val numSamples = ceil(contourLength / step).toInt().coerceAtLeast(1)
            val realStep = contourLength / numSamples

            // Store start point for possible overshoot
            var startX = 0f
            var startY = 0f
            var firstPoint = true

            for (i in 0..numSamples) {
                pm.getPosTan(i * realStep, pos, null)

                val localX = pos[0] - bounds.left
                val localY = pos[1] - bounds.top

                val col = if (cellWidth > 0f) (localX / cellWidth).coerceIn(0f, gridCols.toFloat()) else 0f
                val row = if (cellHeight > 0f) (localY / cellHeight).coerceIn(0f, gridRows.toFloat()) else 0f

                var c0 = col.toInt()
                var c1 = c0 + 1
                if (c1 > gridCols) { c1 = gridCols; c0 = gridCols - 1 }
                val fx = (col - c0).coerceIn(0f, 1f)

                var r0 = row.toInt()
                var r1 = r0 + 1
                if (r1 > gridRows) { r1 = gridRows; r0 = gridRows - 1 }
                val fy = (row - r0).coerceIn(0f, 1f)

                val dx = (1 - fx) * (1 - fy) * gridValue(gridX, r0, c0) +
                         fx * (1 - fy) * gridValue(gridX, r0, c1) +
                         (1 - fx) * fy * gridValue(gridX, r1, c0) +
                         fx * fy * gridValue(gridX, r1, c1)
                val dy = (1 - fx) * (1 - fy) * gridValue(gridY, r0, c0) +
                         fx * (1 - fy) * gridValue(gridY, r0, c1) +
                         (1 - fx) * fy * gridValue(gridY, r1, c0) +
                         fx * fy * gridValue(gridY, r1, c1)

                val warpedX = pos[0] + dx
                val warpedY = pos[1] + dy

                if (firstPoint) {
                    startX = warpedX
                    startY = warpedY
                    result.moveTo(warpedX, warpedY)
                    firstPoint = false
                } else {
                    result.lineTo(warpedX, warpedY)
                }
            }

            // ---------- Terminal disconnection ----------
            val r = disconnectRand.nextFloat()
            when {
                // 40% chance: overshoot past the start by a small amount
                r < 0.4f -> {
                    val overX = startX + PenState.gaussian(0f, 2.0f, disconnectRand)
                    val overY = startY + PenState.gaussian(0f, 2.0f, disconnectRand)
                    result.lineTo(overX, overY)
                }
                // 30% chance: undershoot – just don't close the contour
                r < 0.7f -> {
                    // do nothing, path stays open
                }
                // 30% chance: close normally
                else -> {
                    result.close()
                }
            }
        } while (pm.nextContour())

        return result
    }
}