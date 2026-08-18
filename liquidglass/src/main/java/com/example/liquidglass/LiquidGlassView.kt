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
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import kotlin.math.*

open class LiquidGlassView @JvmOverloads constructor(
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

        /**
         * 全局亮度采样标志：BackdropLuminanceMeter 采样父视图期间为 true，
         * 所有玻璃视图跳过自绘 —— 既避免玻璃影响自己的亮度读数，
         * 也防止采样画布（软件渲染）级联触发其他玻璃视图的完整 CPU 管线
         */
        internal var isLuminanceSampling = false
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

    /** 点击/按压效果开关（按压缩放 + 拖拽弹性拉伸 + 透镜按压形变） */
    var enablePressEffect = true

    /** 按压时的缩放目标（1 = 按下不缩放） */
    var pressScale = 0.95f
        set(value) {
            field = value.coerceIn(0.5f, 1f)
        }

    // 圆角半径（运行时可调，GPU/CPU 路径均生效）
    var cornerRadius = 999f
        set(value) {
            if (field != value) {
                field = value
                updateClipPath()
                blurDirty = true
                aberrationDirty = true
                dispersionDirty = true
                invalidate()
            }
        }

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

    // ==================== Liquid Glass 2.0（API 33+ 统一透镜管线） ====================

    /**
     * 允许使用 API 33+ 的统一透镜着色器管线（SDF 折射 + 色散 + 高光 + 内阴影 + 融合）
     *
     * 渲染路径优先级：
     * - useShaderPipeline && useHardwareBlurWhenPossible && API 33+ → 透镜管线（2.0）
     * - useHardwareBlurWhenPossible && API 31+ → 旧 GPU 管线（模糊+饱和度+旧色差）
     * - 否则 → CPU 管线
     */
    var useShaderPipeline = true
        set(value) {
            if (field != value) {
                field = value
                if (!value) ensureDisplacementMaps()
                updateSensorRegistration()
                blurDirty = true
                aberrationDirty = true
                invalidate()
            }
        }

    /** 材质变体（REGULAR=自适应染色重可读性 / CLEAR=高透+压暗层） */
    var material = GlassMaterial.REGULAR
        set(value) {
            if (field != value) {
                field = value
                updateAdaptiveMeter()
                blurDirty = true
                invalidate()
            }
        }

    /** 边缘斜面带宽度（px）：玻璃"厚度"的视觉宽度（仅透镜管线） */
    var bevelWidth = 40f
        set(value) {
            val clamped = value.coerceIn(2f, 200f)
            if (field != clamped) {
                field = clamped
                invalidate()
            }
        }

    /** 边缘最大折射位移（px）：透镜弯折强度（仅透镜管线；采样有安全钳制，大值也不会越界） */
    var refractionHeight = 200f
        set(value) {
            val clamped = value.coerceIn(0f, 300f)
            if (field != clamped) {
                field = clamped
                invalidate()
            }
        }

    /** 色散强度（0-1）：三通道折射差异，边缘光谱边纹宽度（仅透镜管线） */
    var dispersionStrength = 0.10f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            if (field != clamped) {
                field = clamped
                invalidate()
            }
        }

    /** 高光跟随重力传感器（光源固定在"世界"里，倾斜设备时高光移动；仅透镜管线，默认关闭） */
    var enableSensorHighlight = false
        set(value) {
            if (field != value) {
                field = value
                updateSensorRegistration()
                invalidate()
            }
        }

    /** 背景亮度自适应染色（Regular 材质；持续采样背景明暗并联动前景外观回调；默认关闭） */
    var enableAdaptiveTint = false
        set(value) {
            if (field != value) {
                field = value
                updateAdaptiveMeter()
                invalidate()
            }
        }

    /** 无障碍渲染模式（AUTO 跟随系统「高对比度文字」自动退化为不透明材质） */
    var accessibilityMode = GlassAccessibilityMode.AUTO
        set(value) {
            if (field != value) {
                field = value
                refreshAccessibilityState()
            }
        }

    /**
     * 玻璃外观变化回调：背景明暗越过阈值时触发（带滞回）
     *
     * @param isOverLight true = 亮背景，前景内容建议切换为深色
     */
    var glassAppearanceListener: ((isOverLight: Boolean) -> Unit)? = null

    /**
     * 子类钩子：背景明暗翻转时先于 [glassAppearanceListener] 调用
     *
     * 内置小部件（按钮/FAB/标签条）靠它自动切换前景配色，
     * 不占用公开的 listener —— 使用方仍可自由设置 [glassAppearanceListener]
     */
    protected open fun onAppearanceChanged(isOverLight: Boolean) {}

    /** 当前是否判定为亮背景（自适应开启时由亮度采样驱动，否则等于 [overLight]） */
    val isOverLightBackground: Boolean
        get() = if (enableAdaptiveTint && material.adaptiveTint) adaptiveOverLight else overLight

    // 透镜管线内部状态
    private var lensRenderer: GlassLensRenderer? = null
    private var primaryShape: RectF? = null       // null = 视图整体
    private var primaryShapeCorner = 0f
    private var secondaryShape: RectF? = null
    private var secondaryShapeCorner = 999f
    private var shapeBlendSmoothing = 48f

    // 按压/触摸液态状态（透镜管线 uniform）
    private var pressDepth = 0f
    private var pressAnimator: ValueAnimator? = null

    // 亮度自适应
    private var luminanceMeter: BackdropLuminanceMeter? = null
    private var adaptiveOverLight = false
    private var adaptiveTintColor = 0x24FFFFFF
    private var lastAppliedTint = 0

    // API 36+ AGSL 运行时效果（CPU 管线的最终合成用；不支持时为 null 走回退）
    private var adaptiveTintBlender: RuntimeXfermode? = null
    private var adaptiveTintBlenderTried = false
    private var vibrancySatFilter: RuntimeColorFilter? = null
    private var vibrancySatTried = false
    private var vibrancySatFor = Float.NaN
    private var paintFilterIsVibrancy = false

    // 无障碍状态缓存（attach 时刷新，宿主可调用 refreshAccessibilityState 主动刷新）
    private var a11yReducedTransparency = false
    private var a11yReducedMotion = false
    private var a11yPowerSave = false
    private val opaquePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val opaqueBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val tintOverlayPaint = Paint(Paint.ANTI_ALIAS_FLAG)

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

    // ==================== 背景来源（脱离层级约束） ====================

    /**
     * 指定背景来源视图（null = 直接父容器，默认行为）
     *
     * 玻璃默认捕获它的直接父容器，因此背景内容必须和玻璃在同一个父容器里。
     * 指定 [backdropSource] 后改为捕获这个视图的绘制内容，玻璃可以放在
     * 视图树的任意位置——覆盖区域由两者的屏幕坐标差实时计算。
     *
     * 与 [setCustomBackdropCapture] 的区别：这条路**保留 GPU 管线**
     * （API 31+ RenderEffect / API 33+ AGSL 透镜），自定义捕获则会强制回退 CPU。
     *
     * ```kotlin
     * // 玻璃浮在 RecyclerView 上方，两者不在同一父容器里也能取到背景
     * glass.backdropSource = recyclerView
     * ```
     *
     * 注意：
     * - source 不能是玻璃自身或它的后代（会造成录制重入），传入时被忽略并打日志
     * - 玻璃超出 source 边界的部分捕获为透明，需要自行保证覆盖关系
     * - source 所在的树滚动时会自动触发重绘（见 [backdropScrollListener]）；
     *   内容以其它方式变化（动画等）需要自行 [invalidate] 或开 [enableDynamicBackground]
     */
    var backdropSource: View? = null
        set(value) {
            if (field === value) return
            if (value != null && !isValidBackdropSource(value)) {
                Log.w(TAG, "backdropSource 不能是玻璃自身或其后代，已忽略")
                return
            }
            unregisterBackdropScrollListener()
            field = value
            pendingBackdropSourceId = 0
            if (isAttachedToWindow) registerBackdropScrollListener()
            invalidate()
        }

    /** XML 里 app:backdropSourceId 指定的 id，在挂载后从根视图解析 */
    private var pendingBackdropSourceId = 0

    /** source 所在的树滚动时重绘玻璃（背景内容变了，折射结果必须跟着变） */
    private val backdropScrollListener = ViewTreeObserver.OnScrollChangedListener { invalidate() }
    private var registeredBackdropObserver: ViewTreeObserver? = null

    /**
     * 实际捕获的背景视图：未指定 [backdropSource] 时退回直接父容器
     */
    internal val backdropView: View?
        get() = backdropSource ?: parent as? View

    /** source 是玻璃自身或其后代时，捕获会对正在录制的 RenderNode 重入 */
    private fun isValidBackdropSource(source: View): Boolean {
        if (source === this) return false
        var p = source.parent
        while (p != null) {
            if (p === this) return false
            p = p.parent
        }
        return true
    }

    private fun registerBackdropScrollListener() {
        val source = backdropSource ?: return
        if (registeredBackdropObserver != null) return
        if (!source.isAttachedToWindow) return
        val observer = source.viewTreeObserver
        if (!observer.isAlive) return
        observer.addOnScrollChangedListener(backdropScrollListener)
        registeredBackdropObserver = observer
    }

    private fun unregisterBackdropScrollListener() {
        val observer = registeredBackdropObserver ?: return
        if (observer.isAlive) observer.removeOnScrollChangedListener(backdropScrollListener)
        registeredBackdropObserver = null
    }

    // 位移贴图缓存（跨 detach 保留，仅尺寸变化时重建；透镜管线不需要）
    private var displacementMaps: Map<DisplacementMode, Bitmap>? = null
    private var mapGenerationId = 0  // 异步生成版本号，防止过期结果覆盖
    private var mapGenerationPending = false  // 防止旧管线每帧重复触发生成

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
     * @param effectName 当前生效的效果，取值为与语言无关的英文标识：
     *                   "GPU Lens" / "GPU Blur" / "GPU Blur+CA" / "Dispersion" / "Aberration" / "None"。
     *                   库不带本地化资源，这里返回中文会直接漏进调用方的 UI
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
                if (!value) ensureDisplacementMaps()
                updateSensorRegistration()
                blurDirty = true
                aberrationDirty = true
                invalidate()
            }
        }

    /**
     * 调试用：把渲染管线的版本分层钳制到指定 API 级别，在高版本设备上
     * 预览低版本机型的实际效果。
     *
     * 分层对照：36+ AGSL 颜色滤镜/混合模式增强 → 33+ 透镜管线 →
     * 31+ 旧 GPU 模糊 → 以下 CPU 管线。只影响本库的路径选择，真实设备
     * 能力不足的层不会因此解锁。<= 0 或 [Int.MAX_VALUE] = 不钳制（默认）。
     * 仅调试观察用，不要在生产代码里设置。
     */
    var debugApiLevelCap: Int = Int.MAX_VALUE
        set(value) {
            val v = if (value <= 0) Int.MAX_VALUE else value
            if (field != v) {
                field = v
                // 钳制变化可能切换管线/滤镜：清掉 vibrancy 状态并重建效果链
                updateSaturationFilter()
                if (!lensPathLikely()) ensureDisplacementMaps()
                updateSensorRegistration()
                blurDirty = true
                aberrationDirty = true
                invalidate()
            }
        }

    /** 当前生效的 API 级别（真实 SDK 与 [debugApiLevelCap] 取小，只读） */
    val effectiveApiLevel: Int
        get() = minOf(Build.VERSION.SDK_INT, debugApiLevelCap)

    // 触摸交互状态
    private var touchX = 0f
    private var touchY = 0f
    private var isPressed = false

    // 缩放状态：按压缩放（动画驱动）与弹性拉伸（拖拽驱动）相乘后
    // 应用到 View 变换属性（见 applyGlassScale）
    private var basePressScale = 1f
    private var stretchScaleX = 1f
    private var stretchScaleY = 1f

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

        // XML 属性解析（README 承诺的 app:xxx 属性在此生效）
        attrs?.let { parseAttributes(context, it) }

        // 初始化阴影
        updateShadow()

        // 初始化饱和度滤镜
        updateSaturationFilter()

        // 异步生成位移贴图（透镜管线不需要，跳过以省一次后台计算）
        post {
            maybeGenerateDisplacementMaps()
        }
    }

    /**
     * 解析 XML 属性
     */
    private fun parseAttributes(context: Context, attrs: AttributeSet) {
        val ta = context.obtainStyledAttributes(attrs, R.styleable.LiquidGlassView)
        try {
            displacementScale = ta.getFloat(R.styleable.LiquidGlassView_displacementScale, displacementScale)
            blurAmount = ta.getFloat(R.styleable.LiquidGlassView_blurAmount, blurAmount)
            saturation = ta.getFloat(R.styleable.LiquidGlassView_saturation, saturation)
            aberrationIntensity = ta.getFloat(R.styleable.LiquidGlassView_aberrationIntensity, aberrationIntensity)
            elasticity = ta.getFloat(R.styleable.LiquidGlassView_elasticity, elasticity)
            cornerRadius = ta.getDimension(R.styleable.LiquidGlassView_cornerRadius, cornerRadius)
            bevelWidth = ta.getDimension(R.styleable.LiquidGlassView_bevelWidth, bevelWidth)
            refractionHeight = ta.getDimension(R.styleable.LiquidGlassView_refractionHeight, refractionHeight)
            dispersionStrength = ta.getFloat(R.styleable.LiquidGlassView_dispersionStrength, dispersionStrength)
            enableSensorHighlight = ta.getBoolean(R.styleable.LiquidGlassView_sensorHighlight, enableSensorHighlight)
            enableAdaptiveTint = ta.getBoolean(R.styleable.LiquidGlassView_adaptiveTint, enableAdaptiveTint)
            material = if (ta.getInt(R.styleable.LiquidGlassView_glassMaterial, 0) == 1) {
                GlassMaterial.CLEAR
            } else {
                GlassMaterial.REGULAR
            }
            // 背景来源只能记下 id：此时目标视图还没 inflate 完，挂载后再从根视图解析
            pendingBackdropSourceId = ta.getResourceId(R.styleable.LiquidGlassView_backdropSourceId, 0)
        } finally {
            ta.recycle()
        }
    }

    // ==================== 液态融合形状 API（仅透镜管线） ====================

    /**
     * 设置主玻璃形状（视图局部坐标，px）
     *
     * 默认 null = 玻璃充满整个视图（圆角为 [cornerRadius]）。
     * 液态融合场景下可在一个大视图内自定义玻璃几何。
     */
    fun setPrimaryShape(rect: RectF?, cornerRadiusPx: Float = cornerRadius) {
        primaryShape = rect?.let { RectF(it) }
        primaryShapeCorner = cornerRadiusPx
        invalidate()
    }

    /**
     * 设置副玻璃形状（液态融合的第二个玻璃体；null = 移除）
     *
     * 两个形状用 smin 平滑并集融合：接近时边缘像水银一样黏连、合并。
     *
     * @param smoothing smin 平滑宽度（px）——越大黏连范围越大
     */
    fun setSecondaryShape(rect: RectF?, cornerRadiusPx: Float = 999f, smoothing: Float = 48f) {
        secondaryShape = rect?.let { RectF(it) }
        secondaryShapeCorner = cornerRadiusPx
        shapeBlendSmoothing = smoothing.coerceIn(0f, 200f)
        invalidate()
    }

    override fun draw(canvas: Canvas) {
        if (isCapturingBackdrop) return
        if (isLuminanceSampling) return
        super.draw(canvas)
    }

    /**
     * 更新饱和度滤镜（在最终绘制时应用，省掉一次全图复制的独立 pass）
     *
     * 这里只准备线性 ColorMatrix（回退状态，软件画布也能执行）；
     * API 36+ 硬件画布在绘制前由 [applySaturationFilter] 换用 vibrancy 版本
     */
    private fun updateSaturationFilter() {
        paint.colorFilter = if (saturation != 100f) {
            ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(saturation / 100f) })
        } else {
            null
        }
        paintFilterIsVibrancy = false
    }

    /**
     * 绘制前按画布类型选择饱和度滤镜：API 36+ 硬件画布用 vibrancy
     * （非线性：低饱和多提、高饱和少提、高光保护，与透镜管线同曲线），
     * 软件画布不能执行 AGSL，保持/换回线性 ColorMatrix
     */
    private fun applySaturationFilter(hardwareCanvas: Boolean) {
        if (saturation == 100f) return
        if (!hardwareCanvas || Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA ||
            debugApiLevelCap < Build.VERSION_CODES.BAKLAVA
        ) {
            if (paintFilterIsVibrancy) updateSaturationFilter()
            return
        }
        if (!vibrancySatTried) {
            vibrancySatTried = true
            vibrancySatFilter = GlassRuntimeEffects.createVibrancyFilter()
        }
        val vib = vibrancySatFilter ?: return  // 不支持：保持线性滤镜
        val factor = saturation / 100f
        if (!paintFilterIsVibrancy || vibrancySatFor != factor) {
            vib.setFloatUniform("satFactor", factor)
            // 重新赋值触发 Paint 重新快照滤镜（仅换 uniform 不重挂不生效）
            paint.colorFilter = vib
            paintFilterIsVibrancy = true
            vibrancySatFor = factor
        }
    }
    
    /**
     * 异步生成位移贴图
     *
     * 生成是纯 CPU 计算（三种模式逐像素两遍循环），在主线程执行会导致
     * 布局/场景切换明显卡顿，因此放到后台线程；完成前色差效果暂缺，
     * 完成后自动重绘补上
     */
    private fun generateDisplacementMaps() {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return

        val genId = ++mapGenerationId
        mapGenerationPending = true
        Thread({
            val maps = DisplacementMapGenerator.generateStandardMaps(w, h)
            post {
                // 生成期间尺寸又变了/有更新的请求 → 丢弃本次结果
                if (genId != mapGenerationId || w != width || h != height) {
                    maps.values.forEach { it.recycle() }
                    if (genId == mapGenerationId) mapGenerationPending = false
                    return@post
                }
                displacementMaps?.values?.forEach { it.recycle() }
                displacementMaps = maps
                mapGenerationPending = false
                aberrationDirty = true
                invalidate()
            }
        }, "LiquidGlass-DispMap").start()
    }

    /** 透镜管线（API 33+）不使用位移贴图；仅旧管线可能用到时才生成 */
    private fun lensPathLikely(): Boolean =
        useShaderPipeline && useHardwareBlurWhenPossible &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            debugApiLevelCap >= Build.VERSION_CODES.TIRAMISU &&
            GlassLensRenderer.isSupported() && lensRenderer?.isAvailable != false

    private fun maybeGenerateDisplacementMaps() {
        if (lensPathLikely()) return
        generateDisplacementMaps()
    }

    /** 旧管线需要位移贴图但尚未生成时补一次生成 */
    private fun ensureDisplacementMaps() {
        if (displacementMaps == null && !mapGenerationPending && width > 0 && height > 0) {
            generateDisplacementMaps()
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

        // 重新生成位移贴图（透镜管线跳过）
        if (w > 0 && h > 0) {
            maybeGenerateDisplacementMaps()
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

        // 按压/弹性缩放通过 View 变换属性应用（见 applyGlassScale），不在这里做
        // canvas.scale：onDraw 画布已被裁剪到视图边界，画布缩放超过 1 时（拖拽弹性
        // 拉伸）左右边缘会被切掉；View 变换在裁剪之后应用，可安全溢出布局边界，
        // 且子内容（文字等）会跟随一起缩放

        // 绘制阴影
        drawShadow(canvas)

        // 绘制玻璃效果
        drawGlassEffect(canvas)
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

        // ✅ 无障碍降级：不透明材质（Reduce Transparency）
        if (shouldRenderOpaque()) {
            drawOpaqueFallback(canvas)
            return
        }

        // ✅ API 33+ 统一透镜管线（Liquid Glass 2.0：折射+色散+高光+内阴影+融合）
        if (tryDrawLensGlass(canvas, calculatedBlurRadius)) {
            // 边缘高光由着色器的法线光照完成，无需 Kotlin 层描边
            if (enableDynamicBackground) {
                invalidate()
            }
            return
        }

        // ✅ API 31+ 旧 GPU 快速路径（模糊+饱和度，零拷贝）
        if (tryDrawHardwareBlur(canvas, calculatedBlurRadius)) {
            if (enableEdgeHighlight) {
                drawEdgeHighlight(canvas, bounds)
            }
            if (enableDynamicBackground) {
                invalidate()
            }
            return
        }

        // 旧管线需要位移贴图（透镜管线跳过了生成，回退时补上）
        if (enableChromaticAberration && displacementMaps == null) {
            ensureDisplacementMaps()
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
                applySaturationFilter(canvas.isHardwareAccelerated)
                canvas.drawBitmap(it, resultSrcRect, resultDstRect, paint)

                // 自适应染色 / 材质染色（CPU 路径用覆盖层近似透镜管线的 tint）
                // API 36+ 硬件画布：RuntimeXfermode 按局部亮度逐像素染色
                // （与透镜管线同一条曲线），否则回退全局染色覆盖层
                var perPixelTinted = false
                if (material.adaptiveTint && enableAdaptiveTint &&
                    canvas.isHardwareAccelerated && GlassRuntimeEffects.isSupported &&
                    debugApiLevelCap >= Build.VERSION_CODES.BAKLAVA
                ) {
                    if (!adaptiveTintBlenderTried) {
                        adaptiveTintBlenderTried = true
                        adaptiveTintBlender = GlassRuntimeEffects.createAdaptiveTintBlender()
                    }
                    val blender = adaptiveTintBlender
                    if (blender != null) {
                        tintOverlayPaint.color = Color.WHITE
                        tintOverlayPaint.xfermode = blender
                        canvas.drawPath(clipPath, tintOverlayPaint)
                        tintOverlayPaint.xfermode = null
                        perPixelTinted = true
                    }
                }
                if (!perPixelTinted) {
                    val tint = currentTintColor()
                    if (Color.alpha(tint) > 0) {
                        tintOverlayPaint.color = tint
                        canvas.drawPath(clipPath, tintOverlayPaint)
                    }
                }

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

    // ==================== 透镜管线（Liquid Glass 2.0） ====================

    /**
     * 尝试走 API 33+ 统一透镜着色器管线
     *
     * 覆盖：模糊、饱和度、SDF 折射、色散、法线镜面高光（传感器光源）、
     * 内阴影、自适应染色、Clear 压暗、按压液态、双形状 smin 融合。
     * 自定义背景捕获仍走 CPU 管线。
     *
     * @return true 表示已完成绘制
     */
    private fun tryDrawLensGlass(canvas: Canvas, blurRadius: Float): Boolean {
        if (!useShaderPipeline || !useHardwareBlurWhenPossible) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        if (debugApiLevelCap < Build.VERSION_CODES.TIRAMISU) return false
        if (!GlassLensRenderer.isSupported()) return false
        if (!canvas.isHardwareAccelerated) return false
        if (customBackdropCapture != null) return false

        val renderer = lensRenderer ?: GlassLensRenderer().also { lensRenderer = it }
        if (!renderer.isAvailable) return false

        val startNs = if (collectFrameStats) System.nanoTime() else 0L

        val w = width.toFloat()
        val h = height.toFloat()

        // —— 形状（主形状默认充满视图） ——
        val p1 = primaryShape
        val s1cx = p1?.centerX() ?: (w / 2f)
        val s1cy = p1?.centerY() ?: (h / 2f)
        val s1hw = ((p1?.width() ?: w) / 2f).coerceAtLeast(1f)
        val s1hh = ((p1?.height() ?: h) / 2f).coerceAtLeast(1f)
        val r1 = (if (p1 != null) primaryShapeCorner else cornerRadius).coerceIn(0f, min(s1hw, s1hh))

        val p2 = secondaryShape
        val s2hw = if (p2 != null) (p2.width() / 2f).coerceAtLeast(1f) else 0f
        val s2hh = if (p2 != null) (p2.height() / 2f).coerceAtLeast(1f) else 0f
        val r2 = if (p2 != null) secondaryShapeCorner.coerceIn(0f, min(s2hw, s2hh)) else 0f

        // —— 色散：色差/色散任一开启即生效，量级沿用对应滑杆 ——
        val disp = when {
            enableChromaticDispersion ->
                (dispersionStrength * (dispersionGain / 7f)).coerceIn(0f, 0.9f)
            enableChromaticAberration && aberrationIntensity > 0f ->
                (dispersionStrength * (aberrationIntensity / 2f)).coerceIn(0f, 0.9f)
            else -> 0f
        }

        // —— 高光：沿用边缘高光开关/不透明度，乘材质增益 ——
        val spec = if (enableEdgeHighlight) (edgeHighlightOpacity / 100f) * material.specBoost else 0f

        // —— 光源方向（量化到 0.005，避免静止时反复重建 effect） ——
        val sensorActive = enableSensorHighlight && !a11yReducedMotion && !a11yPowerSave
        val lx: Float
        val ly: Float
        if (sensorActive) {
            lx = (LightSourceController.lightDirX * 200f).toInt() / 200f
            ly = (LightSourceController.lightDirY * 200f).toInt() / 200f
        } else {
            lx = 0.0f
            ly = 1.0f
        }

        // —— 模糊半径 × 材质缩放（量化到 0.5px） ——
        val radius = if (enableBackdropBlur) {
            ((blurRadius * material.blurScale) * 2f).toInt() / 2f
        } else {
            0f
        }

        // —— 按压/触摸（量化到 0.01） ——
        val press = (pressDepth * 100f).toInt() / 100f
        val tAmp = if (isPressed || press > 0f) press else 0f
        val tx = (touchX * 2f).toInt() / 2f
        val ty = (touchY * 2f).toInt() / 2f

        val margin = computeLensMargin(radius)

        // —— 染色：Regular + 自适应时改走着色器内逐像素染色；tint 固定传 0，
        // 避免亮度采样 tick 更新全局染色触发无意义的 effect 重建 ——
        val adaptivePerPixel = material.adaptiveTint && enableAdaptiveTint

        val params = GlassLensRenderer.LensParams(
            blurRadius = radius,
            shape1CX = s1cx, shape1CY = s1cy, shape1HW = s1hw, shape1HH = s1hh, radius1 = r1,
            shape2CX = p2?.centerX() ?: 0f, shape2CY = p2?.centerY() ?: 0f,
            shape2HW = s2hw, shape2HH = s2hh, radius2 = r2,
            blendK = if (p2 != null) shapeBlendSmoothing else 0f,
            bevel = bevelWidth,
            refract = refractionHeight,
            dispersion = disp,
            lightX = lx, lightY = ly,
            spec = spec,
            innerShadow = material.innerShadow,
            tint = if (adaptivePerPixel) 0 else currentTintColor(),
            adaptiveTint = adaptivePerPixel,
            dim = material.dimAmount,
            saturation = saturation,
            press = press,
            touchX = tx, touchY = ty, touchAmp = tAmp
        )

        val ok = renderer.draw(canvas, this, params, margin)

        if (ok && collectFrameStats) {
            // GPU 路径只统计 CPU 侧的录制耗时（实际着色在 GPU 异步执行）
            val totalMs = (System.nanoTime() - startNs) / 1_000_000f
            val stats = FrameStats(
                captureMs = 0f,
                blurMs = 0f,
                effectMs = 0f,
                finalizeMs = 0f,
                totalMs = totalMs,
                effectName = "GPU Lens",
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
     * 背景录制外扩边距：供模糊 pass 在视图边缘取到真实内容（约 3σ 拉入范围）
     *
     * 注意：折射采样是向内的（RuntimeShader 子输入越界会得到透明黑），
     * margin 只服务于模糊质量。16px 对齐减少 effect 重建。
     */
    private fun computeLensMargin(blurRadius: Float): Int {
        val need = max(blurRadius * 3f, 32f)
        return (((need.toInt() + 15) / 16) * 16).coerceAtLeast(32)
    }

    /**
     * 当前染色：自适应（亮度采样）或材质基础染色
     */
    private fun currentTintColor(): Int {
        if (material.adaptiveTint && enableAdaptiveTint) {
            return adaptiveTintColor
        }
        return if (overLight) 0x33000000 else material.baseTint
    }

    // ==================== 亮度自适应 ====================

    /**
     * 亮度样本回调（EMA 平滑后）：更新染色，越过明暗阈值时通知宿主
     */
    private fun onLuminanceSample(luminance: Float) {
        // smoothstep(0.35, 0.75)：0 = 暗背景，1 = 亮背景
        val s = ((luminance - 0.35f) / 0.40f).coerceIn(0f, 1f)
        val e = s * s * (3f - 2f * s)

        // 暗背景 → 白染色提亮玻璃；亮背景 → 黑染色压暗（保前景可读性）
        val channel = ((1f - e) * 255f).toInt()
        val alpha = ((0.14f + 0.08f * e) * 255f).toInt()
        adaptiveTintColor = Color.argb(alpha, channel, channel, channel)

        val meter = luminanceMeter
        if (meter != null && meter.isOverLight != adaptiveOverLight) {
            adaptiveOverLight = meter.isOverLight
            onAppearanceChanged(adaptiveOverLight)
            glassAppearanceListener?.invoke(adaptiveOverLight)
        }

        if (adaptiveTintColor != lastAppliedTint) {
            lastAppliedTint = adaptiveTintColor
            invalidate()
        }
    }

    private fun updateAdaptiveMeter() {
        val want = isAttachedToWindow && enableAdaptiveTint && material.adaptiveTint
        if (want) {
            val meter = luminanceMeter
                ?: BackdropLuminanceMeter(this) { onLuminanceSample(it) }.also { luminanceMeter = it }
            meter.intervalMs = if (a11yPowerSave) 1000L else 350L
            meter.start()
        } else {
            luminanceMeter?.stop()
        }
    }

    // ==================== 传感器光源 ====================

    private fun updateSensorRegistration() {
        val want = isAttachedToWindow && enableSensorHighlight && useShaderPipeline &&
            useHardwareBlurWhenPossible &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            debugApiLevelCap >= Build.VERSION_CODES.TIRAMISU &&
            GlassLensRenderer.isSupported() &&
            !a11yReducedMotion && !a11yPowerSave
        if (want) {
            LightSourceController.register(this)
        } else {
            LightSourceController.unregister(this)
        }
    }

    // ==================== 无障碍降级 ====================

    /**
     * 重新查询系统无障碍/省电状态（attach 时自动调用；设置变化后宿主可主动调用）
     */
    fun refreshAccessibilityState() {
        a11yReducedTransparency = GlassAccessibility.prefersReducedTransparency(context)
        a11yReducedMotion = GlassAccessibility.prefersReducedMotion(context)
        a11yPowerSave = GlassAccessibility.isPowerSaveMode(context)
        updateSensorRegistration()
        updateAdaptiveMeter()
        blurDirty = true
        aberrationDirty = true
        invalidate()
    }

    private fun shouldRenderOpaque(): Boolean = when (accessibilityMode) {
        GlassAccessibilityMode.FORCE_OPAQUE -> true
        GlassAccessibilityMode.FORCE_FULL -> false
        GlassAccessibilityMode.AUTO -> a11yReducedTransparency
    }

    /**
     * 不透明降级材质：实底圆角矩形 + 细边框（保证高对比度需求下的可读性）
     */
    private fun drawOpaqueFallback(canvas: Canvas) {
        val over = isOverLightBackground
        opaquePaint.color = if (over) 0xFFF2F2F6.toInt() else 0xFF2A2A2E.toInt()
        opaqueBorderPaint.color = if (over) 0x33000000 else 0x40FFFFFF
        val r = cornerRadius.coerceAtMost(min(width, height) / 2f)
        val rect = RectF(0.75f, 0.75f, width - 0.75f, height - 0.75f)
        canvas.drawRoundRect(rect, r, r, opaquePaint)
        canvas.drawRoundRect(rect, r, r, opaqueBorderPaint)
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
        if (debugApiLevelCap < Build.VERSION_CODES.S) return false
        if (!canvas.isHardwareAccelerated) return false
        if (enableChromaticDispersion) return false
        if (customBackdropCapture != null) return false

        // 色差：API 33+ 用 RuntimeShader 在 GPU 上完成；32 及以下回退 CPU
        val wantsAberration = enableChromaticAberration && aberrationIntensity > 0f
        var aberrationParams: HardwareBackdropBlur.AberrationParams? = null
        if (wantsAberration) {
            if (!HardwareBackdropBlur.supportsRuntimeShader()) return false
            if (debugApiLevelCap < Build.VERSION_CODES.TIRAMISU) return false
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
        renderer.debugApiLevelCap = debugApiLevelCap
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
                effectName = if (aberrationParams != null) "GPU Blur+CA" else "GPU Blur",
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
            opacity = edgeHighlightOpacity,
            apiLevelCap = debugApiLevelCap
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
        // 动态背景模式下跳过抽样判断：8×8 抽样存在漏检概率，动画背景直接视为每帧变化
        val backdropChanged = if (enableDynamicBackground) {
            true
        } else {
            val backdropHash = computeBackdropSignature(backdrop)
            if (backdropHash != lastBackdropHash) {
                lastBackdropHash = backdropHash
                true
            } else {
                false
            }
        }
        if (backdropChanged) {
            cachedBackdrop?.recycle()
            cachedBackdrop = backdrop
            blurDirty = true
            // 亮度自适应：CPU 路径直接复用背景位图采样，零额外捕获开销
            if (enableAdaptiveTint && material.adaptiveTint) {
                luminanceMeter?.submit(BackdropLuminanceMeter.measureBitmap(backdrop))
            }
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
                enableChromaticDispersion -> "Dispersion"
                enableChromaticAberration -> "Aberration"
                else -> "None"
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
                if (enablePressEffect) {
                    animateScale(true)
                    animatePress(true)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                touchX = event.x
                touchY = event.y
                updateTouchOffset(event.x, event.y)
                if (enablePressEffect) {
                    updateElasticScale()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isPressed = false
                touchOffsetX = 0f
                touchOffsetY = 0f
                // 无条件归位：即使按住期间效果被关闭，也不能卡在缩放状态
                animateScale(false)
                animatePress(false)
                // 事件被这里消费了，不自己派发的话 OnClickListener 永远不会触发；
                // 手指移出视图后抬起视为取消，与标准 Button 行为一致
                if (event.action == MotionEvent.ACTION_UP && isClickable &&
                    event.x in 0f..width.toFloat() && event.y in 0f..height.toFloat()
                ) {
                    performClick()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * 按压液态动画：驱动透镜管线的 press/touch uniform
     * （按下时折射增强 + 手指下方局部凸起，松开时回弹）
     */
    private fun animatePress(pressed: Boolean) {
        if (a11yReducedMotion) {
            // 减弱动效：直接跳变，不做过渡
            pressDepth = if (pressed) 1f else 0f
            invalidate()
            return
        }
        pressAnimator?.cancel()
        pressAnimator = ValueAnimator.ofFloat(pressDepth, if (pressed) 1f else 0f).apply {
            duration = if (pressed) 180 else 320
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                pressDepth = it.animatedValue as Float
                invalidate()
            }
            start()
        }
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
     * 合成缩放并应用到 View 变换属性（setScaleX/setScaleY）
     *
     * 不用 onDraw 里的 canvas.scale：视图画布被裁剪到自身边界，缩放超过 1 时
     * 边缘会被切掉。View 变换在裁剪之后由父视图应用，可以安全溢出布局边界。
     */
    private fun applyGlassScale() {
        scaleX = basePressScale * stretchScaleX
        scaleY = basePressScale * stretchScaleY
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
            stretchScaleX = 1f
            stretchScaleY = 1f
            applyGlassScale()
            return
        }

        val normalizedX = deltaX / centerDistance
        val normalizedY = deltaY / centerDistance
        val stretchIntensity = min(centerDistance / 300f, 1f) * elasticity

        stretchScaleX = max(0.8f, 1f + abs(normalizedX) * stretchIntensity * 0.3f - abs(normalizedY) * stretchIntensity * 0.15f)
        stretchScaleY = max(0.8f, 1f + abs(normalizedY) * stretchIntensity * 0.3f - abs(normalizedX) * stretchIntensity * 0.15f)

        applyGlassScale()
        invalidate()
    }

    /**
     * 按压缩放动画：按下缩到 [pressScale]，松开回到 1
     *
     * 只驱动 basePressScale，弹性拉伸由 updateElasticScale 独立驱动、
     * 二者相乘合成——避免旧实现里动画和拖拽互相覆盖同一个值导致的抖动；
     * 松手时弹性拉伸随同一动画平滑归位
     */
    private fun animateScale(pressed: Boolean) {
        scaleAnimator?.cancel()

        val target = if (pressed) pressScale else 1f
        val startBase = basePressScale
        val startStretchX = stretchScaleX
        val startStretchY = stretchScaleY

        scaleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 200
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                basePressScale = startBase + (target - startBase) * f
                if (!pressed) {
                    stretchScaleX = startStretchX + (1f - startStretchX) * f
                    stretchScaleY = startStretchY + (1f - startStretchY) * f
                }
                applyGlassScale()
                invalidate()
            }
            start()
        }
    }
    
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        // ✅ 位移贴图跨 detach 保留，这里只兜底（首次挂载且 init 的
        // post 尚未执行成功等情况）；缺失时补一次异步生成
        post {
            if (displacementMaps == null && width > 0 && height > 0) {
                maybeGenerateDisplacementMaps()
            }
        }

        // 查询无障碍/省电状态（内部会按需注册传感器光源、启动亮度采样）
        refreshAccessibilityState()

        // XML 指定的背景来源：整棵树 inflate 完才能按 id 找到，放到挂载时解析
        if (backdropSource == null && pendingBackdropSourceId != 0) {
            val id = pendingBackdropSourceId
            rootView?.findViewById<View>(id)?.let { backdropSource = it }
                ?: Log.w(TAG, "backdropSourceId 未在视图树中找到，回退直接父容器")
            pendingBackdropSourceId = 0
        }
        registerBackdropScrollListener()

        // 重新挂载后所有缓存已被清空，标记脏并重启重绘
        lastBackdropHash = 0
        blurDirty = true
        aberrationDirty = true
        dispersionDirty = true
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()

        // ✅ 清理所有缓存和资源
        scaleAnimator?.cancel()
        pressAnimator?.cancel()
        // 动画中途 detach 时归位缩放/按压状态，重挂载后不残留形变
        basePressScale = 1f
        stretchScaleX = 1f
        stretchScaleY = 1f
        applyGlassScale()
        pressDepth = 0f
        enhancedBlurEffect.release()  // 清理增强模糊效果

        // 注销传感器光源与亮度采样
        LightSourceController.unregister(this)
        luminanceMeter?.stop()

        // 解绑背景来源的滚动监听（backdropSource 引用保留，重新挂载后自动恢复）
        unregisterBackdropScrollListener()

        // 清理透镜渲染器
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            lensRenderer?.release()
        }
        lensRenderer = null

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

        // 注意：位移贴图跨 detach 保留（切换场景重挂载时无需重新生成，
        // 避免主线程卡顿），仅在尺寸变化时重建，最终随视图对象被 GC 回收
    }
}

