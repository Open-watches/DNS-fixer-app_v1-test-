package com.community.dnsfix.handwriting

import android.graphics.*
import android.os.Build
import kotlin.math.sin
import kotlin.random.Random

class PaperRenderer(
    private val width: Int,
    private val height: Int
) {
    private val runtimeShader: RuntimeShader? = createRuntimeShader()

    private fun createRuntimeShader(): RuntimeShader? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        return try {
            RuntimeShader(PAPER_SHADER_SOURCE)
        } catch (_: Exception) { null }
    }

    private val fallbackShader: Shader by lazy {
        val size = 256
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(size * size)
        val rand = Random(42)

        val baseR = 0xF9
        val baseG = 0xF6
        val baseB = 0xF0

        for (y in 0 until size) {
            val ny = y.toFloat() / size
            for (x in 0 until size) {
                val nx = x.toFloat() / size
                val wave = (sin(nx * 2.0 * Math.PI) * sin(ny * 2.0 * Math.PI) * 0.5f + 0.5f) * 12f
                val grain = rand.nextFloat() * 6f
                val variation = (wave + grain).toInt().coerceIn(0, 20)

                val offset = (variation - 10) * 0.6f
                val r = (baseR + offset).toInt().coerceIn(0, 255)
                val g = (baseG + offset).toInt().coerceIn(0, 255)
                val b = (baseB + offset).toInt().coerceIn(0, 255)

                pixels[y * size + x] = Color.rgb(r, g, b)
            }
        }
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size)

        val halfSize = size / 2
        val down = Bitmap.createScaledBitmap(bitmap, halfSize, halfSize, true)
        bitmap.recycle()
        val blurred = Bitmap.createScaledBitmap(down, size, size, true)
        down.recycle()

        BitmapShader(blurred, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    fun draw(canvas: Canvas) {
        try {
            if (runtimeShader != null) {
                runtimeShader.setFloatUniform("resolution", width.toFloat(), height.toFloat())
                val paint = Paint().apply { shader = runtimeShader }
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            } else {
                val paint = Paint().apply { shader = fallbackShader }
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            }
        } catch (_: Exception) {
            canvas.drawColor(Color.parseColor("#F9F6F0"))
        }
    }

    companion object {
        // Reusable path, fixed from 'const val' to 'private val'
        private val linePath = Path()

        @JvmStatic
        fun drawLinesAndMargin(
            canvas: Canvas,
            width: Float,
            height: Float,
            marginTop: Float,
            marginBottom: Float,
            marginLeft: Float,
            lineSpacing: Float
        ) {
            val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#A5C6E8")
                strokeWidth = 2f
            }
            val marginPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#E8A5A5")
                strokeWidth = 3f
            }

            var y = marginTop + lineSpacing
            val maxLineY = height - marginBottom
            val rand = Random(123)
            val segmentLength = 20f

            while (y < maxLineY) {
                linePath.reset()
                var x = 0f
                linePath.moveTo(x, y)
                while (x < width) {
                    x += segmentLength
                    val tremor = (rand.nextFloat() - 0.5f) * 2.0f
                    linePath.lineTo(x.coerceAtMost(width), y + tremor)
                }
                canvas.drawPath(linePath, linePaint)
                y += lineSpacing
            }

            // Vertical margin
            canvas.drawLine(marginLeft, 0f, marginLeft, height, marginPaint)
        }

        private const val PAPER_SHADER_SOURCE = """
            uniform float2 resolution;
            half4 main(float2 fragCoord) {
                float2 uv = fragCoord / resolution;
                float fiber = sin(uv.y * 140.0 - uv.x * 30.0) * 0.5 + 0.5;
                fiber = fiber * 0.055;
                float2 seed = uv * resolution;
                float grain = fract(sin(dot(seed, float2(12.9898, 78.233))) * 43758.5453);
                grain = grain * 0.06;
                float texture = grain + fiber;
                float vignette = 1.0 - length(uv - 0.5) * 0.3;
                half3 paper = half3(0.976, 0.965, 0.941);
                half3 finalColor = (paper + texture) * vignette;
                return half4(finalColor, 1.0);
            }
        """.trimIndent()
    }
}