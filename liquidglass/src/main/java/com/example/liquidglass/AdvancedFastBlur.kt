/**
 * 简化版快速模糊工具类
 *
 * 原理：缩小-模糊-放大 (Downscale-Blur-Upscale)
 * 1. 先缩小图片，减少需要处理的像素点
 * 2. 使用简单的盒式模糊（Box Blur）
 * 3. 放大回原尺寸
 *
 * 优势：
 * - 代码简单，无依赖
 * - 处理速度快
 * - 内存占用少
 * - 使用 Bitmap 对象池减少 GC 压力
 *
 * 使用示例：
 * ```kotlin
 * val blur = AdvancedFastBlur()
 * val blurred = blur.blur(bitmap, radius = 20f, downscale = 0.4f)
 * ```
 */
package com.example.liquidglass

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.roundToInt

class AdvancedFastBlur {

    // ✅ 使用 Bitmap 对象池
    private val bitmapPool = BitmapPool.getInstance()
    
    // ✅ 缓存像素数组，避免重复分配
    private var cachedPixels: IntArray? = null
    private var cachedTempPixels: IntArray? = null
    private var cachedSize = 0
    
    /**
     * 简化版模糊处理
     * 
     * @param bitmap 原始图像
     * @param radius 模糊半径 (0-25)
     * @param downscale 缩放比例 (0.01-1.0)，越小速度越快
     * @return 模糊后的图像
     */
    fun blur(
        bitmap: Bitmap, 
        radius: Float, 
        downscale: Float = 0.5f
    ): Bitmap {
        val clampedRadius = radius.coerceIn(0f, 25f)
        val clampedScale = downscale.coerceIn(0.01f, 1.0f)
        
        // 如果半径为 0，直接返回副本
        if (clampedRadius < 0.5f) {
            return bitmap.copy(Bitmap.Config.ARGB_8888, false)
        }
        
        // 缩小-模糊-放大流程
        return blurWithDownscale(bitmap, clampedRadius, clampedScale)
    }
    
    /**
     * 缩小-模糊-放大流程（使用对象池优化）
     */
    private fun blurWithDownscale(
        bitmap: Bitmap,
        radius: Float,
        scale: Float
    ): Bitmap {
        val originalWidth = bitmap.width
        val originalHeight = bitmap.height

        // 1. 缩小图片（使用对象池 + 双线性插值）
        val smallWidth = (originalWidth * scale).roundToInt().coerceAtLeast(1)
        val smallHeight = (originalHeight * scale).roundToInt().coerceAtLeast(1)

        val small = bitmapPool.get(smallWidth, smallHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(small)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val srcRect = Rect(0, 0, originalWidth, originalHeight)
        val dstRect = Rect(0, 0, smallWidth, smallHeight)
        canvas.drawBitmap(bitmap, srcRect, dstRect, paint)

        // 2. 在小图上模糊（半径也按比例缩小）
        val scaledRadius = (radius * scale).coerceIn(1f, 25f)
        val blurred = blurWithBoxBlur(small, scaledRadius.roundToInt())

        // 归还小图到对象池
        if (small != blurred) {
            bitmapPool.put(small)
        }

        // 3. 放大回原尺寸（使用对象池 + 双线性插值平滑放大）
        val result = bitmapPool.get(originalWidth, originalHeight, Bitmap.Config.ARGB_8888)
        val canvas2 = Canvas(result)
        val paint2 = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val srcRect2 = Rect(0, 0, smallWidth, smallHeight)
        val dstRect2 = Rect(0, 0, originalWidth, originalHeight)
        canvas2.drawBitmap(blurred, srcRect2, dstRect2, paint2)

        // 归还模糊后的小图到对象池
        if (blurred != result) {
            bitmapPool.put(blurred)
        }

        return result
    }
    
    /**
     * 盒式模糊（Box Blur）- 简单高效
     * 
     * 原理：对每个像素，取周围 (2*radius+1)×(2*radius+1) 区域的平均值
     * 优化：使用可分离卷积，先水平后垂直，复杂度从 O(n²r²) 降到 O(n²r)
     */
    private fun blurWithBoxBlur(bitmap: Bitmap, radius: Int): Bitmap {
        if (radius < 1) return bitmap.copy(Bitmap.Config.ARGB_8888, false)
        
        val width = bitmap.width
        val height = bitmap.height
        val pixelCount = width * height
        
        // ✅ 复用像素数组
        if (cachedSize != pixelCount) {
            cachedPixels = IntArray(pixelCount)
            cachedTempPixels = IntArray(pixelCount)
            cachedSize = pixelCount
        }
        
        val pixels = cachedPixels!!
        val tempPixels = cachedTempPixels!!
        
        // 读取像素
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        
        // 水平方向模糊
        boxBlurHorizontal(pixels, tempPixels, width, height, radius)
        
        // 垂直方向模糊
        boxBlurVertical(tempPixels, pixels, width, height, radius)
        
        // 创建结果
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, width, 0, 0, width, height)
        
        return result
    }
    
