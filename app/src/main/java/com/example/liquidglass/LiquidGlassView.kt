/**
 * LiquidGlass 主视图组件
 *
 * Android 版本的 LiquidGlass 效果,移植自 liquid-glass-react
 *
 * 核心功能:
 * - 背景模糊和饱和度调整
 * - 边缘扭曲效果
 * - 色差效果
 * - 触摸交互和弹性动画
 * - 阴影效果（可选）
 *
 * 使用示例:
 * ```xml
 * <com.example.liquidglass.LiquidGlassView
 *     android:layout_width="wrap_content"
 *     android:layout_height="wrap_content"
 *     app:displacementScale="70"
 *     app:blurAmount="0.0625"
 *     app:saturation="140"
 *     app:aberrationIntensity="2"
 *     app:elasticity="0.15"
 *     app:cornerRadius="999" />
 * ```
 */
package com.example.liquidglass

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.Build
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import kotlin.math.*

class LiquidGlassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "LiquidGlassView"
        private const val ENABLE_PERFORMANCE_LOG = false  // 性能日志开关（仅调试时打开，每帧构造日志字符串有开销）
        private const val ENABLE_MEMORY_LOG = false  // 内存日志开关（默认关闭，避免日志污染）

        // 背景变化检测的抽样网格（8x8 = 最多 64 个采样点）
        private const val BACKDROP_SAMPLE_GRID = 8
    }

    // ✅ 效果开关
    var enableBackdropBlur = true  // 背景模糊
    var enableChromaticAberration = true  // 色差效果
        set(value) {
            if (field != value) {
                field = value
                aberrationDirty = true
                if (ENABLE_PERFORMANCE_LOG) {
                    Log.d(TAG, "🔄 切换色差效果: $value, aberrationDirty=$aberrationDirty")
                }
                invalidate()
            }
        }
    var enableChromaticDispersion = false  // 色散效果（物理光学）
        set(value) {
            if (field != value) {
                field = value
                dispersionDirty = true
                if (ENABLE_PERFORMANCE_LOG) {
                    Log.d(TAG, "🔄 切换色散效果: $value, dispersionDirty=$dispersionDirty")
                }
                invalidate()
            }
        }
    var enableShadow = false  // 启用阴影（默认关闭，避免轮廓）
    var enableEdgeHighlight = true  // 边缘高光效果（默认开启）

    // ✅ 边缘高光参数
    var edgeHighlightBorderWidth = 1.5f  // 边框宽度（像素）
    var edgeHighlightOpacity = 100f  // 高光不透明度（0-100）

    // ✅ 模糊方法选择（新增）
    var blurMethod = BlurMethod.SMART
        set(value) {
            if (field != value) {
                field = value
                enhancedBlurEffect.blurMethod = value
                blurDirty = true
                invalidate()
            }
        }

    // ✅ 高质量模式（新增，仅对 IIR 高斯有效）
    var highQualityBlur = false
        set(value) {
            if (field != value) {
                field = value
                enhancedBlurEffect.highQuality = value
                blurDirty = true
                invalidate()
            }
        }

    // ✅ 下采样比例（新增，仅对 DOWNSAMPLE 方法有效）
    var downsampleScale = 2
        set(value) {
            val clamped = value.coerceIn(2, 3)
            if (field != clamped) {
                field = clamped
                enhancedBlurEffect.downsampleScale = clamped
                blurDirty = true
                invalidate()
            }
        }

    // ✅ 全局下采样比例（应用于所有效果：截图→缩小→处理→放大）
    var globalDownsampleFactor = 1.0f
        set(value) {
            val clamped = value.coerceIn(0.25f, 1.0f)
            if (field != clamped) {
                field = clamped
                blurDirty = true
                aberrationDirty = true
                invalidate()
            }
        }

    // ✅ 优化背景捕获（启用后仅捕获异形区域，降低渲染量）
    var enableOptimizedCapture = false
        set(value) {
            if (field != value) {
                field = value
                enhancedBlurEffect.enableOptimizedCapture = value
                blurDirty = true
                invalidate()
            }
        }

    // ✅ 色差效果下采样比例（独立控制）
    var aberrationDownsample = 0.5f
        set(value) {
            val clamped = value.coerceIn(0.25f, 1.0f)
            if (field != clamped) {
                field = clamped
                aberrationDirty = true
                invalidate()
            }
        }

    // ✅ 色差通道偏移量（精细控制）
    var aberrationRedOffset = 0f
        set(value) {
            if (field != value) {
                field = value
                aberrationDirty = true
                invalidate()
            }
        }

    var aberrationGreenOffset = -0.05f
        set(value) {
            if (field != value) {
                field = value
                aberrationDirty = true
                invalidate()
            }
        }

    var aberrationBlueOffset = -0.1f
        set(value) {
            if (field != value) {
                field = value
                aberrationDirty = true
                invalidate()
            }
        }

    // ✅ 色散效果参数
    var dispersionThickness = 100f
        set(value) {
            if (field != value) {
                field = value
                dispersionDirty = true
                invalidate()
            }
        }

    var dispersionFactor = 1.5f
        set(value) {
            if (field != value) {
                field = value
                dispersionDirty = true
                invalidate()
            }
        }

    var dispersionGain = 7f
        set(value) {
            if (field != value) {
                field = value
                dispersionDirty = true
                invalidate()
            }
        }

    var dispersionDownsample = 0.5f
        set(value) {
            val clamped = value.coerceIn(0.25f, 1.0f)
            if (field != clamped) {
                field = clamped
                dispersionDirty = true
                invalidate()
            }
        }

    // 效果参数(对应 React 版本的 props) - 带脏标记的属性
    var displacementScale = 70f
        set(value) {
            if (field != value) {
                field = value
                aberrationDirty = true
                invalidate()
            }
        }

    var blurAmount = 0.0625f
        set(value) {
            if (field != value) {
                field = value
                blurDirty = true
                invalidate()
            }
        }

    var saturation = 140f
        set(value) {
            if (field != value) {
                field = value
                // 饱和度在最终绘制时通过 colorFilter 应用，无需重新走模糊管线
                updateSaturationFilter()
                invalidate()
            }
        }

    var aberrationIntensity = 2f
        set(value) {
            if (field != value) {
                field = value
                aberrationDirty = true
                invalidate()
            }
        }

    var elasticity = 0.15f             // 弹性系数
    var cornerRadius = 999f            // 圆角半径

    var overLight = false
        set(value) {
            if (field != value) {
                field = value
                blurDirty = true
                aberrationDirty = true
                invalidate()
            }
        }

    var displacementMode = DisplacementMode.STANDARD
        set(value) {
            if (field != value) {
                field = value
                aberrationDirty = true
                invalidate()
            }
        }

    // 效果处理器
    private val enhancedBlurEffect = EnhancedBlurEffect(this)  // 增强模糊效果
    private val chromaticAberrationEffect = ChromaticAberrationEffect()
    private val chromaticDispersionEffect = ChromaticDispersionEffect()  // 色散效果
    private val edgeHighlightEffect = EdgeHighlightEffect()

    /**
     * 色差效果性能模式
     *
     * 控制色差效果使用的实现方式：
     * - AUTO: 自动选择（大图用 C++，小图用 Kotlin）
     * - CPP: 强制使用 C++ 实现（推荐，性能提升 3-5 倍）
     * - KOTLIN: 强制使用 Kotlin 实现（兼容性好）
     */
    var chromaticAberrationMode: ChromaticAberrationEffect.PerformanceMode
        get() = chromaticAberrationEffect.performanceMode
        set(value) {
            if (chromaticAberrationEffect.performanceMode != value) {
                chromaticAberrationEffect.performanceMode = value
                aberrationDirty = true
                invalidate()
            }
        }

    /**
     * 色差效果双线性插值开关
     *
     * 控制色差效果的采样质量：
     * - true: 双线性插值（高质量，平滑采样，无马赛克，性能开销 2-3 倍）
     * - false: 最近邻采样（性能优先，可能有轻微马赛克）
     *
     * 注意：仅对 Kotlin 实现有效，C++ 实现始终使用双线性插值
     */
    var aberrationUseBilinearInterpolation: Boolean
        get() = chromaticAberrationEffect.useBilinearInterpolation
        set(value) {
            if (chromaticAberrationEffect.useBilinearInterpolation != value) {
                chromaticAberrationEffect.useBilinearInterpolation = value
                aberrationDirty = true
                invalidate()
            }
        }

    // 自定义背景捕获器
    private var customBackdropCapture: ((RectF) -> Bitmap?)? = null

    // 位移贴图缓存
    private var displacementMaps: Map<DisplacementMode, Bitmap>? = null

    // ✅ 智能缓存机制 - 分层缓存策略
    private var cachedBackdrop: Bitmap? = null          // L1: 原始背景
    private var cachedBlurred: Bitmap? = null           // L2: 模糊后的背景
    private var cachedResult: Bitmap? = null            // L3: 最终结果

    // 背景变化检测
    private var lastBackdropHash: Int = 0
    private var lastBlurRadius: Float = -1f
    private var lastAberrationIntensity: Float = -1f

    // 脏标记（不包括 backdrop，因为每帧都需要捕获以支持动态背景）
    private var blurDirty = true
    private var aberrationDirty = true
    private var dispersionDirty = true

    // ✅ 动态背景模式（控制是否持续重绘）
    var enableDynamicBackground = false
        set(value) {
            if (field != value) {
                field = value
                if (value) {
                    invalidate()  // 启用时开始重绘循环
                }
            }
        }

    // ==================== 性能统计（替代解析 logcat） ====================

    /**
     * 单帧渲染统计
     *
     * @param captureMs 背景捕获耗时
     * @param blurMs 模糊处理耗时（缓存命中时为 0）
     * @param effectMs 色差/色散耗时（缓存命中时为 0）
     * @param finalizeMs 收尾耗时
     * @param totalMs 管线总耗时
     * @param effectName 当前生效的效果（"GPU模糊"/"色散"/"色差"/"无"）
     * @param blurRecomputed 本帧是否重算了模糊（false = 缓存命中）
     * @param effectRecomputed 本帧是否重算了色差/色散
     * @param processedWidth 实际处理的图像宽度（下采样后）
     * @param processedHeight 实际处理的图像高度
     * @param drawFps 实测绘制帧率（按每秒 onDraw 次数统计）
     */
    data class FrameStats(
        val captureMs: Float,
        val blurMs: Float,
        val effectMs: Float,
        val finalizeMs: Float,
        val totalMs: Float,
        val effectName: String,
        val blurRecomputed: Boolean,
        val effectRecomputed: Boolean,
        val processedWidth: Int,
        val processedHeight: Int,
        val drawFps: Int
    )

    /** 是否采集每帧统计（仅几次 System.nanoTime() 调用，开销可忽略，默认开启） */
    var collectFrameStats = true

    /** 最近一帧的渲染统计（供性能监控 UI 轮询读取） */
    @Volatile
    var lastFrameStats: FrameStats? = null
        private set

    /** 每帧统计回调（可选；在主线程渲染时同步调用，注意不要做重活） */
    var frameStatsListener: ((FrameStats) -> Unit)? = null

    // 实测 FPS 计数
    private var fpsWindowStartNs = 0L
    private var fpsFrameCount = 0
    private var measuredFps = 0

    // ✅ API 31+ 全 GPU 模糊渲染器（延迟创建）
    private var hardwareBlur: HardwareBackdropBlur? = null

    /**
     * 允许在 API 31+ 使用全 GPU 渲染路径（RenderNode + RenderEffect）
     *
     * 该路径零 Bitmap 分配、零 CPU 像素处理、零 GPU→CPU 回读，
     * 目前覆盖"背景模糊 + 饱和度"；开启色差/色散或自定义背景捕获时
     * 自动回退到 CPU 管线
     */
    var useHardwareBlurWhenPossible = true
        set(value) {
            if (field != value) {
                field = value
                blurDirty = true
                aberrationDirty = true
                invalidate()
            }
        }

    // 触摸交互状态
    private var touchX = 0f
    private var touchY = 0f
    private var isPressed = false
    private var scaleX = 1f
    private var scaleY = 1f

    // 触摸偏移量（归一化，-100 到 100）
    private var touchOffsetX = 0f
    private var touchOffsetY = 0f
    
    // 动画
    private var scaleAnimator: ValueAnimator? = null
    
    // 绘制相关
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clipPath = Path()  // 用于圆角裁剪
    private val resultSrcRect = Rect()  // 复用，避免每帧分配
    private val resultDstRect = Rect()

    // ✅ 捕获背景期间跳过自身绘制
    // （替代切换 visibility 的方案：setVisibility 会触发父视图 invalidate，造成额外重绘）
    internal var isCapturingBackdrop = false

    init {
        setWillNotDraw(false)

        // ✅ 启用硬件加速 - 性能提升 60-80%
        setLayerType(View.LAYER_TYPE_HARDWARE, null)

        // 初始化阴影
        updateShadow()

        // 初始化饱和度滤镜
        updateSaturationFilter()

        // 异步生成位移贴图
        post {
            generateDisplacementMaps()
        }
    }

    override fun draw(canvas: Canvas) {
        if (isCapturingBackdrop) return
        super.draw(canvas)
    }

    /**
     * 更新饱和度滤镜（在最终绘制时应用，省掉一次全图复制的独立 pass）
     */
    private fun updateSaturationFilter() {
        paint.colorFilter = if (saturation != 100f) {
            ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(saturation / 100f) })
        } else {
            null
        }
    }
    
    /**
     * 生成位移贴图
     */
    private fun generateDisplacementMaps() {
        if (width > 0 && height > 0) {
            displacementMaps = DisplacementMapGenerator.generateStandardMaps(width, height)
            invalidate()
        }
    }
    
    /**
     * 更新阴影效果
     */
    private fun updateShadow() {
        val shadowRadius = if (overLight) 70f else 40f
        val shadowAlpha = if (overLight) 0.75f else 0.25f
        
        shadowPaint.color = Color.argb((shadowAlpha * 255).toInt(), 0, 0, 0)
        shadowPaint.maskFilter = BlurMaskFilter(shadowRadius, BlurMaskFilter.Blur.NORMAL)
    }
    
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        // 重新生成位移贴图
        if (w > 0 && h > 0) {
            generateDisplacementMaps()
        }

        // 更新圆角裁剪路径
        updateClipPath()

        // ✅ 清除所有缓存
        cachedBackdrop?.recycle()
        cachedBlurred?.recycle()
        cachedResult?.recycle()
        cachedBackdrop = null
        cachedBlurred = null
        cachedResult = null

        // 标记所有层为脏（背景每帧都会捕获，不需要标记）
        lastBackdropHash = 0  // 重置背景哈希
        blurDirty = true
        aberrationDirty = true
    }

    /**
     * 更新圆角裁剪路径
     */
    private fun updateClipPath() {
        if (width > 0 && height > 0) {
            clipPath.reset()
            val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())
            clipPath.addRoundRect(rect, cornerRadius, cornerRadius, Path.Direction.CW)
        }
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (width <= 0 || height <= 0) return

        // ✅ 实测帧率统计（每秒结算一次）
        if (collectFrameStats) {
            val now = System.nanoTime()
            if (fpsWindowStartNs == 0L) fpsWindowStartNs = now
            fpsFrameCount++
            val elapsed = now - fpsWindowStartNs
            if (elapsed >= 1_000_000_000L) {
                measuredFps = (fpsFrameCount * 1_000_000_000L / elapsed).toInt()
                fpsFrameCount = 0
                fpsWindowStartNs = now
            }
        }

        // 应用缩放变换
        canvas.save()
        canvas.scale(scaleX, scaleY, width / 2f, height / 2f)
        
        // 绘制阴影
        drawShadow(canvas)
        
        // 绘制玻璃效果
        drawGlassEffect(canvas)
        
        canvas.restore()
    }
    
    /**
     * 绘制阴影（可选）
     */
    private fun drawShadow(canvas: Canvas) {
        if (!enableShadow) return  // ✅ 如果禁用阴影，直接返回

        val shadowOffset = if (overLight) 16f else 12f
        val rect = RectF(0f, shadowOffset, width.toFloat(), height.toFloat() + shadowOffset)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, shadowPaint)
    }
    
    /**
     * 绘制玻璃效果（同步渲染版 - 稳定无闪烁）
     */
    private fun drawGlassEffect(canvas: Canvas) {
        // ✅ 同步渲染，避免 Bitmap 生命周期问题
        val bounds = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val calculatedBlurRadius = (if (overLight) 12f else 4f) + blurAmount * 32f

        // ✅ API 31+ 全 GPU 快速路径（模糊+饱和度，零拷贝）
        if (tryDrawHardwareBlur(canvas, calculatedBlurRadius)) {
            if (enableEdgeHighlight) {
                drawEdgeHighlight(canvas, bounds)
            }
            if (enableDynamicBackground) {
                invalidate()
            }
            return
        }

        // 直接调用渲染逻辑
        renderGlassEffectSync(bounds, calculatedBlurRadius)

        // 绘制结果（应用圆角裁剪 + 饱和度滤镜；下采样时自动放大回原始尺寸）
        cachedResult?.let {
            if (!it.isRecycled) {
                val saveCount = canvas.save()
                canvas.clipPath(clipPath)

                resultSrcRect.set(0, 0, it.width, it.height)
                resultDstRect.set(0, 0, width, height)
                canvas.drawBitmap(it, resultSrcRect, resultDstRect, paint)

                canvas.restoreToCount(saveCount)
            }
        }

        // ✅ 绘制边缘高光效果
        if (enableEdgeHighlight) {
            drawEdgeHighlight(canvas, bounds)
        }

        // ✅ 仅在启用动态背景模式时持续重绘
        if (enableDynamicBackground) {
            invalidate()
        }
    }

    /**
     * 尝试走 API 31+ 的全 GPU 渲染路径
     *
     * 覆盖范围：
     * - API 31+：背景模糊 + 饱和度（RenderEffect）
     * - API 33+：额外支持色差（RuntimeShader，与 CPU 实现同一套位移贴图算法）
     * - 色散、自定义背景捕获仍走 CPU 管线
     *
     * 满足条件时不产生任何 Bitmap 分配与 CPU 像素处理。
     *
     * @return true 表示已完成绘制，调用方无需再走 CPU 管线
     */
    private fun tryDrawHardwareBlur(canvas: Canvas, blurRadius: Float): Boolean {
        if (!useHardwareBlurWhenPossible) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        if (!canvas.isHardwareAccelerated) return false
        if (enableChromaticDispersion) return false
        if (customBackdropCapture != null) return false

        // 色差：API 33+ 用 RuntimeShader 在 GPU 上完成；32 及以下回退 CPU
        val wantsAberration = enableChromaticAberration && aberrationIntensity > 0f
        var aberrationParams: HardwareBackdropBlur.AberrationParams? = null
        if (wantsAberration) {
            if (!HardwareBackdropBlur.supportsRuntimeShader()) return false
            // 位移贴图尚未生成时先回退 CPU（CPU 路径同样会跳过色差）
            val map = displacementMaps?.get(displacementMode) ?: return false
            val effectiveScale = if (overLight) displacementScale * 0.5f else displacementScale
            aberrationParams = HardwareBackdropBlur.AberrationParams(
                displacementMap = map,
                displacementScale = effectiveScale,
                redOffset = aberrationRedOffset * aberrationIntensity,
                greenOffset = aberrationGreenOffset * aberrationIntensity,
                blueOffset = aberrationBlueOffset * aberrationIntensity
            )
        }

        val startNs = if (collectFrameStats) System.nanoTime() else 0L

        val renderer = hardwareBlur ?: HardwareBackdropBlur().also { hardwareBlur = it }
        val effectiveRadius = if (enableBackdropBlur) blurRadius else 0f
        val ok = renderer.draw(canvas, this, effectiveRadius, saturation, clipPath, aberrationParams)

        if (ok && collectFrameStats) {
            // GPU 路径只统计 CPU 侧的录制耗时（实际模糊/色差在 GPU 异步执行）
            val totalMs = (System.nanoTime() - startNs) / 1_000_000f
            val stats = FrameStats(
                captureMs = 0f,
                blurMs = 0f,
                effectMs = 0f,
                finalizeMs = 0f,
                totalMs = totalMs,
                effectName = if (aberrationParams != null) "GPU模糊+色差" else "GPU模糊",
                blurRecomputed = false,
                effectRecomputed = false,
                processedWidth = width,
                processedHeight = height,
                drawFps = measuredFps
            )
            lastFrameStats = stats
            frameStatsListener?.invoke(stats)
        }
        return ok
    }

    /**
     * 绘制边缘高光效果
     */
    private fun drawEdgeHighlight(canvas: Canvas, bounds: RectF) {
        val touchOffset = PointF(touchOffsetX, touchOffsetY)
        edgeHighlightEffect.draw(
            canvas = canvas,
            bounds = bounds,
            cornerRadius = cornerRadius,
            mouseOffset = touchOffset,
            overLight = overLight,
            borderWidth = edgeHighlightBorderWidth,
            opacity = edgeHighlightOpacity
        )
    }
    
    /**
     * 设置自定义背景捕获器
     *
     * 用于支持固定玻璃组件捕获滚动背景
     */
    fun setCustomBackdropCapture(capture: (RectF) -> Bitmap?) {
        customBackdropCapture = capture
    }

    /**
     * 同步渲染玻璃效果（主线程调用，优化版）
     */
    private fun renderGlassEffectSync(bounds: RectF, blurRadius: Float) {
        val scale = if (overLight) displacementScale * 0.5f else displacementScale

        // ✅ 检测参数变化（饱和度已移到最终绘制的 colorFilter，不再影响模糊缓存）
        val blurChanged = blurRadius != lastBlurRadius
        val aberrationChanged = aberrationIntensity != lastAberrationIntensity

        // ✅ 性能监控 - 详细分阶段计时（服务于 FrameStats 和可选的 logcat 日志）
        val collectTiming = collectFrameStats || ENABLE_PERFORMANCE_LOG
        var blurRecomputed = false
        var effectRecomputed = false
        var t1 = 0L
        var t2 = 0L
        var t3 = 0L
        var t4 = 0L
        var t5 = 0L
        if (collectTiming) {
            t1 = System.nanoTime()
        }

        // ✅ 同步优化捕获参数到 EnhancedBlurEffect
        if (enableOptimizedCapture) {
            enhancedBlurEffect.cornerRadius = cornerRadius
            enhancedBlurEffect.captureMargin = blurRadius * 2f  // 模糊扩散边距
        }

        // 1. 捕获背景（L1 缓存）- 每帧都捕获以支持动态背景
        // ✅ 内置捕获路径直接在缩小的 Canvas 上绘制父视图，
        //    避免"全尺寸截图 + createScaledBitmap"的额外分配和缩放 pass
        var backdrop = if (customBackdropCapture != null) {
            customBackdropCapture?.invoke(bounds)?.let { full ->
                if (globalDownsampleFactor < 1.0f) {
                    val scaledWidth = (full.width * globalDownsampleFactor).toInt().coerceAtLeast(1)
                    val scaledHeight = (full.height * globalDownsampleFactor).toInt().coerceAtLeast(1)
                    val scaled = Bitmap.createScaledBitmap(full, scaledWidth, scaledHeight, true)
                    if (scaled != full) full.recycle()
                    scaled
                } else {
                    full
                }
            }
        } else {
            enhancedBlurEffect.captureBackdrop(bounds, globalDownsampleFactor)
        }

        if (backdrop == null) {
            val fallbackWidth = (width * globalDownsampleFactor).toInt().coerceAtLeast(1)
            val fallbackHeight = (height * globalDownsampleFactor).toInt().coerceAtLeast(1)
            backdrop = Bitmap.createBitmap(fallbackWidth, fallbackHeight, Bitmap.Config.ARGB_8888)
            backdrop.eraseColor(Color.argb(200, 255, 255, 255))
        }

        // ✅ 检测背景是否真的变化了（支持滚动背景）
        // 注意：必须做内容抽样，Bitmap.hashCode() 是对象身份哈希，每帧新建对象永远不同
        val backdropHash = computeBackdropSignature(backdrop)
        if (backdropHash != lastBackdropHash) {
            cachedBackdrop?.recycle()
            cachedBackdrop = backdrop
            lastBackdropHash = backdropHash
            blurDirty = true
        } else {
            // 背景没变化，回收新捕获的
            backdrop.recycle()
        }

        if (collectTiming) t2 = System.nanoTime()

        // 2. 应用模糊和饱和度（L2 缓存）- 可选
        if (enableBackdropBlur && (blurDirty || blurChanged)) {
            cachedBackdrop?.let { backdrop ->
                // 关闭模糊时 cachedBlurred 会直接引用 cachedBackdrop，此时不能回收
                if (cachedBlurred != cachedBackdrop) {
                    cachedBlurred?.recycle()
                }
                // ✅ 使用增强模糊效果（支持多种算法）
                cachedBlurred = enhancedBlurEffect.applyEffect(backdrop, blurRadius)
                lastBlurRadius = blurRadius
                aberrationDirty = true
                blurRecomputed = true
            }
            blurDirty = false
        } else if (!enableBackdropBlur && cachedBackdrop != null) {
            // 模糊关闭，直接使用背景
            if (cachedBlurred != cachedBackdrop) {
                cachedBlurred?.recycle()
            }
            cachedBlurred = cachedBackdrop
            aberrationDirty = true
        }

        if (collectTiming) t3 = System.nanoTime()

        // 3. 应用色差或色散效果（互斥）
        val displacementMap = displacementMaps?.get(displacementMode)

        // 3a. 色散效果（优先级高于色差）- 每次都执行
        if (enableChromaticDispersion) {
            cachedBlurred?.let { blurred ->
                val dispersed = chromaticDispersionEffect.apply(
                    source = blurred,
                    refThickness = dispersionThickness,
                    refFactor = dispersionFactor,
                    refDispersion = dispersionGain,
                    downscale = dispersionDownsample,
                    cornerRadius = cornerRadius  // 传递圆角半径
                )

                cachedResult?.recycle()
                cachedResult = dispersed
                effectRecomputed = true
            }

            if (collectTiming) t4 = System.nanoTime()

            aberrationDirty = false  // 重置色差脏标记
            dispersionDirty = false  // 重置色散脏标记
        }
        // 3b. 色差效果
        else if (enableChromaticAberration && (aberrationDirty || aberrationChanged) && aberrationIntensity > 0 && displacementMap != null) {
            cachedBlurred?.let { blurred ->
                // ✅ 使用降采样处理，速度提升 4倍，并传递通道偏移参数
                val aberrated = chromaticAberrationEffect.apply(
                    source = blurred,
                    displacementMap = displacementMap,
                    intensity = aberrationIntensity,
                    scale = displacementScale,
                    downscale = aberrationDownsample,
                    redOffset = aberrationRedOffset,
                    greenOffset = aberrationGreenOffset,
                    blueOffset = aberrationBlueOffset
                )

                if (collectTiming) t4 = System.nanoTime()

                // 4. 直接使用色差效果结果（已移除圆角遮罩）
                cachedResult?.recycle()
                cachedResult = aberrated
                lastAberrationIntensity = aberrationIntensity
                effectRecomputed = true
            }
            aberrationDirty = false
            dispersionDirty = false  // 重置色散脏标记
        }
        // 3c. 无效果
        else if (aberrationDirty || dispersionDirty || !enableChromaticAberration) {
            if (collectTiming) t4 = System.nanoTime()

            // 没有色差/色散效果，直接使用模糊后的结果（已移除圆角遮罩）
            cachedBlurred?.let { blurred ->
                cachedResult?.recycle()
                cachedResult = blurred.copy(blurred.config ?: Bitmap.Config.ARGB_8888, true)
            }
            aberrationDirty = false
            dispersionDirty = false
        }

        if (collectTiming) {
            t5 = System.nanoTime()
            // 效果分支全部缓存命中时 t4 不会被赋值
            if (t4 == 0L) t4 = t3

            val captureTime = (t2 - t1) / 1_000_000f
            val blurTime = (t3 - t2) / 1_000_000f
            val aberrationTime = (t4 - t3) / 1_000_000f
            val finalizeTime = (t5 - t4) / 1_000_000f
            val totalTime = (t5 - t1) / 1_000_000f

            val effectName = when {
                enableChromaticDispersion -> "色散"
                enableChromaticAberration -> "色差"
                else -> "无"
            }

            // ✅ 结构化统计（供性能监控 UI 直接读取，替代解析 logcat）
            if (collectFrameStats) {
                val stats = FrameStats(
                    captureMs = captureTime,
                    blurMs = blurTime,
                    effectMs = aberrationTime,
                    finalizeMs = finalizeTime,
                    totalMs = totalTime,
                    effectName = effectName,
                    blurRecomputed = blurRecomputed,
                    effectRecomputed = effectRecomputed,
                    processedWidth = cachedBackdrop?.width ?: 0,
                    processedHeight = cachedBackdrop?.height ?: 0,
                    drawFps = measuredFps
                )
                lastFrameStats = stats
                frameStatsListener?.invoke(stats)
            }

            // 可选的 logcat 文本日志（默认关闭，每帧字符串拼接有开销）
            if (ENABLE_PERFORMANCE_LOG) {
                Log.d(TAG, """
                    |📊 [性能分析] 各效果耗时:
                    |  1️⃣ 捕获背景: ${String.format("%.3f", captureTime)}ms ${if (enableBackdropBlur) "✅" else "⏭️"}
                    |  2️⃣ 模糊处理: ${String.format("%.3f", blurTime)}ms ${if (enableBackdropBlur) "✅" else "⏭️"}
                    |  3️⃣ $effectName 效果: ${String.format("%.3f", aberrationTime)}ms ${if (enableChromaticDispersion || enableChromaticAberration) "✅" else "⏭️"}
                    |  4️⃣ 最终处理: ${String.format("%.3f", finalizeTime)}ms
                    |  ⏱️ 总耗时: ${String.format("%.3f", totalTime)}ms (~${(1000f / totalTime).toInt()} FPS)
                    |  💾 缓存状态: blur=${!blurDirty}, aberration=${!aberrationDirty}, dispersion=${!dispersionDirty}
                """.trimMargin())
            }
        }

        // ✅ 内存监控（可选，默认关闭）
        if (ENABLE_MEMORY_LOG) {
            logMemoryUsage()
        }
    }

    /**
     * 对背景位图做稀疏抽样校验和，用于检测背景内容是否变化
     *
     * 背景位图已经过下采样，最多采样 8x8=64 个像素，开销可忽略。
     * 抽样有极小概率漏检（变化恰好都落在采样点之间），
     * 需要严格逐帧刷新的场景请开启 enableDynamicBackground。
     */
    private fun computeBackdropSignature(bitmap: Bitmap): Int {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return 0

        var hash = w * 31 + h
        val stepX = (w / BACKDROP_SAMPLE_GRID).coerceAtLeast(1)
        val stepY = (h / BACKDROP_SAMPLE_GRID).coerceAtLeast(1)
        var y = stepY / 2
        while (y < h) {
            var x = stepX / 2
            while (x < w) {
                hash = hash * 31 + bitmap.getPixel(x, y)
                x += stepX
            }
            y += stepY
        }
        return hash
    }

    /**
     * 记录内存使用情况
     */
    private fun logMemoryUsage() {
        val runtime = Runtime.getRuntime()
        val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
        val maxMemory = runtime.maxMemory() / 1024 / 1024
        val freeMemory = runtime.freeMemory() / 1024 / 1024

        Log.d(TAG, """
            |💾 [内存监控]
            |  已用: ${usedMemory}MB
            |  可用: ${freeMemory}MB
            |  最大: ${maxMemory}MB
            |  使用率: ${(usedMemory * 100 / maxMemory)}%
        """.trimMargin())
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                touchX = event.x
                touchY = event.y
                isPressed = true
                updateTouchOffset(event.x, event.y)
                animateScale(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                touchX = event.x
                touchY = event.y
                updateTouchOffset(event.x, event.y)
                updateElasticScale()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isPressed = false
                touchOffsetX = 0f
                touchOffsetY = 0f
                animateScale(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * 更新触摸偏移量（归一化到 -100 到 100）
     * 用于边缘高光的动态渐变效果
     */
    private fun updateTouchOffset(x: Float, y: Float) {
        val centerX = width / 2f
        val centerY = height / 2f

        // 归一化到 -100 到 100
        touchOffsetX = ((x - centerX) / width) * 200f
        touchOffsetY = ((y - centerY) / height) * 200f
    }
    
    /**
     * 更新弹性缩放
     * 对应 React 版本的 calculateElasticScale
     */
    private fun updateElasticScale() {
        val centerX = width / 2f
        val centerY = height / 2f
        
        val deltaX = touchX - centerX
        val deltaY = touchY - centerY
        val centerDistance = sqrt(deltaX * deltaX + deltaY * deltaY)
        
        if (centerDistance < 1f) {
            scaleX = 1f
            scaleY = 1f
            return
        }
        
        val normalizedX = deltaX / centerDistance
        val normalizedY = deltaY / centerDistance
        val stretchIntensity = min(centerDistance / 300f, 1f) * elasticity
        
        scaleX = 1f + abs(normalizedX) * stretchIntensity * 0.3f - abs(normalizedY) * stretchIntensity * 0.15f
        scaleY = 1f + abs(normalizedY) * stretchIntensity * 0.3f - abs(normalizedX) * stretchIntensity * 0.15f
        
        scaleX = max(0.8f, scaleX)
        scaleY = max(0.8f, scaleY)
        
        invalidate()
    }
    
    /**
     * 缩放动画
     */
    private fun animateScale(pressed: Boolean) {
        scaleAnimator?.cancel()
        
        val targetScale = if (pressed) 0.95f else 1f
        
        scaleAnimator = ValueAnimator.ofFloat(scaleX, targetScale).apply {
            duration = 200
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val value = it.animatedValue as Float
                scaleX = value
                scaleY = value
                invalidate()
            }
            start()
        }
    }
    
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()

        // ✅ 清理所有缓存和资源
        scaleAnimator?.cancel()
        enhancedBlurEffect.release()  // 清理增强模糊效果

        // 清理 GPU 渲染器
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hardwareBlur?.release()
        }
        hardwareBlur = null

        // ✅ 清理效果处理器
        chromaticAberrationEffect.cleanup()
        edgeHighlightEffect.cleanup()

        // 清理分层缓存
        cachedBackdrop?.recycle()
        cachedBlurred?.recycle()
        cachedResult?.recycle()

        cachedBackdrop = null
        cachedBlurred = null
        cachedResult = null

        // 清理位移贴图
        displacementMaps?.values?.forEach { it.recycle() }
        displacementMaps = null
    }
}

