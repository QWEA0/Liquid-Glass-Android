/**
 * NativeChromaticAberration - 色差效果 JNI 接口层
 * 
 * 提供高性能 C++ 实现的色差效果（Chromatic Aberration）。
 * 
 * 功能：
 * - 模拟光学色差效果，通过分离 RGB 通道并应用不同程度的位移
 * - 使用双线性插值进行平滑采样，消除马赛克效果
 * - C++ 实现比 Kotlin 版本性能提升 2-5 倍
 * 
 * 要求：
 * - 所有 Bitmap 必须为 ARGB_8888 格式
 * - 所有 Bitmap 必须是可编辑的（mutable）
 * - source、displacement、result 必须具有相同的尺寸
 * 
 * 线程安全：
 * - 单个 Bitmap 不应在多线程中同时调用
 * - 不同 Bitmap 可以在不同线程中并发处理
 * 
 * 性能建议：
 * - 对于大图，建议先降采样处理，然后再放大回原尺寸
 * - 可以缓存位移贴图，避免每帧重新生成
 * - 结果 Bitmap 可以与源 Bitmap 相同，实现原位处理
 * 
 * 使用示例：
 * ```kotlin
 * val source = BitmapFactory.decodeResource(resources, R.drawable.image)
 *     .copy(Bitmap.Config.ARGB_8888, true)
 * val displacement = BitmapFactory.decodeResource(resources, R.drawable.displacement_map)
 *     .copy(Bitmap.Config.ARGB_8888, true)
 * val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
 * 
 * NativeChromaticAberration.apply(
 *     source = source,
 *     displacement = displacement,
 *     result = result,
 *     intensity = 2.0f,
 *     scale = 70.0f
 * )
 * ```
 */
package com.example.liquidglass

import android.graphics.Bitmap

object NativeChromaticAberration {

    init {
        System.loadLibrary("nativegauss")
    }

    /**
     * 应用色差效果（原位处理）
     * 
     * @param source 源图像（ARGB_8888, mutable）
     * @param displacement 位移贴图（ARGB_8888, mutable）
     *                     - R 通道：X 方向位移（0-255，128 为中心）
     *                     - G 通道：Y 方向位移（0-255，128 为中心）
     * @param result 结果图像（ARGB_8888, mutable，可以与 source 相同）
     * @param intensity 色差强度（0-10，推荐 2.0）
     *                  - 0：无色差
     *                  - 2：轻度色差（默认）
     *                  - 5：中度色差
     *                  - 10：强烈色差
     * @param scale 位移缩放系数（推荐 70.0）
     *              - 控制位移贴图的影响程度
     *              - 值越大，位移效果越明显
     * @param redOffset 红色通道偏移量（默认 0.0）
     *                  - 控制红色通道的额外位移
     *                  - 正值向外偏移，负值向内偏移
     * @param greenOffset 绿色通道偏移量（默认 -0.05）
     *                    - 控制绿色通道的额外位移
     * @param blueOffset 蓝色通道偏移量（默认 -0.1）
     *                   - 控制蓝色通道的额外位移
     * @param useBilinear 是否使用双线性插值（默认 true）
     *                    - true: 双线性插值（高质量，平滑采样，无马赛克）
     *                    - false: 最近邻采样（高性能，速度快 2-3 倍，可能有轻微马赛克）
     *
     * @throws IllegalArgumentException 如果 Bitmap 格式不是 ARGB_8888 或不可编辑
     * @throws IllegalArgumentException 如果 source、displacement、result 尺寸不一致
     */
    external fun chromaticAberrationInplace(
        source: Bitmap,
        displacement: Bitmap,
        result: Bitmap,
        intensity: Float = 2.0f,
        scale: Float = 70.0f,
        redOffset: Float = 0.0f,
        greenOffset: Float = -0.05f,
        blueOffset: Float = -0.1f,
        useBilinear: Boolean = true
    )

