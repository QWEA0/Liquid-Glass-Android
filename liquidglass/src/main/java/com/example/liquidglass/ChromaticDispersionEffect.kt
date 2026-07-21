/**
 * 色散效果处理器 (Chromatic Dispersion Effect) - 基于物理光学原理
 *
 * 使用 Snell 定律模拟真实的玻璃色散效果，不同波长的光有不同的折射率
 *
 * 物理原理：
 * - 基于 Snell 定律：n₁ sin(θ₁) = n₂ sin(θ₂)
 * - 不同波长的折射率：N_R = 0.98, N_G = 1.0, N_B = 1.02
 * - 边缘距离越近，折射效果越强
 * - 沿法线方向应用折射偏移
 *
 * 适用场景：
 * - 玻璃材质模拟
 * - 水晶、钻石等透明物体
 * - 真实感光学效果
 * - 边缘高光效果
 */
package com.example.liquidglass

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.Log
import kotlin.math.min

/**
 * 色散效果处理器
 */
class ChromaticDispersionEffect {

    companion object {
        private const val TAG = "ChromaticDispersion"

        /**
         * 效果预设
         */
        object Presets {
            /** 玻璃效果 */
            data class Glass(
                val refThickness: Float = 100f,
                val refFactor: Float = 1.5f,
                val refDispersion: Float = 7f
            )

            /** 钻石效果 */
            data class Diamond(
                val refThickness: Float = 80f,
                val refFactor: Float = 2.4f,
                val refDispersion: Float = 15f
            )

            /** 水晶效果 */
            data class Crystal(
                val refThickness: Float = 120f,
                val refFactor: Float = 1.8f,
                val refDispersion: Float = 10f
            )

            /** 彩虹效果 */
            data class Rainbow(
                val refThickness: Float = 150f,
                val refFactor: Float = 1.3f,
                val refDispersion: Float = 20f
            )

            /** 轻微色散 */
            data class Subtle(
                val refThickness: Float = 200f,
                val refFactor: Float = 1.2f,
                val refDispersion: Float = 3f
            )
        }
    }

    // 双线性插值开关（默认启用）
    var useBilinearInterpolation: Boolean = true

    // 设备像素比（默认 1.0）
    var devicePixelRatio: Float = 1.0f

    // 复用贴图，避免每帧创建新贴图
    private var cachedEdgeMap: Bitmap? = null
    private var cachedNormalMap: Bitmap? = null
    private var cachedSize = Pair(0, 0)

    /**
     * 应用色散效果
     *
     * @param source 原始图像
     * @param refThickness 折射厚度（像素，推荐 50-200）
     * @param refFactor 折射系数（推荐 1.2-2.0，玻璃约 1.5）
     * @param refDispersion 色散增益（推荐 0-20，玻璃约 7）
     * @param downscale 降采样比例（0.5 = 处理速度提升 4 倍）
     * @param cornerRadius 圆角半径（像素，0 = 矩形）
     * @param useNormalMap 是否使用法线贴图（默认 false，使用径向法线）
     * @return 应用色散后的图像
     */
    fun apply(
        source: Bitmap,
        refThickness: Float = 100f,
        refFactor: Float = 1.5f,
        refDispersion: Float = 7f,
        downscale: Float = 0.5f,
        cornerRadius: Float = 0f,
        useNormalMap: Boolean = false
    ): Bitmap {
        val startTime = System.currentTimeMillis()

        // 降采样
        val processWidth = (source.width * downscale).toInt()
        val processHeight = (source.height * downscale).toInt()

        Log.d(TAG, "Processing ${source.width}x${source.height} -> ${processWidth}x${processHeight} (downscale=$downscale)")

        val smallSource = if (downscale < 1.0f) {
            Bitmap.createScaledBitmap(source, processWidth, processHeight, true)
        } else {
            source
        }

        // 应用圆角裁剪（设置圆角外的像素为透明）
        val clippedSource = if (cornerRadius > 0) {
            // 限制圆角半径不超过图像尺寸的一半（避免过大的圆角）
            val scaledRadius = cornerRadius * downscale
            val maxRadius = min(processWidth, processHeight) / 2f
            val clampedRadius = min(scaledRadius, maxRadius)
            applyRoundedCornerClip(smallSource, clampedRadius)
        } else {
            smallSource
        }

        // 生成或复用边缘距离贴图（基于 Alpha 通道检测边缘）
        val edgeMap = if (cachedEdgeMap != null && cachedSize == Pair(processWidth, processHeight)) {
            cachedEdgeMap!!
        } else {
            Log.d(TAG, "Generating edge distance map from alpha channel...")
            val map = DispersionMapGenerator.generateEdgeDistanceMapFromAlpha(clippedSource)
            cachedEdgeMap = map
            cachedSize = Pair(processWidth, processHeight)
            map
        }

        // 生成或复用法线贴图（如果需要）
        val normalMap = if (useNormalMap) {
            if (cachedNormalMap != null && cachedSize == Pair(processWidth, processHeight)) {
                cachedNormalMap!!
            } else {
                Log.d(TAG, "Generating normal map...")
                val map = DispersionMapGenerator.generateRadialNormalMap(processWidth, processHeight)
                cachedNormalMap = map
                map
            }
        } else {
            null
        }

        // 创建结果 Bitmap
        val result = Bitmap.createBitmap(processWidth, processHeight, Bitmap.Config.ARGB_8888)

        // 调用 C++ 实现
        val processingStart = System.currentTimeMillis()
        NativeChromaticDispersion.chromaticDispersionInplace(
            source = smallSource,
            edgeDistance = edgeMap,
            normalMap = normalMap,
            result = result,
            refThickness = refThickness * downscale,  // 根据降采样调整参数
            refFactor = refFactor,
            refDispersion = refDispersion,
            dpr = devicePixelRatio,
            useBilinear = useBilinearInterpolation
        )
        val processingTime = System.currentTimeMillis() - processingStart

        // 上采样回原始分辨率
        val finalResult = if (downscale < 1.0f) {
            val upscaled = Bitmap.createScaledBitmap(result, source.width, source.height, true)
            result.recycle()
            upscaled
        } else {
            result
        }

        // 清理
        if (downscale < 1.0f && smallSource != source) {
            smallSource.recycle()
        }

        val totalTime = System.currentTimeMillis() - startTime
        Log.d(TAG, "Dispersion effect applied: processing=${processingTime}ms, total=${totalTime}ms")

        return finalResult
    }

