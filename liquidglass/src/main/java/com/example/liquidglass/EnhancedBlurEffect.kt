/**
 * 增强的模糊效果处理器
 *
 * 集成多种模糊算法，支持动态切换：
 * 1. 传统 Box Blur（AdvancedFastBlur）
 * 2. IIR 递归高斯模糊（标量版本）
 * 3. IIR 递归高斯模糊（NEON 优化版本）
 * 4. Box3 快速模糊
 * 5. 智能选择
 * 6. 下采样管线
 *
 * 性能优化：
 * - 支持 NEON 向量化加速
 * - 智能算法选择
 * - 下采样管线优化
 * - 缓存复用
 * - 优化捕获范围（支持异形元素如圆角）
 *
 * 使用示例：
 * ```kotlin
 * val blurEffect = EnhancedBlurEffect(view)
 * blurEffect.blurMethod = BlurMethod.SMART
 * blurEffect.highQuality = false
 * blurEffect.enableOptimizedCapture = true
 * blurEffect.cornerRadius = 24f
 * val blurred = blurEffect.applyEffect(backdrop, blurRadius)
 * ```
 */
package com.example.liquidglass

import android.graphics.*
import android.util.Log
import android.view.View
import com.example.blur.NativeGauss
import kotlin.math.roundToInt

/**
 * 增强的背景模糊效果处理器
 */
