/**
 * 背景亮度采样器（Regular 材质的自适应染色数据源）
 *
 * Apple 的 Regular 玻璃会持续感知背后内容的明暗，在"亮玻璃/暗玻璃"两套
 * 外观之间平滑过渡，并联动前景内容颜色。GPU 管线没有任何 CPU 侧像素，
 * 因此这里周期性地把父视图绘制到一张 24×24 的小位图上估算平均亮度：
 *
 * - 代价 ≈ 一次父视图软件绘制（每 350ms 一次，省电模式下 1000ms）
 * - CPU 管线本来就有背景位图，直接走 [measureBitmap] 零额外开销
 *
 * 输出经过 EMA 平滑；"是否亮背景"的翻转带滞回（0.60 / 0.45），
 * 避免背景亮度在阈值附近时外观来回跳变。
 *
 * 注意：透镜管线（API 33+）的染色已改为着色器内逐像素自适应，不再消费这里
 * 的全局色值；本采样器仍是 glassAppearanceListener / isOverLightBackground
 * （前景内容换色）与 CPU 管线全局染色回退的数据源。
 */
package com.example.liquidglass

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View

internal class BackdropLuminanceMeter(
    private val view: LiquidGlassView,
    private val onLuminance: (Float) -> Unit
) {
    companion object {
        private const val SAMPLE_SIZE = 24
        private const val EMA_ALPHA = 0.35f
        private const val OVER_LIGHT_ON = 0.60f
        private const val OVER_LIGHT_OFF = 0.45f

        /**
         * 稀疏采样一张已有位图的平均亮度（CPU 管线复用背景位图时调用）
         */
        fun measureBitmap(bitmap: Bitmap): Float {
            val w = bitmap.width
            val h = bitmap.height
            if (w <= 0 || h <= 0 || bitmap.isRecycled) return 0.5f
            val grid = 8
            val stepX = (w / grid).coerceAtLeast(1)
            val stepY = (h / grid).coerceAtLeast(1)
            var sum = 0f
            var count = 0
            var y = stepY / 2
            while (y < h) {
                var x = stepX / 2
                while (x < w) {
                    val c = bitmap.getPixel(x, y)
                    sum += 0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)
                    count++
                    x += stepX
                }
                y += stepY
            }
            return if (count > 0) (sum / count / 255f).coerceIn(0f, 1f) else 0.5f
        }
    }

    var intervalMs = 350L

    /** EMA 平滑后的亮度（0 = 全黑，1 = 全白） */
    var smoothedLuminance = 0.5f
        private set

    /** 当前是否判定为亮背景（带滞回） */
    var isOverLight = false
        private set

    private var running = false
    private var sampleBitmap: Bitmap? = null
    private val location = IntArray(2)
    private val parentLocation = IntArray(2)

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            sampleFromParent()
            view.postDelayed(this, intervalMs)
        }
    }

    fun start() {
        if (running) return
        running = true
        view.postDelayed(tick, intervalMs)
    }

    fun stop() {
        running = false
        view.removeCallbacks(tick)
        sampleBitmap?.recycle()
        sampleBitmap = null
    }

    /**
     * GPU 管线的采样路径：把父视图缩绘到 24×24 位图上估算亮度
     */
    private fun sampleFromParent() {
        // 与捕获路径取同一个背景来源，否则指定 backdropSource 后染色会读错内容
        val parent = view.backdropView ?: return
        val w = view.width
        val h = view.height
        if (w <= 0 || h <= 0) return

        val bmp = sampleBitmap?.takeIf { !it.isRecycled }
            ?: Bitmap.createBitmap(SAMPLE_SIZE, SAMPLE_SIZE, Bitmap.Config.ARGB_8888).also { sampleBitmap = it }
        bmp.eraseColor(Color.GRAY)

        try {
            val canvas = Canvas(bmp)
            view.getLocationOnScreen(location)
            parent.getLocationOnScreen(parentLocation)
            val offsetX = (location[0] - parentLocation[0]).toFloat()
            val offsetY = (location[1] - parentLocation[1]).toFloat()

            // 非等比缩放到 24×24：亮度估算不需要保持纵横比
            canvas.scale(SAMPLE_SIZE.toFloat() / w, SAMPLE_SIZE.toFloat() / h)
            canvas.translate(-offsetX, -offsetY)

            view.isCapturingBackdrop = true
            LiquidGlassView.isLuminanceSampling = true
            try {
                parent.draw(canvas)
            } finally {
                LiquidGlassView.isLuminanceSampling = false
                view.isCapturingBackdrop = false
            }
        } catch (_: Exception) {
            return
        }

        submit(measureBitmap(bmp))
    }

    /**
     * 提交一个亮度样本（CPU 管线用 [measureBitmap] 算好后从这里进）
     */
    fun submit(luminance: Float) {
        smoothedLuminance += (luminance - smoothedLuminance) * EMA_ALPHA

        // 带滞回的亮背景判定
        if (!isOverLight && smoothedLuminance > OVER_LIGHT_ON) {
            isOverLight = true
        } else if (isOverLight && smoothedLuminance < OVER_LIGHT_OFF) {
            isOverLight = false
        }

        onLuminance(smoothedLuminance)
    }
}