    /**
     * 水平方向盒式模糊
     */
    private fun boxBlurHorizontal(input: IntArray, output: IntArray, width: Int, height: Int, radius: Int) {
        val windowSize = radius * 2 + 1
        
        for (y in 0 until height) {
            var sumA = 0
            var sumR = 0
            var sumG = 0
            var sumB = 0
            
            val rowStart = y * width
            
            // 初始化窗口
            for (x in -radius..radius) {
                val px = x.coerceIn(0, width - 1)
                val color = input[rowStart + px]
                sumA += (color shr 24) and 0xFF
                sumR += (color shr 16) and 0xFF
                sumG += (color shr 8) and 0xFF
                sumB += color and 0xFF
            }
            
            // 滑动窗口
            for (x in 0 until width) {
                output[rowStart + x] = (
                    ((sumA / windowSize) shl 24) or
                    ((sumR / windowSize) shl 16) or
                    ((sumG / windowSize) shl 8) or
                    (sumB / windowSize)
                )
                
                // 移动窗口
                val removeX = (x - radius).coerceIn(0, width - 1)
                val addX = (x + radius + 1).coerceIn(0, width - 1)
                
                val removeColor = input[rowStart + removeX]
                val addColor = input[rowStart + addX]
                
                sumA += ((addColor shr 24) and 0xFF) - ((removeColor shr 24) and 0xFF)
                sumR += ((addColor shr 16) and 0xFF) - ((removeColor shr 16) and 0xFF)
                sumG += ((addColor shr 8) and 0xFF) - ((removeColor shr 8) and 0xFF)
                sumB += (addColor and 0xFF) - (removeColor and 0xFF)
            }
        }
    }
    
    /**
     * 垂直方向盒式模糊
     */
    private fun boxBlurVertical(input: IntArray, output: IntArray, width: Int, height: Int, radius: Int) {
        val windowSize = radius * 2 + 1
        
        for (x in 0 until width) {
            var sumA = 0
            var sumR = 0
            var sumG = 0
            var sumB = 0
            
            // 初始化窗口
            for (y in -radius..radius) {
                val py = y.coerceIn(0, height - 1)
                val color = input[py * width + x]
                sumA += (color shr 24) and 0xFF
                sumR += (color shr 16) and 0xFF
                sumG += (color shr 8) and 0xFF
                sumB += color and 0xFF
            }
            
            // 滑动窗口
            for (y in 0 until height) {
                output[y * width + x] = (
                    ((sumA / windowSize) shl 24) or
                    ((sumR / windowSize) shl 16) or
                    ((sumG / windowSize) shl 8) or
                    (sumB / windowSize)
                )
                
                // 移动窗口
                val removeY = (y - radius).coerceIn(0, height - 1)
                val addY = (y + radius + 1).coerceIn(0, height - 1)
                
                val removeColor = input[removeY * width + x]
                val addColor = input[addY * width + x]
                
                sumA += ((addColor shr 24) and 0xFF) - ((removeColor shr 24) and 0xFF)
                sumR += ((addColor shr 16) and 0xFF) - ((removeColor shr 16) and 0xFF)
                sumG += ((addColor shr 8) and 0xFF) - ((removeColor shr 8) and 0xFF)
                sumB += (addColor and 0xFF) - (removeColor and 0xFF)
            }
        }
    }
    
    /**
     * 清理资源
     */
    fun cleanup() {
        cachedPixels = null
        cachedTempPixels = null
        cachedSize = 0
    }
}

