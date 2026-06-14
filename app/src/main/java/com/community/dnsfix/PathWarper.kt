package com.community.dnsfix.handwriting

import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import kotlin.math.*
import kotlin.random.Random

object PathWarper {
    fun warpWithGrid(
        source: Path,
        strength: Float,
        seed: Int,
        tokenLength: Int
    ): Path {
        val bounds = RectF()
        source.computeBounds(bounds, true)
        val wordWidth = bounds.width()
        val wordHeight = bounds.height()
        if (wordWidth <= 0f || wordHeight <= 0f) return source

        val cellSize = when {
            tokenLength <= 2 -> 12f
            tokenLength <= 4 -> 18f
            else -> 25f
        }
        val gridCols = max(2, ceil(wordWidth / cellSize).toInt())
        val gridRows = max(2, ceil(wordHeight / cellSize).toInt())
        val rand = Random(seed.toLong())

        val gridX = Array(gridRows + 1) { FloatArray(gridCols + 1) }
        val gridY = Array(gridRows + 1) { FloatArray(gridCols + 1) }
        for (r in 0..gridRows)
            for (c in 0..gridCols) {
                gridX[r][c] = PenState.gaussian(0f, strength, rand)
                gridY[r][c] = PenState.gaussian(0f, strength, rand)
            }

        val cellWidth = wordWidth / gridCols
        val cellHeight = wordHeight / gridRows
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
                val col = (pos[0] / cellWidth).coerceIn(0f, gridCols - 1f)
                val row = (pos[1] / cellHeight).coerceIn(0f, gridRows - 1f)
                val c0 = col.toInt(); val r0 = row.toInt()
                val c1 = min(c0 + 1, gridCols); val r1 = min(r0 + 1, gridRows)
                val fx = col - c0; val fy = row - r0
                val dx = (1 - fx) * (1 - fy) * gridX[r0][c0] +
                         fx * (1 - fy) * gridX[r0][c1] +
                         (1 - fx) * fy * gridX[r1][c0] +
                         fx * fy * gridX[r1][c1]
                val dy = (1 - fx) * (1 - fy) * gridY[r0][c0] +
                         fx * (1 - fy) * gridY[r0][c1] +
                         (1 - fx) * fy * gridY[r1][c0] +
                         fx * fy * gridY[r1][c1]
                val warpedX = pos[0] + dx
                val warpedY = pos[1] + dy
                if (firstPoint) { result.moveTo(warpedX, warpedY); firstPoint = false }
                else result.lineTo(warpedX, warpedY)
            }
        } while (pm.nextContour())
        return result
    }
}