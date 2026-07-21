/**
 * 位移贴图生成器
 * 
 * 移植自 liquid-glass-react 的 shader-utils.ts
 * 用于生成不同模式的位移贴图,实现边缘扭曲效果
 * 
 * 支持的模式:
 * - STANDARD: 标准圆角矩形扭曲
 * - POLAR: 极坐标扭曲
 * - PROMINENT: 突出边缘扭曲
 */
package com.example.liquidglass

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.*

/**
 * 位移贴图模式
 */
enum class DisplacementMode {
    STANDARD,   // 标准模式
    POLAR,      // 极坐标模式
    PROMINENT   // 突出模式
}

/**
 * 二维向量
 */
data class Vec2(val x: Float, val y: Float)

/**
 * 位移贴图生成器
 */
class DisplacementMapGenerator(
    private val width: Int,
    private val height: Int
) {
    
    /**
     * 生成位移贴图
     * 
     * @param mode 位移模式
     * @return 位移贴图 Bitmap
     */
    fun generate(mode: DisplacementMode = DisplacementMode.STANDARD): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        
        // 第一遍:计算原始位移值
        val rawDisplacements = Array(height) { y ->
            FloatArray(width * 2) { x ->
                val pixelX = x / 2
                val uv = Vec2(pixelX.toFloat() / width, y.toFloat() / height)
                val displaced = when (mode) {
                    DisplacementMode.STANDARD -> fragmentShaderStandard(uv)
                    DisplacementMode.POLAR -> fragmentShaderPolar(uv)
                    DisplacementMode.PROMINENT -> fragmentShaderProminent(uv)
                }
                
                if (x % 2 == 0) {
                    displaced.x * width - pixelX  // dx
                } else {
                    displaced.y * height - y       // dy
                }
            }
        }
        
        // 计算最大位移量(用于归一化)
        var maxScale = 0f
        rawDisplacements.forEach { row ->
            row.forEach { value ->
                maxScale = max(maxScale, abs(value))
            }
        }
        maxScale = max(maxScale, 1f)
        
        // 第二遍:归一化并写入像素数组（批量 setPixels，比逐像素 setPixel 快一个数量级）
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val rowOffset = y * width
            for (x in 0 until width) {
                val dx = rawDisplacements[y][x * 2]
                val dy = rawDisplacements[y][x * 2 + 1]

                // 边缘平滑处理
                val edgeDistance = min(min(x, y), min(width - x - 1, height - y - 1))
                val edgeFactor = min(1f, edgeDistance / 2f)

                val smoothedDx = dx * edgeFactor
                val smoothedDy = dy * edgeFactor

                // 归一化到 [0, 1] 范围
                val r = (smoothedDx / maxScale + 0.5f).coerceIn(0f, 1f)
                val g = (smoothedDy / maxScale + 0.5f).coerceIn(0f, 1f)

                // 写入像素 (R=dx, G=dy, B=dy, A=255)
                pixels[rowOffset + x] = Color.argb(
                    255,
                    (r * 255).toInt(),
                    (g * 255).toInt(),
                    (g * 255).toInt()
                )
            }
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)

        return bitmap
    }
    
    /**
     * 标准模式片段着色器
     * 移植自 fragmentShaders.liquidGlass
     */
    private fun fragmentShaderStandard(uv: Vec2): Vec2 {
        val ix = uv.x - 0.5f
        val iy = uv.y - 0.5f
        
        // 计算到圆角矩形边缘的距离
        val distanceToEdge = roundedRectSDF(ix, iy, 0.3f, 0.2f, 0.6f)
        
        // 计算位移强度
        val displacement = smoothStep(0.8f, 0f, distanceToEdge - 0.15f)
        val scaled = smoothStep(0f, 1f, displacement)
        
        // 应用位移
        return Vec2(
            ix * scaled + 0.5f,
            iy * scaled + 0.5f
        )
    }
    
    /**
     * 极坐标模式片段着色器
     */
    private fun fragmentShaderPolar(uv: Vec2): Vec2 {
        val ix = uv.x - 0.5f
        val iy = uv.y - 0.5f
        
        // 转换到极坐标
        val radius = sqrt(ix * ix + iy * iy)
        val angle = atan2(iy, ix)
        
        // 计算径向位移
        val distanceToEdge = roundedRectSDF(ix, iy, 0.35f, 0.25f, 0.5f)
        val displacement = smoothStep(0.7f, 0f, distanceToEdge - 0.1f)
        
        // 应用径向扭曲
        val newRadius = radius * (1f - displacement * 0.3f)
        
        return Vec2(
            newRadius * cos(angle) + 0.5f,
            newRadius * sin(angle) + 0.5f
        )
    }
    
    /**
     * 突出模式片段着色器
     */
    private fun fragmentShaderProminent(uv: Vec2): Vec2 {
        val ix = uv.x - 0.5f
        val iy = uv.y - 0.5f
        
        // 计算到边缘的距离
        val distanceToEdge = roundedRectSDF(ix, iy, 0.25f, 0.15f, 0.7f)
        
        // 更强的边缘效果
        val displacement = smoothStep(0.9f, 0f, distanceToEdge - 0.2f)
        val scaled = displacement.pow(1.5f)
        
        // 向外推的效果
        val pushFactor = 1f + scaled * 0.2f
        
        return Vec2(
            ix * pushFactor + 0.5f,
            iy * pushFactor + 0.5f
        )
    }
    
    /**
     * 平滑阶跃函数
     * 移植自 smoothStep
     */
    private fun smoothStep(a: Float, b: Float, t: Float): Float {
        val clamped = ((t - a) / (b - a)).coerceIn(0f, 1f)
        return clamped * clamped * (3f - 2f * clamped)
    }
    
    /**
     * 圆角矩形 SDF (Signed Distance Function)
     * 移植自 roundedRectSDF
     * 
     * @param x X 坐标(中心为原点)
     * @param y Y 坐标(中心为原点)
     * @param width 半宽
     * @param height 半高
     * @param radius 圆角半径
     * @return 到边缘的有符号距离
     */
    private fun roundedRectSDF(
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        radius: Float
    ): Float {
        val qx = abs(x) - width + radius
        val qy = abs(y) - height + radius
        
        val len = sqrt(max(qx, 0f).pow(2) + max(qy, 0f).pow(2))
        return min(max(qx, qy), 0f) + len - radius
    }
    
    companion object {
        /**
         * 预生成标准尺寸的位移贴图
         */
        fun generateStandardMaps(width: Int = 270, height: Int = 69): Map<DisplacementMode, Bitmap> {
            val generator = DisplacementMapGenerator(width, height)
            return mapOf(
                DisplacementMode.STANDARD to generator.generate(DisplacementMode.STANDARD),
                DisplacementMode.POLAR to generator.generate(DisplacementMode.POLAR),
                DisplacementMode.PROMINENT to generator.generate(DisplacementMode.PROMINENT)
            )
        }
    }
}

