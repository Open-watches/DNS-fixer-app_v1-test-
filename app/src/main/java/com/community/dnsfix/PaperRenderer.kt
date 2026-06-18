package com.community.dnsfix.handwriting

import android.graphics.*
import android.os.Build
import kotlin.math.sin
import kotlin.random.Random

class PaperRenderer(
    private val width: Int,
    private val height: Int,
    /** Seed for the CPU fallback texture. Default = 42 (consistent paper). */
    private val cpuSeed: Int = 42,
    /** Seed for line tremor. Default = system time (per‑session variation). */
    private val tremorSeed: Long = System.currentTimeMillis()
) {
    // ---- GPU / CPU paper base ----
    private val runtimeShader: RuntimeShader? = createRuntimeShader()
    private val fallbackShader: Shader by lazy { createFallbackShader() }

    // ---- Pre‑allocated paints ----
    // Base paper: reuse a single Paint – shader assigned once in init
    private val basePaint = Paint().apply {
        if (runtimeShader != null) shader = runtimeShader
    }
    private val fallbackPaint = Paint().apply { shader = fallbackShader }

    // Overlays
    private val vignettePaint: Paint
    private val cornerTintPaint: Paint

    // Ruled lines & margin
    private val linePaint: Paint
    private val marginPaint: Paint
    private val marginBleedPaint: Paint
    private val linePath = Path()
    private val lineTremorRandom = Random(tremorSeed)

    init {
        // Vignette
        val cx = width / 2f
        val cy = height / 2f
        val radius = Math.hypot(cx.toDouble(), cy.toDouble()).toFloat()
        val vignetteGradient = RadialGradient(
            cx, cy, radius,
            intArrayOf(Color.TRANSPARENT, Color.argb(18, 0, 0, 0)),
            floatArrayOf(0.6f, 1.0f),
            Shader.TileMode.CLAMP
        )
        vignettePaint = Paint().apply { shader = vignetteGradient }

        // Corner tint
        val cornerGradient = LinearGradient(
            0f, 0f, width.toFloat(), height.toFloat(),
            intArrayOf(
                Color.argb(8, 255, 224, 192),
                Color.argb(8, 208, 224, 255)
            ),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        cornerTintPaint = Paint().apply { shader = cornerGradient }

        // Ruled lines (fade at edges)
        val lineGradient = LinearGradient(
            0f, 0f, width.toFloat(), 0f,
            intArrayOf(
                Color.argb(0, 0xA5, 0xC6, 0xE8),
                Color.argb(255, 0xA5, 0xC6, 0xE8),
                Color.argb(255, 0xA5, 0xC6, 0xE8),
                Color.argb(0, 0xA5, 0xC6, 0xE8)
            ),
            floatArrayOf(0f, 0.25f, 0.75f, 1f),
            Shader.TileMode.CLAMP
        )
        linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = lineGradient
            strokeWidth = 2f
        }

        // Margin with ink bleed
        marginPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E8A5A5")
            strokeWidth = 3f
        }
        marginBleedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(40, 0xE8, 0xA5, 0xA5)
            strokeWidth = 7f
        }
    }

    private fun createRuntimeShader(): RuntimeShader? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        return try { RuntimeShader(PAPER_SHADER_SOURCE) } catch (_: Exception) { null }
    }

    private fun createFallbackShader(): Shader {
        val size = 256
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(size * size)
        val rand = Random(cpuSeed)   // configurable seed

        // Smooth value noise grid (4×4)
        val gridSize = 4
        val grid = Array(gridSize + 1) { FloatArray(gridSize + 1) }
        for (gy in 0..gridSize) {
            for (gx in 0..gridSize) {
                grid[gy][gx] = rand.nextFloat()
            }
        }

        val baseR = 0xF9; val baseG = 0xF6; val baseB = 0xF0
        for (y in 0 until size) {
            val ny = y.toFloat() / size * gridSize
            val iy = ny.toInt()
            val fy = ny - iy
            val row0 = grid[iy % gridSize]
            val row1 = grid[(iy + 1) % gridSize]

            for (x in 0 until size) {
                val nx = x.toFloat() / size * gridSize
                val ix = nx.toInt()
                val fx = nx - ix

                val v0 = lerp(row0[ix % gridSize], row0[(ix + 1) % gridSize], fx)
                val v1 = lerp(row1[ix % gridSize], row1[(ix + 1) % gridSize], fx)
                val large = lerp(v0, v1, fy) * 14f

                val grain = rand.nextFloat() * 5f
                val variation = (large + grain).toInt().coerceIn(0, 19)
                val offset = (variation - 9.5f) * 0.55f

                pixels[y * size + x] = Color.rgb(
                    (baseR + offset).toInt().coerceIn(0, 255),
                    (baseG + offset).toInt().coerceIn(0, 255),
                    (baseB + offset).toInt().coerceIn(0, 255)
                )
            }
        }
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size)

        val half = size / 2
        val down = Bitmap.createScaledBitmap(bitmap, half, half, true)
        bitmap.recycle()
        val blurred = Bitmap.createScaledBitmap(down, size, size, true)
        down.recycle()
        return BitmapShader(blurred, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    fun draw(canvas: Canvas) {
        try {
            val shader = runtimeShader
            if (shader != null) {
                shader.setFloatUniform("resolution", width.toFloat(), height.toFloat())
                // basePaint already has the shader set in init
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), basePaint)
            } else {
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fallbackPaint)
            }
        } catch (_: Exception) {
            canvas.drawColor(Color.parseColor("#F9F6F0"))
        }

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), vignettePaint)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), cornerTintPaint)
    }

    fun drawLinesAndMargin(
        canvas: Canvas,
        marginTop: Float,
        marginBottom: Float,
        marginLeft: Float,
        lineSpacing: Float
    ) {
        var y = marginTop + lineSpacing
        val maxY = height - marginBottom
        val segment = 20f

        while (y < maxY) {
            linePath.reset()
            var x = 0f
            linePath.moveTo(x, y)
            while (x < width) {
                x += segment
                val tremor = (lineTremorRandom.nextFloat() - 0.5f) * 2.0f
                linePath.lineTo(x.coerceAtMost(width.toFloat()), y + tremor)
            }
            canvas.drawPath(linePath, linePaint)
            y += lineSpacing
        }

        canvas.drawLine(marginLeft, 0f, marginLeft, height.toFloat(), marginBleedPaint)
        canvas.drawLine(marginLeft, 0f, marginLeft, height.toFloat(), marginPaint)
    }

    companion object {
        // GPU shader (unchanged, but could be aligned to smooth value noise later)
        private const val PAPER_SHADER_SOURCE = """
            uniform float2 resolution;

            half4 main(float2 fragCoord) {
                float2 uv = fragCoord / resolution;

                // Randomised laid‑paper fibres
                float f1 = sin(uv.y * 137.0 - uv.x * 28.0) * 0.5 + 0.5;
                float f2 = sin(uv.y * 143.0 + uv.x * 33.0 + 2.7) * 0.5 + 0.5;
                float f3 = sin(uv.y * 151.0 - uv.x * 25.0 + 5.1) * 0.5 + 0.5;
                float noise = fract(sin(dot(uv * 100.0, float2(127.1, 311.7))) * 43758.5453);
                float fiber = (f1 * 0.35 + f2 * 0.35 + f3 * 0.3) * (0.8 + noise * 0.4);
                fiber *= 0.06;

                // Fine grain
                float grain = fract(sin(dot(uv * resolution, float2(12.9898, 78.233))) * 43758.5453);
                grain *= 0.05;

                // Large‑scale unevenness
                float2 bigSeed = floor(uv * 4.0);
                float largeNoise = fract(sin(dot(bigSeed, float2(127.1, 311.7))) * 43758.5453);
                largeNoise *= 0.035;

                float texture = grain + fiber + largeNoise;
                float vignette = 1.0 - length(uv - 0.5) * 0.28;
                half3 paper = half3(0.976, 0.965, 0.941);
                half3 finalColor = (paper + texture) * vignette;
                return half4(finalColor, 1.0);
            }
        """.trimIndent()
    }
}