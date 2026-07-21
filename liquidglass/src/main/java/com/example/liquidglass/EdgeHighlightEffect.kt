/**
 * 边缘高光效果处理器 (Edge Highlight Effect)
 *
 *
 * 使用示例：
 * ```kotlin
 * val effect = EdgeHighlightEffect()
 * effect.draw(canvas, bounds, touchOffset, overLight)
 * ```
 */
package com.example.liquidglass

import android.graphics.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 边缘高光效果处理器
 */
class EdgeHighlightEffect {

    // 缓存的 Paint 对象
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // 缓存的路径对象
    private val borderPath = Path()
    private val innerPath = Path()

    /**
     * 绘制边缘高光效果
     *
     * @param canvas 画布
     * @param bounds 边界矩形
     * @param cornerRadius 圆角半径
     * @param mouseOffset 触摸偏移量（归一化，-100 到 100）
     * @param overLight 是否在亮背景上
     * @param borderWidth 边框宽度（像素）
     * @param opacity 高光不透明度（0-100）
     */
    fun draw(
        canvas: Canvas,
        bounds: RectF,
        cornerRadius: Float,
        mouseOffset: PointF = PointF(0f, 0f),
        overLight: Boolean = false,
        borderWidth: Float = 1.5f,
        opacity: Float = 100f
    ) {
        val normalizedOpacity = opacity / 100f

        // 1. 绘制 Over Light 效果（如果需要）
        if (overLight) {
            drawOverLightEffect(canvas, bounds, cornerRadius, normalizedOpacity)
        }

        // 2. 绘制边框层 1（Screen 混合模式）
        drawBorderLayer(canvas, bounds, cornerRadius, mouseOffset, PorterDuff.Mode.SCREEN, 0.2f * normalizedOpacity, borderWidth)

        // 3. 绘制边框层 2（Overlay 混合模式）
        drawBorderLayer(canvas, bounds, cornerRadius, mouseOffset, PorterDuff.Mode.OVERLAY, 1.0f * normalizedOpacity, borderWidth)
    }
    
    /**
     * 绘制 Over Light 效果
     */
    private fun drawOverLightEffect(canvas: Canvas, bounds: RectF, cornerRadius: Float, opacity: Float) {
        // 第一层：黑色半透明层（opacity: 0.2 * opacity）
        overlayPaint.reset()
        overlayPaint.isAntiAlias = true
        overlayPaint.color = Color.argb((0.2f * opacity * 255).toInt(), 0, 0, 0)
        canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, overlayPaint)

        // 第二层：黑色 Overlay 混合模式层（opacity: 1.0 * opacity）
        overlayPaint.reset()
        overlayPaint.isAntiAlias = true
        overlayPaint.color = Color.argb((opacity * 255).toInt(), 0, 0, 0)
        overlayPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.OVERLAY)
        canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, overlayPaint)
        overlayPaint.xfermode = null
    }
    
    /**
     * 绘制边框层
     *
     * @param blendMode 混合模式（Screen 或 Overlay）
     * @param baseOpacity 基础透明度
     * @param borderWidth 边框宽度
     */
    private fun drawBorderLayer(
        canvas: Canvas,
        bounds: RectF,
        cornerRadius: Float,
        mouseOffset: PointF,
        blendMode: PorterDuff.Mode,
        baseOpacity: Float,
        borderWidth: Float
    ) {
        // 计算渐变角度（对应 React: 135 + mouseOffset.x * 1.2）
        val gradientAngle = 135f + mouseOffset.x * 1.2f
        val angleRad = Math.toRadians(gradientAngle.toDouble()).toFloat()
        
        // 计算渐变起点和终点
        val centerX = bounds.centerX()
        val centerY = bounds.centerY()
        val radius = max(bounds.width(), bounds.height()) / 2f
        
        val startX = centerX - radius * kotlin.math.cos(angleRad)
        val startY = centerY - radius * kotlin.math.sin(angleRad)
        val endX = centerX + radius * kotlin.math.cos(angleRad)
        val endY = centerY + radius * kotlin.math.sin(angleRad)
        
        // 计算渐变参数（对应 React 的动态透明度和位置）
        val opacity1 = if (blendMode == PorterDuff.Mode.SCREEN) {
            0.12f + abs(mouseOffset.x) * 0.008f
        } else {
            0.32f + abs(mouseOffset.x) * 0.008f
        }
        
        val opacity2 = if (blendMode == PorterDuff.Mode.SCREEN) {
            0.4f + abs(mouseOffset.x) * 0.012f
        } else {
            0.6f + abs(mouseOffset.x) * 0.012f
        }
        
        val position1 = max(0.1f, 0.33f + mouseOffset.y * 0.003f)
        val position2 = min(0.9f, 0.66f + mouseOffset.y * 0.004f)
        
        // 创建线性渐变
        val gradient = LinearGradient(
            startX, startY, endX, endY,
            intArrayOf(
                Color.argb(0, 255, 255, 255),
                Color.argb((opacity1 * 255).toInt(), 255, 255, 255),
                Color.argb((opacity2 * 255).toInt(), 255, 255, 255),
                Color.argb(0, 255, 255, 255)
            ),
            floatArrayOf(0f, position1, position2, 1f),
            Shader.TileMode.CLAMP
        )
        
        // 创建边框路径（使用 mask 效果）
        borderPath.reset()
        borderPath.addRoundRect(bounds, cornerRadius, cornerRadius, Path.Direction.CW)
        
        innerPath.reset()
        val innerBounds = RectF(
            bounds.left + borderWidth,
            bounds.top + borderWidth,
            bounds.right - borderWidth,
            bounds.bottom - borderWidth
        )
        innerPath.addRoundRect(innerBounds, cornerRadius - borderWidth, cornerRadius - borderWidth, Path.Direction.CW)
        
        // 使用 Path.op 创建边框遮罩
        borderPath.op(innerPath, Path.Op.DIFFERENCE)
        
        // 绘制边框
        borderPaint.reset()
        borderPaint.isAntiAlias = true
        borderPaint.shader = gradient
        borderPaint.alpha = (baseOpacity * 255).toInt()
        borderPaint.xfermode = PorterDuffXfermode(blendMode)
        
        canvas.drawPath(borderPath, borderPaint)
        
        // 重置 xfermode
        borderPaint.xfermode = null
        borderPaint.shader = null
    }
    

    
    /**
     * 清理资源
     */
    fun cleanup() {
        borderPath.reset()
        innerPath.reset()
    }
}

