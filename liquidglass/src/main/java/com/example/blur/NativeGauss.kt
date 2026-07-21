/**
 * NativeGauss - JNI 接口层
 * 
 * 提供高性能 IIR 递归高斯模糊和 Box3 近似高斯模糊的原生实现。
 * 
 * 要求：
 * - Bitmap 必须为 ARGB_8888 格式
 * - Bitmap 必须是可编辑的（mutable）
 * - 所有操作均为原位处理（in-place），直接修改传入的 Bitmap
 * 
 * 线程安全：
 * - 单个 Bitmap 不应在多线程中同时调用
 * - 不同 Bitmap 可以在不同线程中并发处理
 * 
 * 性能建议：
 * - 对于 σ > 15 的强模糊，考虑先下采样 1/2 或 1/3，模糊后再上采样
 * - 对于静态背景，可以缓存模糊结果，避免每帧重算
 * - 小图（< 64×64）优先使用 box3Inplace，性能更优
 */
package com.example.blur

import android.graphics.Bitmap

object NativeGauss {

    // NEON 支持检测（延迟初始化）
    private val neonSupported: Boolean by lazy {
        try {
            hasNeonSupport()
        } catch (e: UnsatisfiedLinkError) {
            false
        }
    }

    init {
        System.loadLibrary("nativegauss")
    }

    /**
     * 检测当前设备是否支持 NEON 向量化
     *
     * @return true 如果支持 NEON，false 否则
     */
    external fun hasNeonSupport(): Boolean

    /**
     * IIR 递归高斯模糊（原位处理）
     *
     * 算法特性：
     * - 时间复杂度：O(W×H)，与 σ 无关
     * - 空间复杂度：O(max(W,H))，仅需一维工作缓冲
     * - 数值稳定：采用 Deriche 参数化，支持大 σ 值
     *
     * @param bitmap 待处理位图（ARGB_8888, mutable）
     * @param sigma 高斯标准差，推荐范围 [2.0, 30.0]
     *              - σ ≤ 0.1：直接返回，不做处理
     *              - σ = 6：轻度模糊，感知半径 ≈ 18px
     *              - σ = 12：中度模糊，感知半径 ≈ 36px
     *              - σ = 18：强烈模糊，感知半径 ≈ 54px
     * @param linear 是否在线性色彩空间处理
     *              - true：sRGB→Linear→处理→sRGB，质量最佳，无带状，耗时 +15%
     *              - false：直接在 sRGB 空间处理，性能最优，强模糊可能轻微带状
     *
     * @throws IllegalArgumentException 如果 Bitmap 格式不是 ARGB_8888 或不可编辑
     */
    external fun gaussianIIRInplace(
        bitmap: Bitmap,
        sigma: Float,
        linear: Boolean = false
    )

    /**
     * IIR 递归高斯模糊（NEON 优化版本，原位处理）
     *
     * 性能提升：
     * - 相比标量版本加速 2-4×
     * - 仅在支持 NEON 的设备上可用
     * - 建议使用 smartBlur() 自动选择最优实现
     *
     * @param bitmap 待处理位图（ARGB_8888, mutable）
     * @param sigma 高斯标准差
     * @param linear 是否在线性色彩空间处理
     *
     * @throws IllegalArgumentException 如果 Bitmap 格式不是 ARGB_8888 或不可编辑
     * @throws UnsatisfiedLinkError 如果设备不支持 NEON
     */
    external fun gaussianIIRNeonInplace(
        bitmap: Bitmap,
        sigma: Float,
        linear: Boolean = false
    )
    
    /**
     * 三次盒式模糊近似高斯（原位处理）
     * 
     * 算法特性：
     * - 时间复杂度：O(W×H)，与 radius 无关
     * - 近似质量：与真实高斯的 PSNR ≈ 35-40 dB
     * - 适用场景：低端设备、超小图、实时预览
     * 
     * @param bitmap 待处理位图（ARGB_8888, mutable）
     * @param radius 盒式半径，推荐范围 [1, 20]
     *               - radius = 3：轻度模糊，近似 σ ≈ 2.5
     *               - radius = 6：中度模糊，近似 σ ≈ 5.0
     *               - radius = 12：强烈模糊，近似 σ ≈ 10.0
     * 
     * @throws IllegalArgumentException 如果 Bitmap 格式不是 ARGB_8888 或不可编辑
     */
    external fun box3Inplace(
        bitmap: Bitmap,
        radius: Int
    )