class EnhancedBlurEffect(
    private val view: View
) {
    companion object {
        private const val TAG = "EnhancedBlurEffect"
        
        // 模糊半径到 σ 的转换系数
        // blurRadius ≈ 3σ (感知半径)
        private const val RADIUS_TO_SIGMA = 0.33f
        
        // 下采样阈值：当 σ > 此值时，推荐使用下采样管线
        private const val DOWNSAMPLE_THRESHOLD = 15f
    }

    // 传统 Box Blur 工具
    private val fastBlur = AdvancedFastBlur()

    // 模糊方法（默认智能选择）
    var blurMethod = BlurMethod.SMART

    // 高质量模式（仅对 IIR 高斯有效）
    var highQuality = false

    // 下采样比例（2 或 3）
    var downsampleScale = 2
        set(value) {
            field = value.coerceIn(2, 3)
        }

    // ✅ 优化捕获范围开关（启用后仅捕获异形区域，降低渲染量）
    var enableOptimizedCapture = false

    // ✅ 圆角半径（用于优化捕获）
    var cornerRadius = 0f

    // ✅ 捕获扩展边距（用于模糊扩散，避免边缘裁切）
    // 建议值：blurRadius * 2
    var captureMargin = 0f

    // NEON 支持检测（延迟初始化）
    private val neonSupported: Boolean by lazy {
        try {
            NativeGauss.hasNeonSupport()
        } catch (e: Exception) {
            Log.w(TAG, "NEON support check failed: ${e.message}")
            false
        }
    }
    
    /**
     * 捕获视图背后的背景
     *
     * @param bounds 视图边界
     * @param downsample 下采样比例 (0-1]。小于 1 时直接在缩小的 Canvas 上绘制父视图，
     *                   避免"全尺寸截图 + 二次缩放"的额外分配和拷贝
     * @return 背景 Bitmap（尺寸为 bounds * downsample）
     */
    fun captureBackdrop(bounds: RectF, downsample: Float = 1f): Bitmap? {
        // 背景来源：默认直接父容器，setBackdropSource 后为指定视图（可跨层级）
        val parent = (view as? LiquidGlassView)?.backdropView
            ?: view.parent as? View
            ?: return null

        // ✅ 优化捕获范围：根据是否启用优化捕获来决定捕获区域
        val captureBounds = if (enableOptimizedCapture && cornerRadius > 0) {
            // 计算实际需要捕获的最小矩形区域（考虑圆角和模糊扩散）
            calculateOptimizedBounds(bounds)
        } else {
            bounds
        }

        // 创建背景 Bitmap（按下采样比例缩小）
        val scale = downsample.coerceIn(0.01f, 1f)
        val width = (captureBounds.width() * scale).toInt().coerceAtLeast(1)
        val height = (captureBounds.height() * scale).toInt().coerceAtLeast(1)
        val backdrop = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(backdrop)

        try {
            // 获取视图相对背景视图的实际位置（屏幕坐标差，兼容跨层级/跨 window）
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            val parentLocation = IntArray(2)
            parent.getLocationOnScreen(parentLocation)

            // 计算视图相对于父容器的偏移
            val offsetX = (location[0] - parentLocation[0]).toFloat()
            val offsetY = (location[1] - parentLocation[1]).toFloat()

            // ✅ 先缩放再平移：父视图直接绘制到缩小的画布上
            if (scale < 1f) {
                canvas.scale(scale, scale)
            }
            canvas.translate(-offsetX - captureBounds.left, -offsetY - captureBounds.top)

            // ✅ 如果启用优化捕获，应用圆角裁剪
            if (enableOptimizedCapture && cornerRadius > 0) {
                val clipPath = Path()
                val clipRect = RectF(
                    offsetX + captureBounds.left,
                    offsetY + captureBounds.top,
                    offsetX + captureBounds.right,
                    offsetY + captureBounds.bottom
                )
                clipPath.addRoundRect(clipRect, cornerRadius, cornerRadius, Path.Direction.CW)
                canvas.clipPath(clipPath)
            }

            // 绘制父视图(不包括当前视图)
            // ✅ 通过 isCapturingBackdrop 标志让 LiquidGlassView 跳过自身绘制，
            //    避免切换 visibility 触发父视图 invalidate 造成的额外重绘
            val glassView = view as? LiquidGlassView
            if (glassView != null) {
                glassView.isCapturingBackdrop = true
                try {
                    parent.draw(canvas)
                } finally {
                    glassView.isCapturingBackdrop = false
                }
            } else {
                // 非 LiquidGlassView 的兜底路径：仍用 visibility 方案
                val wasVisible = view.visibility
                view.visibility = View.INVISIBLE
                parent.draw(canvas)
                view.visibility = wasVisible
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to capture backdrop: ${e.message}")
            // 如果捕获失败，返回半透明白色背景
            canvas.drawColor(android.graphics.Color.argb(200, 255, 255, 255))
        }

        return backdrop
    }

    /**
     * 计算优化后的捕获边界
     *
     * 对于圆角矩形，实际需要捕获的区域可以小于完整矩形
     * 同时考虑模糊扩散，需要额外的边距
     *
     * @param bounds 原始边界
     * @return 优化后的边界
     */
    private fun calculateOptimizedBounds(bounds: RectF): RectF {
        // ✅ 添加捕获边距以避免模糊边缘被裁切
        // 模糊会导致像素扩散，需要额外捕获周围区域
        val margin = captureMargin.coerceAtLeast(0f)

        return RectF(
            (bounds.left - margin).coerceAtLeast(0f),
            (bounds.top - margin).coerceAtLeast(0f),
            bounds.right + margin,
            bounds.bottom + margin
        )
    }
    
    /**
     * 应用模糊效果
     *
     * 饱和度不在此处理：由 LiquidGlassView 在最终绘制时通过
     * ColorMatrixColorFilter 应用（零成本，无需额外的全图复制 pass）
     *
     * @param backdrop 原始背景
     * @param blurRadius 模糊半径 (0-25)
     * @return 处理后的背景
     */
    fun applyEffect(
        backdrop: Bitmap,
        blurRadius: Float
    ): Bitmap {
        return if (blurRadius > 0f) {
            applyBlur(backdrop, blurRadius)
        } else {
            backdrop
        }
    }
    
    /**
     * 应用模糊效果（根据选择的方法）
     *
     * @param bitmap 原始图像
     * @param radius 模糊半径 (0-25)
     * @return 模糊后的图像
     */
    private fun applyBlur(bitmap: Bitmap, radius: Float): Bitmap {
        val clampedRadius = radius.coerceIn(0f, 25f)

        // 转换为 σ 值（用于 IIR 高斯）
        val sigma = clampedRadius * RADIUS_TO_SIGMA

        return when (blurMethod) {
            BlurMethod.BOX_BLUR -> applyBoxBlur(bitmap, clampedRadius)
            BlurMethod.BOX_BLUR_CPP -> applyBoxBlurCpp(bitmap, clampedRadius)
            BlurMethod.IIR_GAUSSIAN -> applyIIRGaussian(bitmap, sigma)
            BlurMethod.IIR_GAUSSIAN_NEON -> applyIIRGaussianNeon(bitmap, sigma)
            BlurMethod.BOX3 -> applyBox3(bitmap, sigma)
            BlurMethod.SMART -> applySmartBlur(bitmap, sigma)
            BlurMethod.DOWNSAMPLE -> applyDownsampleBlur(bitmap, sigma)
        }
    }
    
    /**
     * 传统 Box Blur（使用 AdvancedFastBlur - Kotlin 实现）
     */
    private fun applyBoxBlur(bitmap: Bitmap, radius: Float): Bitmap {
        return fastBlur.blur(
            bitmap = bitmap,
            radius = radius,
            downscale = 0.5f  // 降采样 50%
        )
    }

    /**
     * C++ Box Blur（使用 C++ 原生实现）
     */
    private fun applyBoxBlurCpp(bitmap: Bitmap, radius: Float): Bitmap {
        // 创建可编辑副本
        val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)

        try {
            NativeGauss.advancedBoxBlurInplace(
                bitmap = mutableBitmap,
                radius = radius,
                downscale = 0.5f  // 降采样 50%
            )
        } catch (e: Exception) {
            Log.e(TAG, "C++ Box Blur failed: ${e.message}")
            // 回退到 Kotlin Box Blur
            return applyBoxBlur(bitmap, radius)
        }

        return mutableBitmap
    }

    /**
     * IIR 递归高斯模糊（标量版本）
     */
    private fun applyIIRGaussian(bitmap: Bitmap, sigma: Float): Bitmap {
        // 创建可编辑副本
        val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        
        try {
            NativeGauss.gaussianIIRInplace(mutableBitmap, sigma, highQuality)
        } catch (e: Exception) {
            Log.e(TAG, "IIR Gaussian blur failed: ${e.message}")
            // 回退到 Box Blur
            return applyBoxBlur(bitmap, sigma * 3f)
        }
        
        return mutableBitmap
    }
    
    /**
     * IIR 递归高斯模糊（NEON 优化版本）
     */
    private fun applyIIRGaussianNeon(bitmap: Bitmap, sigma: Float): Bitmap {
        if (!neonSupported) {
            Log.w(TAG, "NEON not supported, fallback to scalar IIR")
            return applyIIRGaussian(bitmap, sigma)
        }
        
        // 创建可编辑副本
        val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        
        try {
            NativeGauss.gaussianIIRNeonInplace(mutableBitmap, sigma, highQuality)
        } catch (e: Exception) {
            Log.e(TAG, "IIR Gaussian NEON blur failed: ${e.message}")
            // 回退到标量版本
            return applyIIRGaussian(bitmap, sigma)
        }
        
        return mutableBitmap
    }
    
    /**
     * Box3 快速模糊
     */
    private fun applyBox3(bitmap: Bitmap, sigma: Float): Bitmap {
        // 创建可编辑副本
        val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        
        // σ 转换为 Box3 半径：radius ≈ σ * 1.2
        val radius = (sigma * 1.2f).toInt().coerceAtLeast(1)
        
        try {
            NativeGauss.box3Inplace(mutableBitmap, radius)
        } catch (e: Exception) {
            Log.e(TAG, "Box3 blur failed: ${e.message}")
            // 回退到 Box Blur
            return applyBoxBlur(bitmap, sigma * 3f)
        }
        
        return mutableBitmap
    }
    
    /**
     * 智能选择模糊算法
     */
    private fun applySmartBlur(bitmap: Bitmap, sigma: Float): Bitmap {
        // 创建可编辑副本
        val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        
        try {
            NativeGauss.smartBlur(mutableBitmap, sigma, highQuality)
        } catch (e: Exception) {
            Log.e(TAG, "Smart blur failed: ${e.message}")
            // 回退到 Box Blur
            return applyBoxBlur(bitmap, sigma * 3f)
        }
        
        return mutableBitmap
    }
    
    /**
     * 下采样管线模糊
     */
    private fun applyDownsampleBlur(bitmap: Bitmap, sigma: Float): Bitmap {
        try {
            return NativeGauss.downsampleBlur(bitmap, sigma, downsampleScale, highQuality)
        } catch (e: Exception) {
            Log.e(TAG, "Downsample blur failed: ${e.message}")
            // 回退到智能模糊
            return applySmartBlur(bitmap, sigma)
        }
    }
    
    /**
     * 释放资源
     */
    fun release() {
        fastBlur.cleanup()
    }
}