    /**
     * 应用预设效果
     */
    fun applyPreset(
        source: Bitmap,
        preset: String,
        downscale: Float = 0.5f,
        cornerRadius: Float = 0f
    ): Bitmap {
        return when (preset.lowercase()) {
            "glass" -> {
                val p = Presets.Glass()
                apply(source, p.refThickness, p.refFactor, p.refDispersion, downscale, cornerRadius)
            }
            "diamond" -> {
                val p = Presets.Diamond()
                apply(source, p.refThickness, p.refFactor, p.refDispersion, downscale, cornerRadius)
            }
            "crystal" -> {
                val p = Presets.Crystal()
                apply(source, p.refThickness, p.refFactor, p.refDispersion, downscale, cornerRadius)
            }
            "rainbow" -> {
                val p = Presets.Rainbow()
                apply(source, p.refThickness, p.refFactor, p.refDispersion, downscale, cornerRadius)
            }
            "subtle" -> {
                val p = Presets.Subtle()
                apply(source, p.refThickness, p.refFactor, p.refDispersion, downscale, cornerRadius)
            }
            else -> {
                Log.w(TAG, "Unknown preset: $preset, using default (glass)")
                val p = Presets.Glass()
                apply(source, p.refThickness, p.refFactor, p.refDispersion, downscale, cornerRadius)
            }
        }
    }

    /**
     * 应用圆角裁剪（设置圆角外的像素为透明）
     *
     * @param source 原始图像
     * @param cornerRadius 圆角半径（像素）
     * @return 裁剪后的图像（圆角外的像素为透明）
     */
    private fun applyRoundedCornerClip(source: Bitmap, cornerRadius: Float): Bitmap {
        val width = source.width
        val height = source.height

        // 创建输出 Bitmap（带 Alpha 通道）
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        // 创建圆角路径
        val path = Path()
        val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        path.addRoundRect(rect, cornerRadius, cornerRadius, Path.Direction.CW)

        // 先绘制圆角蒙版（白色）
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = android.graphics.Color.WHITE
        canvas.drawPath(path, paint)

        // 使用 SRC_IN 模式绘制原图（只保留圆角内的部分）
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(source, 0f, 0f, paint)

        Log.d(TAG, "Applied rounded corner clip: radius=$cornerRadius")
        return output
    }

    /**
     * 清理缓存的贴图
     */
    fun clearCache() {
        cachedEdgeMap?.recycle()
        cachedEdgeMap = null
        cachedNormalMap?.recycle()
        cachedNormalMap = null
        cachedSize = Pair(0, 0)
    }

    /**
     * 获取推荐的降采样比例（基于图像大小）
     */
    fun getRecommendedDownscale(width: Int, height: Int): Float {
        val pixels = width * height
        return when {
            pixels > 2_000_000 -> 0.25f  // > 2MP: 0.25x
            pixels > 1_000_000 -> 0.5f   // > 1MP: 0.5x
            else -> 0.75f                // <= 1MP: 0.75x
        }
    }
}

