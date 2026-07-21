/**
 * 色散贴图生成器 (Dispersion Map Generator)
 *
 * 为色散效果生成必要的辅助贴图：
 * 1. 边缘距离贴图（Edge Distance Map）
 * 2. 法线贴图（Normal Map）
 *
 * 功能：
 * - 生成径向边缘距离场
 * - 生成径向法线贴图
 * - 支持不同的形状（圆形、矩形等）
 * - 支持自定义中心点
 */
package com.example.liquidglass

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import kotlin.math.sqrt
import kotlin.math.min
import kotlin.math.max
import kotlin.math.abs

/**
 * 色散贴图生成器
 */
object DispersionMapGenerator {

    private const val TAG = "DispersionMapGenerator"

    /**
     * 形状类型
     */
    enum class Shape {
        CIRCLE,      // 圆形
        RECTANGLE,   // 矩形
        ELLIPSE      // 椭圆
    }

    /**
     * 从源图像生成边缘距离贴图（基于 Alpha 通道）
     *
     * 检测 Alpha 通道来识别实际的元素边缘，计算每个像素到最近边缘的距离
     * 适用于已裁剪成圆角形状的图像（圆角外的像素为透明）
     *
     * @param source 源图像（必须包含 Alpha 通道）
     * @return 边缘距离贴图（灰度图，0=边缘，255=中心）
     */
    fun generateEdgeDistanceMapFromAlpha(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        Log.d(TAG, "Generating edge distance map from alpha channel: ${width}x${height}")

        // 第一步：检测边缘像素（Alpha < 255 或相邻像素 Alpha 变化大）
        val edgePixels = mutableListOf<Pair<Int, Int>>()
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = source.getPixel(x, y)
                val alpha = Color.alpha(pixel)

                // 检测边缘：Alpha < 255 或者相邻像素 Alpha 不同
                val isEdge = alpha < 255 || isAlphaEdge(source, x, y)
                if (isEdge) {
                    edgePixels.add(Pair(x, y))
                }
            }
        }

        Log.d(TAG, "Detected ${edgePixels.size} edge pixels")

        // 如果没有检测到边缘，返回全白图（所有像素都是中心）
        if (edgePixels.isEmpty()) {
            bitmap.eraseColor(Color.WHITE)
            Log.w(TAG, "No edge pixels detected, returning white map")
            return bitmap
        }

        // 第二步：使用快速算法计算距离场
        // 使用简化的距离场算法（基于矩形边界 + Alpha 检测）
        val maxDist = sqrt((width * width + height * height).toFloat()) / 2f

        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = source.getPixel(x, y)
                val alpha = Color.alpha(pixel)

                // 如果像素是透明的，距离为 0（边缘外）
                if (alpha < 10) {
                    bitmap.setPixel(x, y, Color.rgb(0, 0, 0))
                    continue
                }

                // 计算到矩形边界的距离
                val distToLeft = x.toFloat()
                val distToRight = (width - 1 - x).toFloat()
                val distToTop = y.toFloat()
                val distToBottom = (height - 1 - y).toFloat()

                val minDistance = min(min(distToLeft, distToRight), min(distToTop, distToBottom))

                // 归一化到 [0, 255]
                val normalizedDistance = (minDistance / maxDist * 255f).coerceIn(0f, 255f).toInt()

                bitmap.setPixel(x, y, Color.rgb(normalizedDistance, normalizedDistance, normalizedDistance))
            }
        }

        Log.d(TAG, "Edge distance map generated successfully")
        return bitmap
    }

    /**
     * 检测像素是否在 Alpha 边缘上
     */
    private fun isAlphaEdge(source: Bitmap, x: Int, y: Int): Boolean {
        val width = source.width
        val height = source.height
        val centerAlpha = Color.alpha(source.getPixel(x, y))

        // 检查 4 个相邻像素
        val neighbors = listOf(
            Pair(x - 1, y),
            Pair(x + 1, y),
            Pair(x, y - 1),
            Pair(x, y + 1)
        )

        for ((nx, ny) in neighbors) {
            if (nx in 0 until width && ny in 0 until height) {
                val neighborAlpha = Color.alpha(source.getPixel(nx, ny))
                // 如果相邻像素 Alpha 差异大于 10，认为是边缘
                if (abs(centerAlpha - neighborAlpha) > 10) {
                    return true
                }
            }
        }

        return false
    }

    /**
     * 生成边缘距离贴图
     *
     * 边缘距离贴图用于控制色散效果的强度：
     * - 边缘（0）：色散效果最强
     * - 中心（255）：色散效果最弱
     *
     * @param width 贴图宽度
     * @param height 贴图高度
     * @param shape 形状类型（默认圆形）
     * @param centerX 中心点 X 坐标（默认图像中心）
     * @param centerY 中心点 Y 坐标（默认图像中心）
     * @param maxDistance 最大距离（默认自动计算）
     * @return 边缘距离贴图（ARGB_8888）
     */
    fun generateEdgeDistanceMap(
        width: Int,
        height: Int,
        shape: Shape = Shape.CIRCLE,
        centerX: Float = width / 2f,
        centerY: Float = height / 2f,
        maxDistance: Float? = null
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        // 计算最大距离
        val maxDist = maxDistance ?: when (shape) {
            Shape.CIRCLE -> min(width, height) / 2f
            Shape.RECTANGLE -> sqrt((width / 2f) * (width / 2f) + (height / 2f) * (height / 2f))
            Shape.ELLIPSE -> max(width, height) / 2f
        }

        // 生成距离场
        for (y in 0 until height) {
            for (x in 0 until width) {
                val dx = x - centerX
                val dy = y - centerY

                // 计算到边缘的距离
                val distance = when (shape) {
                    Shape.CIRCLE -> {
                        // 圆形：径向距离
                        sqrt(dx * dx + dy * dy)
                    }
                    Shape.RECTANGLE -> {
                        // 矩形：到最近边的距离
                        val distToLeft = x.toFloat()
                        val distToRight = (width - 1 - x).toFloat()
                        val distToTop = y.toFloat()
                        val distToBottom = (height - 1 - y).toFloat()
                        min(min(distToLeft, distToRight), min(distToTop, distToBottom))
                    }
                    Shape.ELLIPSE -> {
                        // 椭圆：归一化径向距离
                        val normalizedDx = dx / (width / 2f)
                        val normalizedDy = dy / (height / 2f)
                        sqrt(normalizedDx * normalizedDx + normalizedDy * normalizedDy) * maxDist
                    }
                }

                // 归一化到 [0, 255]
                // 边缘 = 0（黑色），中心 = 255（白色）
                val normalizedDistance = (distance / maxDist * 255f).coerceIn(0f, 255f).toInt()

                // 写入像素（RGB 通道都使用相同的值）
                bitmap.setPixel(x, y, Color.rgb(normalizedDistance, normalizedDistance, normalizedDistance))
            }
        }

        return bitmap
    }

    /**
     * 生成径向法线贴图
     *
     * 法线贴图用于控制色散效果的方向：
     * - R 通道：法线 X 分量（0-255，128 为 0）
     * - G 通道：法线 Y 分量（0-255，128 为 0）
     * - B 通道：法线 Z 分量（固定为 128，表示向外）
     *
     * @param width 贴图宽度
     * @param height 贴图高度
     * @param centerX 中心点 X 坐标（默认图像中心）
     * @param centerY 中心点 Y 坐标（默认图像中心）
     * @param invert 是否反转法线方向（默认 false，从中心指向边缘）
     * @return 法线贴图（ARGB_8888）
     */
    fun generateRadialNormalMap(
        width: Int,
        height: Int,
        centerX: Float = width / 2f,
        centerY: Float = height / 2f,
        invert: Boolean = false
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val dx = x - centerX
                val dy = y - centerY
                val len = sqrt(dx * dx + dy * dy)

                // 计算归一化法线
                val nx = if (len > 0f) dx / len else 0f
                val ny = if (len > 0f) dy / len else 0f

                // 反转法线（如果需要）
                val finalNx = if (invert) -nx else nx
                val finalNy = if (invert) -ny else ny

                // 归一化到 [0, 255]
                // 法线分量范围 [-1, 1] -> [0, 255]
                val r = ((finalNx + 1f) * 0.5f * 255f).coerceIn(0f, 255f).toInt()
                val g = ((finalNy + 1f) * 0.5f * 255f).coerceIn(0f, 255f).toInt()
                val b = 128  // Z 分量固定为 0（映射到 128）

                // 写入像素
                bitmap.setPixel(x, y, Color.rgb(r, g, b))
            }
        }

        return bitmap
    }

    /**
     * 生成渐变边缘距离贴图（用于边缘高光效果）
     *
     * @param width 贴图宽度
     * @param height 贴图高度
     * @param edgeWidth 边缘宽度（像素）
     * @param shape 形状类型
     * @return 边缘距离贴图
     */
    fun generateGradientEdgeMap(
        width: Int,
        height: Int,
        edgeWidth: Float = 50f,
        shape: Shape = Shape.CIRCLE
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val centerX = width / 2f
        val centerY = height / 2f

        for (y in 0 until height) {
            for (x in 0 until width) {
                val dx = x - centerX
                val dy = y - centerY

                // 计算到边缘的距离
                val distance = when (shape) {
                    Shape.CIRCLE -> {
                        val radius = min(width, height) / 2f
                        val distFromCenter = sqrt(dx * dx + dy * dy)
                        max(0f, radius - distFromCenter)
                    }
                    Shape.RECTANGLE -> {
                        val distToLeft = x.toFloat()
                        val distToRight = (width - 1 - x).toFloat()
                        val distToTop = y.toFloat()
                        val distToBottom = (height - 1 - y).toFloat()
                        min(min(distToLeft, distToRight), min(distToTop, distToBottom))
                    }
                    Shape.ELLIPSE -> {
                        val radiusX = width / 2f
                        val radiusY = height / 2f
                        val normalizedDist = sqrt((dx / radiusX) * (dx / radiusX) + (dy / radiusY) * (dy / radiusY))
                        max(0f, 1f - normalizedDist) * min(radiusX, radiusY)
                    }
                }

                // 归一化到 [0, 255]（边缘宽度内）
                val normalizedDistance = (distance / edgeWidth * 255f).coerceIn(0f, 255f).toInt()

                bitmap.setPixel(x, y, Color.rgb(normalizedDistance, normalizedDistance, normalizedDistance))
            }
        }

        return bitmap
    }

    /**
     * 生成自定义形状的边缘距离贴图（基于 SDF）
     *
     * @param width 贴图宽度
     * @param height 贴图高度
     * @param sdf 有符号距离场函数 (x, y) -> distance
     * @param maxDistance 最大距离
     * @return 边缘距离贴图
     */
    fun generateCustomEdgeMap(
        width: Int,
        height: Int,
        maxDistance: Float = 200f,
        sdf: (x: Float, y: Float) -> Float
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        for (y in 0 until height) {
            for (x in 0 until width) {
                // 计算 SDF 值
                val distance = sdf(x.toFloat(), y.toFloat())

                // 归一化到 [0, 255]
                val normalizedDistance = ((distance / maxDistance) * 255f).coerceIn(0f, 255f).toInt()

                bitmap.setPixel(x, y, Color.rgb(normalizedDistance, normalizedDistance, normalizedDistance))
            }
        }

        return bitmap
    }

    /**
     * 预设：玻璃球效果
     */
    fun generateGlassSphereMap(width: Int, height: Int): Pair<Bitmap, Bitmap> {
        val edgeMap = generateEdgeDistanceMap(width, height, Shape.CIRCLE)
        val normalMap = generateRadialNormalMap(width, height)
        return Pair(edgeMap, normalMap)
    }

    /**
     * 预设：玻璃矩形效果
     */
    fun generateGlassRectangleMap(width: Int, height: Int): Pair<Bitmap, Bitmap> {
        val edgeMap = generateEdgeDistanceMap(width, height, Shape.RECTANGLE)
        val normalMap = generateRadialNormalMap(width, height)
        return Pair(edgeMap, normalMap)
    }

    /**
     * 预设：边缘高光效果
     */
    fun generateEdgeGlowMap(width: Int, height: Int, edgeWidth: Float = 50f): Bitmap {
        return generateGradientEdgeMap(width, height, edgeWidth, Shape.CIRCLE)
    }

    /**
     * 调试：可视化边缘距离贴图（添加颜色映射）
     */
    fun visualizeEdgeDistanceMap(edgeMap: Bitmap): Bitmap {
        val width = edgeMap.width
        val height = edgeMap.height
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = edgeMap.getPixel(x, y)
                val value = Color.red(pixel) / 255f

                // 颜色映射：蓝色（边缘）-> 绿色（中间）-> 红色（中心）
                val r = (value * 255f).toInt()
                val g = ((1f - kotlin.math.abs(value - 0.5f) * 2f) * 255f).toInt()
                val b = ((1f - value) * 255f).toInt()

                result.setPixel(x, y, Color.rgb(r, g, b))
            }
        }

        return result
    }

    /**
     * 调试：可视化法线贴图
     */
    fun visualizeNormalMap(normalMap: Bitmap): Bitmap {
        // 法线贴图本身就是可视化的（RGB 通道直接对应法线方向）
        return normalMap.copy(Bitmap.Config.ARGB_8888, false)
    }
}

