/**
 * 色差效果处理器 (Chromatic Aberration) - C++ 加速版
 *
 * 模拟光学色差效果,通过分离 RGB 通道并应用不同程度的位移
 * 对应 React 版本中的 aberrationIntensity 参数
 *
 * 实现方式:
 * 1. C++ 版本（默认）：高性能原生实现，速度提升 3-5 倍
 * 2. Kotlin 版本（备选）：纯 Kotlin 实现，兼容性好
 *
 * 实现原理:
 * 1. 分离 RGB 三个颜色通道
 * 2. 对每个通道应用不同程度的位移
 * 3. 使用双线性插值平滑采样
 * 4. 复用像素数组和 Bitmap 对象，减少 GC 压力
 */
package com.example.liquidglass

import android.graphics.*
import android.util.Log
import kotlin.math.roundToInt

/**
 * 色差效果处理器（C++ 加速版）
 */
class ChromaticAberrationEffect {

    /**
     * 性能模式
     */
    enum class PerformanceMode {
        KOTLIN,    // 使用 Kotlin 实现
        CPP,       // 使用 C++ 实现
        AUTO       // 自动选择（默认）
    }

    // 性能模式设置（默认自动选择）
    var performanceMode: PerformanceMode = PerformanceMode.AUTO

    // ✅ 双线性插值开关（默认启用）
    // 启用：高质量，平滑采样，无马赛克（性能开销 2-3 倍）
    // 禁用：最近邻采样，性能优先，可能有轻微马赛克
    var useBilinearInterpolation: Boolean = true

    // ✅ 复用像素数组，避免每帧创建新数组（仅 Kotlin 版本使用）
    private var cachedSourcePixels: IntArray? = null
    private var cachedMapPixels: IntArray? = null
    private var cachedResultPixels: IntArray? = null
    private var cachedSize = 0

    // ✅ 使用 Bitmap 对象池
    private val bitmapPool = BitmapPool.getInstance()
    
    /**
     * 应用色差效果（智能选择实现）
     *
     * 根据性能模式自动选择最优实现：
     * - AUTO: 根据图像大小自动选择（大图用 C++，小图用 Kotlin）
     * - CPP: 强制使用 C++ 实现（推荐，性能提升 3-5 倍）
     * - KOTLIN: 强制使用 Kotlin 实现（兼容性好）
     *
     * @param source 原始图像
     * @param displacementMap 位移贴图
     * @param intensity 色差强度 (0-10, 默认 2)
     * @param scale 位移缩放系数
     * @param downscale 降采样比例 (0.5 = 处理速度提升4倍)
     * @param redOffset 红色通道偏移量（默认 0）
     * @param greenOffset 绿色通道偏移量（默认 -0.05）
     * @param blueOffset 蓝色通道偏移量（默认 -0.1）
     * @return 应用色差后的图像
     */
    fun apply(
        source: Bitmap,
        displacementMap: Bitmap,
        intensity: Float = 2f,
        scale: Float = 70f,
        downscale: Float = 0.5f,  // ✅ 默认 0.5x 降采样
        redOffset: Float = 0f,
        greenOffset: Float = -0.05f,
        blueOffset: Float = -0.1f
    ): Bitmap {
        // 根据性能模式选择实现
        return when (selectImplementation(source, downscale)) {
            Implementation.CPP -> applyCpp(
                source, displacementMap, intensity, scale,
                downscale, redOffset, greenOffset, blueOffset
            )
            Implementation.KOTLIN -> applyKotlin(
                source, displacementMap, intensity, scale,
                downscale, redOffset, greenOffset, blueOffset
            )
        }
    }

    /**
     * 选择最优实现
     */
    private fun selectImplementation(source: Bitmap, downscale: Float): Implementation {
        return when (performanceMode) {
            PerformanceMode.KOTLIN -> Implementation.KOTLIN
            PerformanceMode.CPP -> Implementation.CPP
            PerformanceMode.AUTO -> {
                // 自动选择策略：
                // 1. 大图（> 256×256）优先使用 C++
                // 2. 降采样比例 >= 0.5 时使用 C++
                // 3. 其他情况使用 Kotlin（避免 JNI 开销）
                val pixels = source.width * source.height
                if (pixels > 256 * 256 || downscale >= 0.5f) {
                    Implementation.CPP
                } else {
                    Implementation.KOTLIN
                }
            }
        }
    }