    /**
     * 应用色差效果（便捷方法，创建新的结果 Bitmap）
     *
     * @param source 源图像
     * @param displacement 位移贴图
     * @param intensity 色差强度
     * @param scale 位移缩放系数
     * @param redOffset 红色通道偏移量
     * @param greenOffset 绿色通道偏移量
     * @param blueOffset 蓝色通道偏移量
     * @param useBilinear 是否使用双线性插值（默认 true）
     * @return 应用色差后的新图像
     */
    fun apply(
        source: Bitmap,
        displacement: Bitmap,
        intensity: Float = 2.0f,
        scale: Float = 70.0f,
        redOffset: Float = 0.0f,
        greenOffset: Float = -0.05f,
        blueOffset: Float = -0.1f,
        useBilinear: Boolean = true
    ): Bitmap {
        // 创建结果 Bitmap
        val result = Bitmap.createBitmap(
            source.width,
            source.height,
            Bitmap.Config.ARGB_8888
        )

        // 调用原位处理函数
        chromaticAberrationInplace(
            source,
            displacement,
            result,
            intensity,
            scale,
            redOffset,
            greenOffset,
            blueOffset,
            useBilinear
        )

        return result
    }

    /**
     * 应用色差效果（降采样优化版本）
     * 
     * 先将图像降采样到较小尺寸，应用色差效果，然后再放大回原尺寸。
     * 可以显著提升性能，适合实时处理场景。
     * 
     * @param source 源图像
     * @param displacement 位移贴图
     * @param intensity 色差强度
     * @param scale 位移缩放系数
     * @param downscale 降采样比例（0.5 = 处理速度提升 4 倍）
     * @param redOffset 红色通道偏移量
     * @param greenOffset 绿色通道偏移量
     * @param blueOffset 蓝色通道偏移量
     * @return 应用色差后的图像
     */
    fun applyWithDownsample(
        source: Bitmap,
        displacement: Bitmap,
        intensity: Float = 2.0f,
        scale: Float = 70.0f,
        downscale: Float = 0.5f,
        redOffset: Float = 0.0f,
        greenOffset: Float = -0.05f,
        blueOffset: Float = -0.1f
    ): Bitmap {
        val originalWidth = source.width
        val originalHeight = source.height
        
        // 计算降采样后的尺寸
        val processWidth = (originalWidth * downscale).toInt().coerceAtLeast(1)
        val processHeight = (originalHeight * downscale).toInt().coerceAtLeast(1)
        
        // 降采样源图像
        val smallSource = if (downscale < 1.0f) {
            Bitmap.createScaledBitmap(source, processWidth, processHeight, true)
        } else {
            source
        }
        
        // 降采样位移贴图
        val smallDisplacement = if (downscale < 1.0f) {
            Bitmap.createScaledBitmap(displacement, processWidth, processHeight, true)
        } else {
            displacement
        }
        
        // 创建结果 Bitmap
        val smallResult = Bitmap.createBitmap(
            processWidth,
            processHeight,
            Bitmap.Config.ARGB_8888
        )

        // ✅ 调整参数以适应降采样
        // 注意：C++ 直接使用传入的 offset，所以这里需要乘以 intensity 和 downscale
        val adjustedScale = scale * downscale
        val adjustedRedOffset = redOffset * intensity * downscale
        val adjustedGreenOffset = greenOffset * intensity * downscale
        val adjustedBlueOffset = blueOffset * intensity * downscale

        // 应用色差效果
        chromaticAberrationInplace(
            smallSource,
            smallDisplacement,
            smallResult,
            intensity,
            adjustedScale,
            adjustedRedOffset,
            adjustedGreenOffset,
            adjustedBlueOffset
        )
        
        // 清理临时 Bitmap
        if (smallSource != source) {
            smallSource.recycle()
        }
        if (smallDisplacement != displacement) {
            smallDisplacement.recycle()
        }
        
        // 放大回原尺寸
        val finalResult = if (downscale < 1.0f) {
            val upscaled = Bitmap.createScaledBitmap(
                smallResult,
                originalWidth,
                originalHeight,
                true
            )
            smallResult.recycle()
            upscaled
        } else {
            smallResult
        }
        
        return finalResult
    }

    /**
     * 验证 Bitmap 是否满足要求
     * 
     * @param bitmap 待验证的 Bitmap
     * @return true 如果满足要求，false 否则
     */
    fun validateBitmap(bitmap: Bitmap): Boolean {
        return bitmap.config == Bitmap.Config.ARGB_8888 && bitmap.isMutable
    }

    /**
     * 验证多个 Bitmap 是否尺寸一致
     * 
     * @param bitmaps 待验证的 Bitmap 列表
     * @return true 如果所有 Bitmap 尺寸一致，false 否则
     */
    fun validateDimensions(vararg bitmaps: Bitmap): Boolean {
        if (bitmaps.isEmpty()) return true
        
        val width = bitmaps[0].width
        val height = bitmaps[0].height
        
        return bitmaps.all { it.width == width && it.height == height }
    }
}

