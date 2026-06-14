package com.community.dnsfix

import android.content.Context
import android.graphics.*
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.text.BreakIterator
import java.util.*
import java.util.regex.Pattern
import kotlin.math.*

class HandwritingGenerator(
    private val width: Int = 1000,
    private val height: Int = 1200,
    private val context: Context? = null,
    private val baseInkColor: Int = Color.rgb(0, 51, 153),   // classic blue pen
    private val lineSpacing: Float = 72f,
    private val customTypeface: Typeface? = null
) {
    // ----- Geometry (derived) -----
    private val fontSize = (lineSpacing * 0.58f).coerceIn(40f, 48f)
    private val marginLeft = 100f
    private val marginRight = 80f
    private val globalSlant = 7f

    // ----- Single shared random source for inter‑line randomness only -----
    private val globalRandom = Random()

    // ----- Paints -----
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = fontSize
        typeface = customTypeface ?: Typeface.DEFAULT
        style = Paint.Style.FILL
    }
    private val linePaint = Paint().apply {
        color = Color.parseColor("#A5C6E8")
        strokeWidth = 2f
    }
    private val marginPaint = Paint().apply {
        color = Color.parseColor("#E8A5A5")
        strokeWidth = 3f
    }
    private val spreadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DARKEN)
    }

    // ----- Pen physics state (continuous across words) -----
    private data class PenState(
        var pressure: Float = 0.7f,
        var slantOffset: Float = 0f,
        var tremor: Float = 1.0f,
        var fatigue: Float = 0f
    )

    private val pen = PenState()

    // ----- Baseline drift (reduced aggression) -----
    private var baselineDrift = 0f
    private val driftStep = 0.4f
    private val driftClamp = 5.0f

    // ----- Paper shader (AGSL with fiber + grain + vignette) -----
    private var paperShader: Shader? = null

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                paperShader = android.graphics.RuntimeShader(
                    """
                    uniform float2 resolution;
                    uniform float time;
                    
                    float random(float2 st) {
                        return fract(sin(dot(st, float2(12.9898, 78.233))) * 43758.5453);
                    }
                    
                    half4 main(float2 fragCoord) {
                        float2 uv = fragCoord / resolution;
                        
                        float fiber = sin(uv.y * 120.0 + uv.x * 2.0) * 0.5 + 0.5;
                        fiber = fiber * 0.06;
                        float grain = random(uv) * 0.08;
                        float texture = grain + fiber;
                        float vignette = 1.0 - length(uv - 0.5) * 0.3;
                        
                        half3 paper = half3(0.976, 0.965, 0.941);
                        half3 finalColor = (paper + texture) * vignette;
                        return half4(finalColor, 1.0);
                    }
                    """.trimIndent()
                )
            } catch (e: Exception) {
                logError("AGSL init failed", e)
                paperShader = null
            }
        }
    }

    private val fallbackPaperShader: Shader by lazy {
        val noiseSize = 256
        val noiseBitmap = Bitmap.createBitmap(noiseSize, noiseSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(noiseBitmap)
        val rand = Random()
        for (x in 0 until noiseSize) {
            for (y in 0 until noiseSize) {
                val grain = (rand.nextFloat() * 20).toInt()
                noiseBitmap.setPixel(x, y, Color.argb(grain, 0xF9, 0xF6, 0xF0))
            }
        }
        BitmapShader(noiseBitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    private fun gaussian(mean: Float, stdDev: Float, random: Random): Float =
        (random.nextGaussian() * stdDev + mean).toFloat()

    // ----- Error logging -----
    private var lastErrorMessage: String? = null
    private var lastErrorStackTrace: String? = null

    private fun logError(msg: String, e: Exception?) {
        lastErrorMessage = msg + (e?.let { ": ${it.message}" } ?: "")
        lastErrorStackTrace = e?.stackTraceToString() ?: "No stack trace"
        context?.let {
            try {
                val crashDir = File(it.cacheDir, "handwriting_logs")
                if (!crashDir.exists()) crashDir.mkdirs()
                val file = File(crashDir, "error_${System.currentTimeMillis()}.txt")
                FileOutputStream(file).use { fos ->
                    fos.write("$msg\n".toByteArray())
                    fos.write(lastErrorStackTrace!!.toByteArray())
                }
            } catch (ignored: Exception) {}
        }
    }

    fun getLastError(): Pair<String?, String?> = Pair(lastErrorMessage, lastErrorStackTrace)

    // ----- Public generation (crash‑safe) -----
    fun generateBitmap(text: String): Bitmap = generateBitmap(text, width, height)

    fun generateBitmap(text: String, canvasWidth: Int, canvasHeight: Int): Bitmap {
        return try {
            lastErrorMessage = null
            lastErrorStackTrace = null
            realGenerateBitmap(text, canvasWidth, canvasHeight)
        } catch (e: Exception) {
            logError("Generation failed", e)
            createErrorBitmap(canvasWidth, canvasHeight, e)
        }
    }

    private fun realGenerateBitmap(text: String, w: Int, h: Int): Bitmap {
        // Reset pen state for new document
        pen.pressure = 0.7f
        pen.slantOffset = 0f
        pen.tremor = 1.0f
        pen.fatigue = 0f
        baselineDrift = 0f

        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Paper background
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && paperShader != null) {
                (paperShader as android.graphics.RuntimeShader).setFloatUniform("resolution", w.toFloat(), h.toFloat())
                (paperShader as android.graphics.RuntimeShader).setFloatUniform("time", System.currentTimeMillis() / 1000f)
                val shaderPaint = Paint().apply { shader = paperShader }
                canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), shaderPaint)
            } else {
                val paint = Paint().apply { shader = fallbackPaperShader }
                canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
            }
        } catch (e: Exception) {
            canvas.drawColor(Color.parseColor("#F9F6F0"))
        }

        // Ruled lines
        var y = lineSpacing
        while (y < h) {
            val wiggle = sin(y * 0.02f) * 1.5f
            canvas.drawLine(wiggle, y, w.toFloat() + wiggle, y, linePaint)
            y += lineSpacing
        }
        canvas.drawLine(marginLeft, 0f, marginLeft, h.toFloat(), marginPaint)

        if (text.isNotEmpty()) {
            layoutAndDraw(canvas, text, w)
        }
        return bitmap
    }

    // ----- Mixed‑script tokenisation -----
    private fun tokenize(text: String): List<TokenWithType> {
        // First, split by word boundaries
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
                    // Split into clusters, mark each as part of the same Myanmar word
                    val clusters = splitMyanmarClusters(rawToken)
                    for ((index, cluster) in clusters.withIndex()) {
                        result.add(TokenWithType(cluster, isMyanmarCluster = true, isLastInMyanmarWord = index == clusters.lastIndex))
                    }
                } else {
                    result.add(TokenWithType(rawToken, isMyanmarCluster = false, isLastInMyanmarWord = true))
                }
            }
            start = end
            end = wordIterator.next()
        }
        return result
    }

    private data class TokenWithType(
        val text: String,
        val isMyanmarCluster: Boolean,
        val isLastInMyanmarWord: Boolean
    )

    private fun containsMyanmar(s: String): Boolean {
        for (ch in s) {
            if (ch in '\u1000'..'\u109F') return true
        }
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
        while (matcher.find()) {
            result.add(matcher.group())
        }
        return result
    }

    // ----- Layout & drawing (continuous pen state) -----
    private fun layoutAndDraw(canvas: Canvas, text: String, pageWidth: Int) {
        val tokenList = tokenize(text)
        val placements = computePlacements(tokenList, pageWidth)
        if (placements.isEmpty()) return

        var y = lineSpacing * 1.8f
        for (line in placements) {
            if (line.isEmpty()) {
                y += lineSpacing * (0.9f + globalRandom.nextFloat() * 0.2f)
                baselineDrift += gaussian(0f, driftStep, globalRandom).coerceIn(-driftClamp, driftClamp)
                updatePenState(rest = true)
                continue
            }

            var x = marginLeft + 20f + gaussian(0f, 2.5f, globalRandom)
            for ((index, wp) in line.withIndex()) {
                drawWholeWord(canvas, wp, x, y)
                x += wp.estimatedWidth
                if (index < line.size - 1) {
                    val nextWp = line[index + 1]
                    // Only add inter-word space if we are NOT between clusters of the same Myanmar word
                    if (!(wp.isPartOfMyanmarWord && nextWp.isPartOfMyanmarWord)) {
                        x += 6f + gaussian(0f, 2.5f, globalRandom).coerceIn(-4f, 4f)
                    }
                }
                updatePenState(rest = false)
            }
            y += lineSpacing * (0.9f + globalRandom.nextFloat() * 0.2f)
            baselineDrift += gaussian(0f, driftStep, globalRandom).coerceIn(-driftClamp, driftClamp)
            updatePenState(rest = true)
        }
    }

    private fun computePlacements(tokens: List<TokenWithType>, pageWidth: Int): List<List<WordPlacement>> {
        val lines = mutableListOf<MutableList<WordPlacement>>()
        var currentLine = mutableListOf<WordPlacement>()
        var currentWidth = 0f
        var wordIndex = 0

        for (tok in tokens) {
            if (tok.text == "\n") {
                if (currentLine.isNotEmpty()) lines.add(currentLine)
                lines.add(mutableListOf())
                currentLine = mutableListOf()
                currentWidth = 0f
                continue
            }

            val seed = (tok.text.hashCode() * 31 + wordIndex) and 0x7fffffff
            wordIndex++

            val rand = Random(seed.toLong())
            val pressure = (pen.pressure + gaussian(0f, 0.05f, rand)).coerceIn(0.4f, 0.95f)
            val scaleX = 1f + gaussian(0f, 0.07f + pen.fatigue * 0.04f, rand)
            val scaleY = 1f + gaussian(0f, 0.05f, rand)
            val slantOffset = pen.slantOffset + gaussian(0f, 3.0f, rand)
            val rotation = gaussian(0f, 2.0f + pen.fatigue * 1.5f, rand)
            val dx = gaussian(0f, 2.0f, rand)
            val dy = gaussian(0f, 2.0f, rand)
            val tremor = pen.tremor * (1f + pen.fatigue * 0.5f)

            val transforms = WordTransforms(pressure, scaleX, scaleY, slantOffset, rotation, dx, dy, tremor)
            val nativeWidth = textPaint.measureText(tok.text)
            val finalWidth = nativeWidth * scaleX

            if (currentWidth + finalWidth > pageWidth - marginRight && currentLine.isNotEmpty()) {
                lines.add(currentLine)
                currentLine = mutableListOf()
                currentWidth = 0f
            }
            currentLine.add(
                WordPlacement(
                    text = tok.text,
                    seed = seed,
                    estimatedWidth = finalWidth,
                    transforms = transforms,
                    isPartOfMyanmarWord = tok.isMyanmarCluster && !tok.isLastInMyanmarWord  // true for non-final clusters of a word
                )
            )
            currentWidth += finalWidth
            evolvePenState()
        }
        if (currentLine.isNotEmpty()) lines.add(currentLine)
        return lines
    }

    // ----- drawWholeWord with empty‑path fallback -----
    private fun drawWholeWord(canvas: Canvas, wp: WordPlacement, startX: Float, baselineY: Float) {
        val t = wp.transforms

        val baseRed = Color.red(baseInkColor)
        val baseGreen = Color.green(baseInkColor)
        val baseBlue = Color.blue(baseInkColor)

        val alpha = (150 + t.pressure * 105).toInt().coerceIn(40, 255)
        val density = (0.5f + t.pressure * 0.5f).coerceIn(0.5f, 1f)

        val washRand = Random((wp.seed and 0x7FFFFFFF).toLong())
        val washR = (baseRed + gaussian(0f, 8f, washRand)).coerceIn(0f, 255f)
        val washG = (baseGreen + gaussian(0f, 8f, washRand)).coerceIn(0f, 255f)
        val washB = (baseBlue + gaussian(0f, 8f, washRand)).coerceIn(0f, 255f)

        val finalColor = Color.argb(
            alpha,
            (washR * density).toInt(),
            (washG * density).toInt(),
            (washB * density).toInt()
        )
        textPaint.color = finalColor

        val rawPath = Path()
        textPaint.getTextPath(wp.text, 0, wp.text.length, 0f, 0f, rawPath)

        canvas.save()
        val matrix = Matrix()
        val slantDeg = globalSlant + t.slantOffset
        val skewX = -tan(Math.toRadians(slantDeg.toDouble())).toFloat()
        matrix.postSkew(skewX, 0f)
        matrix.postRotate(t.rotation)
        matrix.postScale(t.scaleX, t.scaleY)
        matrix.postTranslate(startX + t.dx, baselineY + baselineDrift + t.dy)
        canvas.concat(matrix)

        if (rawPath.isEmpty) {
            // FALLBACK: draw text directly with transformations and ink spread
            val spreadCount = 4
            val spreadRand = Random((wp.seed * 31).toLong())
            for (i in 0 until spreadCount) {
                val offsetX = gaussian(0f, 0.4f, spreadRand)
                val offsetY = gaussian(0f, 0.4f, spreadRand)
                val spreadAlpha = (alpha * 0.12f).toInt().coerceIn(5, 20)
                spreadPaint.color = Color.argb(spreadAlpha, washR.toInt(), washG.toInt(), washB.toInt())
                canvas.save()
                canvas.translate(offsetX, offsetY)
                canvas.drawText(wp.text, 0f, 0f, spreadPaint)
                canvas.restore()
            }
            canvas.drawText(wp.text, 0f, 0f, textPaint)
        } else {
            // Normal path‑based warp rendering
            val bounds = RectF()
            rawPath.computeBounds(bounds, true)
            val wordWidth = bounds.width()
            val wordHeight = bounds.height()

            val baseStrength = when {
                wp.text.length <= 2 -> 2.5f
                wp.text.length <= 4 -> 2.0f
                else -> 1.5f
            }
            val lengthFactor = 1f + (wp.text.length - 1) * 0.06f
            val warpStrength = baseStrength * lengthFactor * t.tremor

            val warpSeed = wp.seed xor 0x5A5A5A5A
            val warpedPath = warpPathWithGrid(
                rawPath,
                wordWidth,
                wordHeight,
                warpStrength,
                warpSeed,
                wp.text.length
            )

            val spreadCount = 8
            val spreadRand = Random((wp.seed * 31).toLong())
            for (i in 0 until spreadCount) {
                val offsetX = gaussian(0f, 0.6f, spreadRand)
                val offsetY = gaussian(0f, 0.6f, spreadRand)
                val spreadAlpha = (alpha * 0.15f * (1f - i.toFloat() / spreadCount)).toInt().coerceIn(5, 30)
                spreadPaint.color = Color.argb(spreadAlpha, washR.toInt(), washG.toInt(), washB.toInt())

                canvas.save()
                canvas.translate(offsetX, offsetY)
                canvas.drawPath(warpedPath, spreadPaint)
                canvas.restore()
            }
            canvas.drawPath(warpedPath, textPaint)
        }

        canvas.restore()
    }

    private fun warpPathWithGrid(
        source: Path,
        wordWidth: Float,
        wordHeight: Float,
        strength: Float,
        seed: Int,
        tokenLength: Int
    ): Path {
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
        for (r in 0..gridRows) {
            for (c in 0..gridCols) {
                gridX[r][c] = gaussian(0f, strength, rand)
                gridY[r][c] = gaussian(0f, strength, rand)
            }
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

                val col = (pos[0] / cellWidth).coerceIn(0f, gridCols.toFloat() - 1f)
                val row = (pos[1] / cellHeight).coerceIn(0f, gridRows.toFloat() - 1f)

                val c0 = col.toInt()
                val r0 = row.toInt()
                val c1 = min(c0 + 1, gridCols)
                val r1 = min(r0 + 1, gridRows)

                val fx = col - c0
                val fy = row - r0

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

    // ----- Pen state evolution -----
    private fun updatePenState(rest: Boolean) {
        if (rest) {
            pen.fatigue = (pen.fatigue - 0.005f).coerceAtLeast(0f)
        } else {
            pen.fatigue = (pen.fatigue + 0.003f).coerceAtMost(0.4f)
        }
        pen.pressure = (0.7f - pen.fatigue * 0.3f + gaussian(0f, 0.03f, globalRandom)).coerceIn(0.4f, 0.9f)
        pen.slantOffset = (pen.slantOffset + gaussian(0f, 0.1f, globalRandom)).coerceIn(-4f, 4f)
        pen.tremor = (1.0f + pen.fatigue * 0.8f).coerceAtMost(1.8f)
    }

    private fun evolvePenState() {
        pen.fatigue = (pen.fatigue + 0.0005f).coerceAtMost(0.4f)
        pen.pressure = (pen.pressure - 0.0002f + gaussian(0f, 0.01f, globalRandom)).coerceIn(0.4f, 0.9f)
        pen.slantOffset = (pen.slantOffset + gaussian(0f, 0.05f, globalRandom)).coerceIn(-4f, 4f)
        pen.tremor = (1.0f + pen.fatigue * 0.8f).coerceAtMost(1.8f)
    }

    // ----- Data classes -----
    private data class WordTransforms(
        val pressure: Float,
        val scaleX: Float,
        val scaleY: Float,
        val slantOffset: Float,
        val rotation: Float,
        val dx: Float,
        val dy: Float,
        val tremor: Float
    )

    private data class WordPlacement(
        val text: String,
        val seed: Int,
        val estimatedWidth: Float,
        val transforms: WordTransforms,
        val isPartOfMyanmarWord: Boolean = false   // true if this token is a non‑final cluster of a Myanmar word
    )

    private fun createErrorBitmap(w: Int, h: Int, e: Exception): Bitmap {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED; textSize = 28f }
        canvas.drawText("Generation failed: ${e.message}", 30f, 100f, paint)
        canvas.drawText("Check notification for details", 30f, 140f, paint)
        return bitmap
    }
}