    /**
     * C++ 实现（高性能版本）
     */
    private fun applyCpp(
        source: Bitmap,
        displacementMap: Bitmap,
        intensity: Float,
        scale: Float,
        downscale: Float,
        redOffset: Float,
        greenOffset: Float,
        blueOffset: Float
    ): Bitmap {
        try {
            val originalWidth = source.width
            val originalHeight = source.height

            // ✅ 降采样：在更小的分辨率上处理
            val processWidth = (originalWidth * downscale).toInt().coerceAtLeast(1)
            val processHeight = (originalHeight * downscale).toInt().coerceAtLeast(1)

            // ✅ 从对象池获取 Bitmap
            val smallSource = if (downscale < 1.0f) {
                val temp = bitmapPool.get(processWidth, processHeight, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(temp)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)  // ✅ 添加过滤
                val srcRect = Rect(0, 0, source.width, source.height)
                val dstRect = Rect(0, 0, processWidth, processHeight)
                canvas.drawBitmap(source, srcRect, dstRect, paint)
                temp
            } else {
                source.copy(Bitmap.Config.ARGB_8888, true) ?: source
            }

            // 缩放位移贴图到处理尺寸
            val scaledMap = if (displacementMap.width != processWidth || displacementMap.height != processHeight) {
                val temp = bitmapPool.get(processWidth, processHeight, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(temp)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)  // ✅ 添加过滤
                val srcRect = Rect(0, 0, displacementMap.width, displacementMap.height)
                val dstRect = Rect(0, 0, processWidth, processHeight)
                canvas.drawBitmap(displacementMap, srcRect, dstRect, paint)
                temp
            } else {
                displacementMap.copy(Bitmap.Config.ARGB_8888, true) ?: displacementMap
            }

            // 创建结果 Bitmap
            val result = bitmapPool.get(processWidth, processHeight, Bitmap.Config.ARGB_8888)

            // ✅ 调整参数以适应降采样（与 Kotlin 实现一致）
            // 注意：C++ 直接使用传入的 offset，所以这里需要乘以 intensity 和 downscale
            val adjustedScale = scale * downscale
            val adjustedRedOffset = redOffset * intensity * downscale
            val adjustedGreenOffset = greenOffset * intensity * downscale
            val adjustedBlueOffset = blueOffset * intensity * downscale

            // ✅ 调用 C++ 原生实现
            NativeChromaticAberration.chromaticAberrationInplace(
                source = smallSource,
                displacement = scaledMap,
                result = result,
                intensity = intensity,
                scale = adjustedScale,
                redOffset = adjustedRedOffset,
                greenOffset = adjustedGreenOffset,
                blueOffset = adjustedBlueOffset,
                useBilinear = useBilinearInterpolation
            )

            // ✅ 归还临时 Bitmap 到对象池
            if (scaledMap != displacementMap) {
                bitmapPool.put(scaledMap)
            }
            if (smallSource != source) {
                bitmapPool.put(smallSource)
            }

            // ✅ 放大回原始尺寸（使用双线性插值平滑放大）
            val finalResult = if (downscale < 1.0f) {
                val upscaled = bitmapPool.get(originalWidth, originalHeight, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(upscaled)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                val srcRect = Rect(0, 0, processWidth, processHeight)
                val dstRect = Rect(0, 0, originalWidth, originalHeight)
                canvas.drawBitmap(result, srcRect, dstRect, paint)
                bitmapPool.put(result)
                upscaled
            } else {
                result
            }

            return finalResult
        } catch (e: Exception) {
            Log.e(TAG, "C++ implementation failed, falling back to Kotlin", e)
            // 如果 C++ 实现失败，回退到 Kotlin 实现
            return applyKotlin(source, displacementMap, intensity, scale, downscale, redOffset, greenOffset, blueOffset)
        }
    }

    /**
     * Kotlin 实现（兼容性版本）
     */
    private fun applyKotlin(
        source: Bitmap,
        displacementMap: Bitmap,
        intensity: Float,
        scale: Float,
        downscale: Float,
        redOffset: Float,
        greenOffset: Float,
        blueOffset: Float
    ): Bitmap {
        val originalWidth = source.width
        val originalHeight = source.height

        // ✅ 降采样：在更小的分辨率上处理
        val processWidth = (originalWidth * downscale).toInt().coerceAtLeast(1)
        val processHeight = (originalHeight * downscale).toInt().coerceAtLeast(1)

        // ✅ 从对象池获取 Bitmap
        val smallSource = if (downscale < 1.0f) {
            val temp = bitmapPool.get(processWidth, processHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(temp)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)  // ✅ 添加过滤
            val srcRect = android.graphics.Rect(0, 0, source.width, source.height)
            val dstRect = android.graphics.Rect(0, 0, processWidth, processHeight)
            canvas.drawBitmap(source, srcRect, dstRect, paint)
            temp
        } else {
            source
        }

        val result = bitmapPool.get(processWidth, processHeight, Bitmap.Config.ARGB_8888)

        // 缩放位移贴图到处理尺寸
        val scaledMap = if (displacementMap.width != processWidth || displacementMap.height != processHeight) {
            val temp = bitmapPool.get(processWidth, processHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(temp)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)  // ✅ 添加过滤
            val srcRect = android.graphics.Rect(0, 0, displacementMap.width, displacementMap.height)
            val dstRect = android.graphics.Rect(0, 0, processWidth, processHeight)
            canvas.drawBitmap(displacementMap, srcRect, dstRect, paint)
            temp
        } else {
            displacementMap
        }

        // ✅ 复用像素数组，避免每帧创建新数组
        val pixelCount = processWidth * processHeight
        if (cachedSize != pixelCount) {
            cachedSourcePixels = IntArray(pixelCount)
            cachedMapPixels = IntArray(pixelCount)
            cachedResultPixels = IntArray(pixelCount)
            cachedSize = pixelCount
        }

        val sourcePixels = cachedSourcePixels!!
        val mapPixels = cachedMapPixels!!
        val resultPixels = cachedResultPixels!!

        smallSource.getPixels(sourcePixels, 0, processWidth, 0, 0, processWidth, processHeight)
        scaledMap.getPixels(mapPixels, 0, processWidth, 0, 0, processWidth, processHeight)

        // 位移缩放因子（需要根据降采样调整）
        val scaleFactor = (scale * downscale) / 255f

        // 三个通道的位移偏移量（使用传入的参数，并根据强度和降采样调整）
        val actualRedOffset = redOffset * intensity * downscale
        val actualGreenOffset = greenOffset * intensity * downscale
        val actualBlueOffset = blueOffset * intensity * downscale

        // ✅ 直接在一次遍历中处理所有通道（最近邻采样）
        for (y in 0 until processHeight) {
            for (x in 0 until processWidth) {
                val index = y * processWidth + x
                val mapColor = mapPixels[index]

                // 计算基础位移
                val baseDx = (Color.red(mapColor) - 128) * scaleFactor
                val baseDy = (Color.green(mapColor) - 128) * scaleFactor

                // ✅ 通道偏移沿位移方向应用
                // （旧实现把标量同时加到 x/y 上，等于固定沿 45° 对角线偏移）
                val baseLen = kotlin.math.sqrt(baseDx * baseDx + baseDy * baseDy)
                val dirX: Float
                val dirY: Float
                if (baseLen > 0.001f) {
                    dirX = baseDx / baseLen
                    dirY = baseDy / baseLen
                } else {
                    dirX = 0f
                    dirY = 0f
                }

                // 采样三个通道（每个通道沿位移方向有不同的偏移量）
                val rSrcX = x + baseDx + dirX * actualRedOffset
                val rSrcY = y + baseDy + dirY * actualRedOffset
                val gSrcX = x + baseDx + dirX * actualGreenOffset
                val gSrcY = y + baseDy + dirY * actualGreenOffset
                val bSrcX = x + baseDx + dirX * actualBlueOffset
                val bSrcY = y + baseDy + dirY * actualBlueOffset

                // ✅ 根据设置选择采样方法
                val r: Int
                val g: Int
                val b: Int

                if (useBilinearInterpolation) {
                    // 双线性插值：高质量，平滑采样，无马赛克（性能开销 2-3 倍）
                    val rColor = sampleBilinear(sourcePixels, processWidth, processHeight, rSrcX, rSrcY)
                    val gColor = sampleBilinear(sourcePixels, processWidth, processHeight, gSrcX, gSrcY)
                    val bColor = sampleBilinear(sourcePixels, processWidth, processHeight, bSrcX, bSrcY)
                    r = Color.red(rColor)
                    g = Color.green(gColor)
                    b = Color.blue(bColor)
                } else {
                    // 最近邻采样：性能优先，可能有轻微马赛克
                    val rColor = samplePixel(sourcePixels, processWidth, processHeight, rSrcX, rSrcY)
                    val gColor = samplePixel(sourcePixels, processWidth, processHeight, gSrcX, gSrcY)
                    val bColor = samplePixel(sourcePixels, processWidth, processHeight, bSrcX, bSrcY)
                    r = Color.red(rColor)
                    g = Color.green(gColor)
                    b = Color.blue(bColor)
                }

                val a = Color.alpha(sourcePixels[index])

                resultPixels[index] = Color.argb(a, r, g, b)
            }
        }

        result.setPixels(resultPixels, 0, processWidth, 0, 0, processWidth, processHeight)

        // ✅ 归还临时 Bitmap 到对象池
        if (scaledMap != displacementMap) {
            bitmapPool.put(scaledMap)
        }
        if (smallSource != source) {
            bitmapPool.put(smallSource)
        }

        // ✅ 放大回原始尺寸（使用双线性插值平滑放大）
        val finalResult = if (downscale < 1.0f) {
            val upscaled = bitmapPool.get(originalWidth, originalHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(upscaled)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val srcRect = android.graphics.Rect(0, 0, processWidth, processHeight)
            val dstRect = android.graphics.Rect(0, 0, originalWidth, originalHeight)
            canvas.drawBitmap(result, srcRect, dstRect, paint)
            bitmapPool.put(result)
            upscaled
        } else {
            result
        }

        return finalResult
    }

    /**
     * 双线性插值采样（平滑采样，消除马赛克）
     *
     * 原理：
     * 1. 找到采样点周围的 4 个像素
     * 2. 根据采样点的小数部分计算权重
     * 3. 对 4 个像素进行加权平均
     *
     * @param pixels 像素数组
     * @param width 图像宽度
     * @param height 图像高度
     * @param x 采样 X 坐标（可以是小数）
     * @param y 采样 Y 坐标（可以是小数）
     * @return 插值后的颜色值
     */
    private fun sampleBilinear(pixels: IntArray, width: Int, height: Int, x: Float, y: Float): Int {
        // 边界检查
        if (x < 0 || x >= width - 1 || y < 0 || y >= height - 1) {
            val clampedX = x.roundToInt().coerceIn(0, width - 1)
            val clampedY = y.roundToInt().coerceIn(0, height - 1)
            return pixels[clampedY * width + clampedX]
        }

        // 获取整数部分和小数部分
        val x0 = x.toInt()
        val y0 = y.toInt()
        val x1 = (x0 + 1).coerceAtMost(width - 1)
        val y1 = (y0 + 1).coerceAtMost(height - 1)

        val fx = x - x0  // X 方向的小数部分
        val fy = y - y0  // Y 方向的小数部分

        // 获取 4 个角的像素
        val c00 = pixels[y0 * width + x0]  // 左上
        val c10 = pixels[y0 * width + x1]  // 右上
        val c01 = pixels[y1 * width + x0]  // 左下
        val c11 = pixels[y1 * width + x1]  // 右下

        // 对每个颜色通道进行双线性插值
        val a = interpolateChannel(Color.alpha(c00), Color.alpha(c10), Color.alpha(c01), Color.alpha(c11), fx, fy)
        val r = interpolateChannel(Color.red(c00), Color.red(c10), Color.red(c01), Color.red(c11), fx, fy)
        val g = interpolateChannel(Color.green(c00), Color.green(c10), Color.green(c01), Color.green(c11), fx, fy)
        val b = interpolateChannel(Color.blue(c00), Color.blue(c10), Color.blue(c01), Color.blue(c11), fx, fy)

        return Color.argb(a, r, g, b)
    }

    /**
     * 对单个颜色通道进行双线性插值
     *
     * @param c00 左上角的通道值
     * @param c10 右上角的通道值
     * @param c01 左下角的通道值
     * @param c11 右下角的通道值
     * @param fx X 方向的插值因子（0-1）
     * @param fy Y 方向的插值因子（0-1）
     * @return 插值后的通道值
     */
    private fun interpolateChannel(c00: Int, c10: Int, c01: Int, c11: Int, fx: Float, fy: Float): Int {
        // 先在 X 方向插值
        val c0 = c00 * (1 - fx) + c10 * fx  // 上边插值
        val c1 = c01 * (1 - fx) + c11 * fx  // 下边插值

        // 再在 Y 方向插值
        val result = c0 * (1 - fy) + c1 * fy

        return result.roundToInt().coerceIn(0, 255)
    }

    /**
     * 从像素数组采样（最近邻采样）
     *
     * 性能优先的采样方法，速度比双线性插值快 2-3 倍，
     * 但可能在边缘产生轻微的马赛克效果。
     *
     * @param pixels 像素数组
     * @param width 图像宽度
     * @param height 图像高度
     * @param x 采样 X 坐标（可以是小数）
     * @param y 采样 Y 坐标（可以是小数）
     * @return 最近邻像素的颜色值
     */
    private fun samplePixel(pixels: IntArray, width: Int, height: Int, x: Float, y: Float): Int {
        val clampedX = x.roundToInt().coerceIn(0, width - 1)
        val clampedY = y.roundToInt().coerceIn(0, height - 1)
        return pixels[clampedY * width + clampedX]
    }

    /**
     * 对单个颜色通道应用位移（旧版 - 已弃用）
     *
     * @deprecated 使用新的优化版本，直接在 apply() 中处理所有通道
     */
    @Deprecated("Use optimized version in apply() method")
    private fun displaceChannel(
        source: Bitmap,
        displacementMap: Bitmap,
        scale: Float,
        offset: Float
    ): Bitmap {
        val width = source.width
        val height = source.height
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        val actualScale = (scale + scale * offset) / 255f

        for (y in 0 until height) {
            for (x in 0 until width) {
                // 读取位移贴图的值
                val mapColor = displacementMap.getPixel(x, y)
                val dx = (Color.red(mapColor) - 128) * actualScale
                val dy = (Color.green(mapColor) - 128) * actualScale

                // 计算采样位置
                val srcX = (x + dx).roundToInt().coerceIn(0, width - 1)
                val srcY = (y + dy).roundToInt().coerceIn(0, height - 1)

                // 采样并写入
                result.setPixel(x, y, source.getPixel(srcX, srcY))
            }
        }

        return result
    }
    
    /**
     * 应用边缘遮罩的色差效果
     * 
     * 只在边缘区域应用色差,中心区域保持原样
     * 对应 React 版本中的 EDGE_ABERRATION 效果
     * 
     * @param source 原始图像
     * @param displacementMap 位移贴图
     * @param intensity 色差强度
     * @param scale 位移缩放系数
     * @return 应用边缘色差后的图像
     */
    fun applyWithEdgeMask(
        source: Bitmap,
        displacementMap: Bitmap,
        intensity: Float = 2f,
        scale: Float = 70f
    ): Bitmap {
        val width = source.width
        val height = source.height
        
        // 生成径向渐变遮罩
        val edgeMask = generateRadialEdgeMask(width, height, intensity)
        
        // 应用色差
        val aberrated = apply(source, displacementMap, intensity, scale)
        
        // 混合原图和色差图
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val maskValue = Color.alpha(edgeMask.getPixel(x, y)) / 255f
                
                val original = source.getPixel(x, y)
                val aberratedColor = aberrated.getPixel(x, y)
                
                // 线性插值
                val r = lerp(Color.red(original), Color.red(aberratedColor), maskValue)
                val g = lerp(Color.green(original), Color.green(aberratedColor), maskValue)
                val b = lerp(Color.blue(original), Color.blue(aberratedColor), maskValue)
                val a = Color.alpha(original)
                
                result.setPixel(x, y, Color.argb(a, r, g, b))
            }
        }
        
        edgeMask.recycle()
        aberrated.recycle()
        
        return result
    }
    