    /**
     * AdvancedFastBlur 风格的 Box Blur（降采样优化 - 快速版本，原位处理）
     *
     * 算法流程：
     * 1. 降采样到指定比例（使用最近邻插值 - 快速）
     * 2. 在小图上执行 Box Blur
     * 3. 上采样回原尺寸（使用最近邻插值 - 快速）
     *
     * 性能优势：
     * - 降采样 50% 时，处理像素数减少 75%
     * - 最近邻插值比双线性插值快 5-10 倍
     * - 适合实时预览和大图模糊
     * - 由于后续会模糊，质量损失可接受
     *
     * @param bitmap 待处理位图（ARGB_8888, mutable）
     * @param radius 模糊半径（0-25），应用于降采样后的图像
     * @param downscale 降采样比例（0.01-1.0），推荐 0.5
     *                  - 0.5：降采样 50%，速度提升约 4×
     *                  - 0.25：降采样 75%，速度提升约 16×
     *
     * @throws IllegalArgumentException 如果 Bitmap 格式不是 ARGB_8888 或不可编辑
     */
    external fun advancedBoxBlurInplace(
        bitmap: Bitmap,
        radius: Float,
        downscale: Float = 0.5f
    )

    /**
     * AdvancedFastBlur 风格的 Box Blur（降采样优化 - 高质量版本，原位处理）
     *
     * 算法流程：
     * 1. 降采样到指定比例（使用双线性插值 - 高质量）
     * 2. 在小图上执行 Box Blur
     * 3. 上采样回原尺寸（使用双线性插值 - 高质量）
     *
     * 优势：
     * - 质量好，平滑无锯齿
     * - 适合需要高质量的场景
     *
     * 缺点：
     * - 速度较慢（大量浮点运算）
     *
     * @param bitmap 待处理位图（ARGB_8888, mutable）
     * @param radius 模糊半径（0-25），应用于降采样后的图像
     * @param downscale 降采样比例（0.01-1.0），推荐 0.5
     *
     * @throws IllegalArgumentException 如果 Bitmap 格式不是 ARGB_8888 或不可编辑
     */
    external fun advancedBoxBlurInplaceHQ(
        bitmap: Bitmap,
        radius: Float,
        downscale: Float = 0.5f
    )

    /**
     * 辅助函数：根据目标半径计算等效 σ
     * 
     * @param radius 期望的感知半径（像素）
     * @return 对应的 σ 值（radius ≈ 3σ）
     */
    fun radiusToSigma(radius: Int): Float {
        return radius / 3.0f
    }
    
    /**
     * 辅助函数：根据 σ 计算感知半径
     * 
     * @param sigma 高斯标准差
     * @return 对应的感知半径（像素）
     */
    fun sigmaToRadius(sigma: Float): Int {
        return (sigma * 3.0f).toInt()
    }
    
    /**
     * 智能模糊：根据图像尺寸和模糊强度自动选择最优算法
     *
     * 策略：
     * - 小图（< 64×64）且 σ < 8：使用 Box3
     * - 支持 NEON 的设备：使用 NEON 优化版本
     * - 其他情况：使用标量 IIR
     *
     * @param bitmap 待处理位图
     * @param sigma 高斯标准差
     * @param highQuality 是否启用高质量模式（线性色彩空间）
     */
    fun smartBlur(bitmap: Bitmap, sigma: Float, highQuality: Boolean = false) {
        val pixels = bitmap.width * bitmap.height

        // 小图且轻度模糊：使用 Box3
        if (pixels < 64 * 64 && sigma < 8.0f) {
            val radius = (sigma * 1.2f).toInt().coerceAtLeast(1)
            box3Inplace(bitmap, radius)
        } else {
            // 其他情况：优先使用 NEON 优化版本
            if (neonSupported) {
                gaussianIIRNeonInplace(bitmap, sigma, highQuality)
            } else {
                gaussianIIRInplace(bitmap, sigma, highQuality)
            }
        }
    }
    
    /**
     * 下采样模糊管线（推荐用于强模糊场景）
     *
     * 流程：
     * 1. 下采样到 1/scale
     * 2. 在小图上执行模糊（σ' = σ / scale）
     * 3. 双线性上采样回原尺寸
     *
     * 优势：
     * - σ = 18, scale = 2：性能提升 ≈ 4×，质量损失 < 5%
     * - σ = 24, scale = 3：性能提升 ≈ 9×，质量损失 < 10%
     *
     * @param bitmap 待处理位图
     * @param sigma 高斯标准差（原图尺度）
     * @param scale 下采样倍数（2 或 3）
     * @param highQuality 是否启用高质量模式
     * @return 模糊后的位图（新创建）
     */
    fun downsampleBlur(
        bitmap: Bitmap,
        sigma: Float,
        scale: Int = 2,
        highQuality: Boolean = false
    ): Bitmap {
        require(scale in 2..3) { "Scale must be 2 or 3" }

        val smallW = bitmap.width / scale
        val smallH = bitmap.height / scale

        // 下采样
        val small = Bitmap.createScaledBitmap(bitmap, smallW, smallH, true)

        // 在小图上模糊（调整 σ，优先使用 NEON）
        val adjustedSigma = sigma / scale
        if (neonSupported) {
            gaussianIIRNeonInplace(small, adjustedSigma, highQuality)
        } else {
            gaussianIIRInplace(small, adjustedSigma, highQuality)
        }

        // 上采样回原尺寸
        val result = Bitmap.createScaledBitmap(small, bitmap.width, bitmap.height, true)
        small.recycle()

        return result
    }
}

