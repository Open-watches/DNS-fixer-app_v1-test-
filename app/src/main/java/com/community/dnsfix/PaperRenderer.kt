package com.community.dnsfix.handwriting

import android.graphics.*
import android.os.Build

class PaperRenderer(private var width: Int, private var height: Int) {
    private var paperShader: Shader? = null

    init {
        initializeShader()
    }

    /** Re-initializes the shader dimensions if the paper size changes dynamically. */
    fun updateDimensions(newWidth: Int, newHeight: Int) {
        if (width != newWidth || height != newHeight) {
            this.width = newWidth
            this.height = newHeight
            initializeShader()
        }
    }

    private fun initializeShader() {
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
        val noiseSize = 128 // Tiling 128x128 saves 75% memory allocations over 256x256
        val noiseBitmap = Bitmap.createBitmap(noiseSize, noiseSize, Bitmap.Config.ARGB_8888)
        
        // Allocate a flat array to completely bypass JNI overhead loops
        val pixels = IntArray(noiseSize * noiseSize)
        val rand = kotlin.random.Random
        
        for (i in pixels.indices) {
            val grain = (rand.nextFloat() * 20).toInt()
            pixels[i] = Color.argb(grain, 0xF9, 0xF6, 0xF0)
        }
        
        // Single native memory execution transfer across the JNI bridge
        noiseBitmap.setPixels(pixels, 0, noiseSize, 0, 0, noiseSize, noiseSize)
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
        /**
         * Draws realistic ruled lines and page guidelines, leaving a pristine top 
         * and bottom margin blank space for out-of-bounds components (e.g. page numbers).
         */
        fun drawLinesAndMargin(
            canvas: Canvas,
            width: Float,
            height: Float,
            marginTop: Float,
            marginBottom: Float,
            marginLeft: Float,
            lineSpacing: Float
        ) {
            val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#A5C6E8"); strokeWidth = 2f }
            val marginPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E8A5A5"); strokeWidth = 3f }
            
            // Render ruled notebook lines strictly within text flow bounds
            var y = marginTop + lineSpacing
            val maxLineY = height - marginBottom

            while (y < maxLineY) {
                // Your excellent sine-wave wiggle remains perfectly preserved
                val wiggle = kotlin.math.sin(y * 0.02f) * 1.5f
                canvas.drawLine(wiggle, y, width + wiggle, y, linePaint)
                y += lineSpacing
            }

            // Draw vertical margin binder line line rule
            canvas.drawLine(marginLeft, 0f, marginLeft, height, marginPaint)
        }
    }
}