    /**
     * 生成径向边缘遮罩
     * 
     * 对应 React 版本中的 radialGradient edge-mask
     */
    private fun generateRadialEdgeMask(
        width: Int,
        height: Int,
        intensity: Float
    ): Bitmap {
        val mask = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(mask)
        
        val centerX = width / 2f
        val centerY = height / 2f
        val radius = kotlin.math.max(width, height) / 2f
        
        // 计算渐变起始点(对应 React 版本的 offset 计算)
        val startOffset = kotlin.math.max(30f, 80f - intensity * 2f) / 100f
        
        val gradient = RadialGradient(
            centerX, centerY, radius,
            intArrayOf(
                Color.argb(0, 0, 0, 0),      // 中心透明
                Color.argb(0, 0, 0, 0),      // startOffset 处透明
                Color.argb(255, 255, 255, 255)  // 边缘不透明
            ),
            floatArrayOf(0f, startOffset, 1f),
            Shader.TileMode.CLAMP
        )
        
        val paint = Paint()
        paint.shader = gradient
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        
        return mask
    }
    
    /**
     * 线性插值
     */
    private fun lerp(a: Int, b: Int, t: Float): Int {
        return (a + (b - a) * t).roundToInt().coerceIn(0, 255)
    }

    /**
     * 清理资源
     */
    fun cleanup() {
        // ✅ 清理缓存的像素数组
        cachedSourcePixels = null
        cachedMapPixels = null
        cachedResultPixels = null
        cachedSize = 0
    }

    /**
     * 实现类型（内部使用）
     */
    private enum class Implementation {
        KOTLIN, CPP
    }

    companion object {
        private const val TAG = "ChromaticAberration"
    }
}

