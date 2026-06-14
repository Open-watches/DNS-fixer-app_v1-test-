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
        tokenLength: Int
    ): Path {
        val bounds = RectF()
        source.computeBounds(bounds, true)
        val boxWidth = bounds.width()
        val boxHeight = bounds.height()
        if (boxWidth <= 0f || boxHeight <= 0f) return source

        val cellSize = when {
            tokenLength <= 2 -> 12f
            tokenLength <= 4 -> 18f
            else -> 25f
        }
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
                gridX[idx] = PenState.gaussian(0f, strength, rand)
                gridY[idx] = PenState.gaussian(0f, strength, rand)
            }
        }

        fun gridValue(x: FloatArray, row: Int, col: Int): Float {
            return x[row * (gridCols + 1) + col]
        }

        val cellWidth = boxWidth / gridCols
        val cellHeight = boxHeight / gridRows
        val pm = PathMeasure(source, false)
        val result = Path()
        val pos = FloatArray(2)

        do {
            val contourLength = pm.length
            if (contourLength == 0f) continue

            val step = 2.5f
            val numSamples = ceil(contourLength / step).toInt().coerceAtLeast(1)
            val realStep = contourLength / numSamples
            var firstPoint = true

            for (i in 0..numSamples) {
                pm.getPosTan(i * realStep, pos, null)

                // Normalize to local bounding-box coordinates
                val localX = pos[0] - bounds.left
                val localY = pos[1] - bounds.top

                val col = (localX / cellWidth).coerceIn(0f, gridCols - 1f)
                val row = (localY / cellHeight).coerceIn(0f, gridRows - 1f)

                val c0 = col.toInt()
                val r0 = row.toInt()
                val c1 = min(c0 + 1, gridCols)
                val r1 = min(r0 + 1, gridRows)

                val fx = col - c0
                val fy = row - r0

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
                    result.moveTo(warpedX, warpedY)
                    firstPoint = false
                } else {
                    result.lineTo(warpedX, warpedY)
                }
            }
        } while (pm.nextContour())

        return result
    }
}