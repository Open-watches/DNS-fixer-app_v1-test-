package com.community.dnsfix.handwriting

import android.graphics.*
import android.os.Build

class PaperRenderer(private val width: Int, private val height: Int) {
    private var paperShader: Shader? = null

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                paperShader = android.graphics.RuntimeShader("""
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
                """.trimIndent())
            } catch (_: Exception) { paperShader = null }
        }
    }

    private val fallbackShader: Shader by lazy {
        val noiseSize = 256
        val noiseBitmap = Bitmap.createBitmap(noiseSize, noiseSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(noiseBitmap)
        val rand = kotlin.random.Random
        for (x in 0 until noiseSize) {
            for (y in 0 until noiseSize) {
                val grain = (rand.nextFloat() * 20).toInt()
                noiseBitmap.setPixel(x, y, Color.argb(grain, 0xF9, 0xF6, 0xF0))
            }
        }
        BitmapShader(noiseBitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    fun draw(canvas: Canvas) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && paperShader != null) {
                (paperShader as android.graphics.RuntimeShader).setFloatUniform("resolution", width.toFloat(), height.toFloat())
                (paperShader as android.graphics.RuntimeShader).setFloatUniform("time", System.currentTimeMillis() / 1000f)
                val paint = Paint().apply { shader = paperShader }
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
        fun drawLinesAndMargin(canvas: Canvas, lineSpacing: Float, marginLeft: Float, width: Float, height: Float) {
            val linePaint = Paint().apply { color = Color.parseColor("#A5C6E8"); strokeWidth = 2f }
            val marginPaint = Paint().apply { color = Color.parseColor("#E8A5A5"); strokeWidth = 3f }
            var y = lineSpacing
            while (y < height) {
                val wiggle = kotlin.math.sin(y * 0.02f) * 1.5f
                canvas.drawLine(wiggle, y, width + wiggle, y, linePaint)
                y += lineSpacing
            }
            canvas.drawLine(marginLeft, 0f, marginLeft, height, marginPaint)
        }
    }
}