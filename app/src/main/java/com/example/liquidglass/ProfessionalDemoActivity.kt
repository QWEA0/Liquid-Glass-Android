/**
 * LiquidGlass 专业演示 Activity
 *
 * 功能特性：
 * - 4 个演示场景：滚动背景 / 图片背景 / 动画背景 / 多组件展示
 * - 卡片式现代调试面板（按 渲染路径 / 玻璃参数 / 色彩效果 分组，
 *   CPU 算法选项仅在强制 CPU 时显示）
 * - 实时性能监控（读取 LiquidGlassView.FrameStats）
 * - 背景图片选择、中英文切换
 */
package com.example.liquidglass

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.example.liquidglass.demo.R
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

class ProfessionalDemoActivity : AppCompatActivity() {

    // ==================== 场景 ====================

    private enum class Scene(val labelRes: Int) {
        SCROLL(R.string.scene_scroll),
        IMAGE(R.string.scene_image),
        ANIMATED(R.string.scene_animated),
        MERGE(R.string.scene_merge),
        HOME(R.string.scene_home),
        LIST(R.string.scene_list),
        SHEET(R.string.scene_sheet),
        TEXT(R.string.scene_text),
        SHOWCASE(R.string.scene_showcase),
        WIDGETS(R.string.scene_widgets)
    }

    private var currentScene = Scene.SCROLL

    // ==================== 视图 ====================

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var sceneHost: FrameLayout
    private lateinit var glassView: LiquidGlassView
    private val extraGlassViews = mutableListOf<LiquidGlassView>()
    private lateinit var fabSettings: FloatingActionButton
    private lateinit var tvPerformanceOverlay: TextView
    private lateinit var tvDebugInfo: TextView
    private lateinit var sceneBarScroll: HorizontalScrollView
    private val sceneChips = mutableListOf<TextView>()

    // 动态显隐的面板分组
    private lateinit var cpuOptionsGroup: LinearLayout
    private lateinit var lensGroup: LinearLayout
    private lateinit var aberrationGroup: LinearLayout
    private lateinit var dispersionGroup: LinearLayout

    // 性能监控数据源（融合场景使用场景内的玻璃视图）
    private var statsSource: LiquidGlassView? = null

    // 弹层场景的对话框与其中的玻璃（跨 window，切场景/销毁时必须关掉）
    private var bottomSheetDialog: BottomSheetDialog? = null
    private var sheetGlass: LiquidGlassView? = null

    /** 状态栏高度，由 window insets 回填。场景里顶部对齐的文字/组件靠它避开状态栏与性能悬浮窗 */
    private var systemBarTop = 0

    private var customBackgroundBitmap: Bitmap? = null
    private var scenicBitmap: Bitmap? = null

    // 性能监控
    private val performanceHandler = Handler(Looper.getMainLooper())
    private var isMonitoring = true

    // ==================== 系统 ====================

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri -> loadBackgroundImage(uri) }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) openImagePicker()
        else Toast.makeText(this, getString(R.string.toast_no_image_selected), Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "ProfessionalDemo"
        private const val PREF_NAME = "LiquidGlassPrefs"
        private const val KEY_LANGUAGE = "language"
        private const val LANG_ENGLISH = "en"
        private const val LANG_CHINESE = "zh"

        private const val COLOR_BG = 0xFFF2F2F7.toInt()       // 面板底色
        private const val COLOR_SCROLL_GUTTER = 0xFF0B1020.toInt()  // 滚动场景上下留白底色
        private const val COLOR_CARD = 0xFFFFFFFF.toInt()     // 卡片
        private const val COLOR_TEXT = 0xFF111111.toInt()     // 主文字
        private const val COLOR_TEXT_DIM = 0xFF8E8E93.toInt() // 次要文字
        private const val COLOR_ACCENT = 0xFF007AFF.toInt()   // 强调色
        private const val COLOR_SEG_BG = 0xFFE9E9EB.toInt()   // 分段控件底
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applySavedLanguage()
        super.onCreate(savedInstanceState)
        // 演示页全屏展示玻璃效果，ActionBar 只会挡住场景和性能悬浮窗。
        // 同时让场景铺到系统栏底下——玻璃拖到屏幕边缘时不该被一条纯色带截断。
        supportActionBar?.hide()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        createGlassView()
        createMainLayout()
        showScene(Scene.SCROLL)
        startPerformanceMonitoring()
    }

    // ==================== 布局骨架 ====================

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun dpF(v: Int): Float = v * resources.displayMetrics.density

    private fun createGlassView() {
        val label = TextView(this).apply {
            text = getString(R.string.glass_button_text)
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(36), dp(28), dp(36), dp(28))
            setShadowLayer(8f, 0f, 2f, Color.BLACK)
        }
        glassView = LiquidGlassView(this).apply {
            id = View.generateViewId()
            enableDynamicBackground = true
            addView(label)
            // 自适应外观：背景变亮时前景文字切换为深色（Apple Regular 玻璃行为）
            glassAppearanceListener = { isOverLight ->
                if (isOverLight) {
                    label.setTextColor(0xDE000000.toInt())
                    label.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
                } else {
                    label.setTextColor(Color.WHITE)
                    label.setShadowLayer(8f, 0f, 2f, Color.BLACK)
                }
            }
        }
    }

    private fun createMainLayout() {
        drawerLayout = DrawerLayout(this)

        val mainContent = FrameLayout(this)

        // 场景容器（背景 + 玻璃组件都在这里，父视图捕获不会带上 UI 覆盖层）
        sceneHost = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        mainContent.addView(sceneHost)

        // 性能悬浮窗
        tvPerformanceOverlay = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                // 顶部间距在下面按真实状态栏 inset 设置：API 35+ 强制 edge-to-edge，
                // mainContent 从窗口顶端起算，硬编码的 dp 值会被状态栏/刘海盖住
                setMargins(dp(8), dp(8), dp(8), 0)
            }
            background = GradientDrawable().apply {
                cornerRadius = dpF(10)
                setColor(0xCC000000.toInt())
            }
            setTextColor(0xFF4CFF7A.toInt())
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setPadding(dp(10), dp(6), dp(10), dp(6))
            text = getString(R.string.performance_waiting)
        }
        mainContent.addView(tvPerformanceOverlay)

        // 场景切换条
        val sceneBar = createSceneBar()
        mainContent.addView(sceneBar)

        // 设置按钮
        fabSettings = FloatingActionButton(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                setMargins(0, 0, dp(20), dp(96))
            }
            setImageResource(android.R.drawable.ic_menu_preferences)
            setOnClickListener { drawerLayout.openDrawer(GravityCompat.END) }
        }
        mainContent.addView(fabSettings)

        // 只有覆盖层让开系统栏，场景内容仍然铺满全屏。
        // 不要用固定 dp 值：状态栏高度随刘海/挖孔变化，手势条与三键导航也差一倍。
        ViewCompat.setOnApplyWindowInsetsListener(mainContent) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            systemBarTop = bars.top
            (tvPerformanceOverlay.layoutParams as FrameLayout.LayoutParams).apply {
                topMargin = bars.top + dp(8)
                rightMargin = bars.right + dp(8)
            }
            (sceneBar.layoutParams as FrameLayout.LayoutParams).bottomMargin = bars.bottom + dp(16)
            (fabSettings.layoutParams as FrameLayout.LayoutParams).apply {
                bottomMargin = bars.bottom + dp(84)
                rightMargin = bars.right + dp(20)
            }
            tvPerformanceOverlay.requestLayout()
            sceneBar.requestLayout()
            fabSettings.requestLayout()
            insets
        }

        drawerLayout.addView(mainContent)
        drawerLayout.addView(createDrawer())
        setContentView(drawerLayout)
    }

    private fun createSceneBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        Scene.entries.forEach { scene ->
            val chip = TextView(this).apply {
                text = getString(scene.labelRes)
                textSize = 13f
                gravity = Gravity.CENTER
                // 一行放不下时必须单行截断：被挤窄的 chip 会逐字换行，
                // 把整条 bar 撑成几倍高（黑底跟着变高就是这么来的）
                maxLines = 1
                setPadding(dp(13), dp(8), dp(13), dp(8))
                setOnClickListener { if (currentScene != scene) showScene(scene) }
            }
            sceneChips += chip
            bar.addView(chip)
        }

        // 场景数量超过一屏宽度后改为横向滚动，而不是把 chip 压扁。
        // 胶囊底色和圆角裁剪都挂在滚动容器（视口）上，这样无论内容多宽，
        // 看到的始终是一颗完整的、不出屏的胶囊，chip 在里面滚动
        sceneBarScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            // 两端渐隐提示还能往左右滚，而不是把文字硬切断
            isHorizontalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(20))
            background = GradientDrawable().apply {
                cornerRadius = dpF(22)
                setColor(0xB3000000.toInt())
            }
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dpF(22))
                }
            }
            clipToOutline = true
            setPadding(dp(6), dp(6), dp(6), dp(6))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(28)
                leftMargin = dp(12)
                rightMargin = dp(12)
            }
            addView(bar)
        }
        return sceneBarScroll
    }

    private fun updateSceneBar() {
        Scene.entries.forEachIndexed { i, scene ->
            val chip = sceneChips[i]
            if (scene == currentScene) {
                chip.background = GradientDrawable().apply {
                    cornerRadius = dpF(18)
                    setColor(Color.WHITE)
                }
                chip.setTextColor(COLOR_TEXT)
                chip.typeface = Typeface.DEFAULT_BOLD
            } else {
                chip.background = null
                chip.setTextColor(0xFFDDDDDD.toInt())
                chip.typeface = Typeface.DEFAULT
            }
        }

        // 选中项滚进可视区（切场景时它可能在屏幕外）
        val selected = sceneChips[Scene.entries.indexOf(currentScene)]
        sceneBarScroll.post {
            sceneBarScroll.smoothScrollTo(
                selected.left - (sceneBarScroll.width - selected.width) / 2, 0
            )
        }
    }

    // ==================== 场景 ====================

    private fun showScene(scene: Scene) {
        currentScene = scene
        extraGlassViews.clear()
        (glassView.parent as? ViewGroup)?.removeView(glassView)
        sceneHost.removeAllViews()
        statsSource = glassView
        // HOME 场景会给共享的 glassView 装拖动监听并改 translation，切走时必须还原，
        // 否则其他场景的居中布局会被上次拖动的位移带偏
        glassView.setOnTouchListener(null)
        glassView.translationX = 0f
        glassView.translationY = 0f
        // LIST 场景会把背景来源指到场景内的列表上，切走时必须解绑，
        // 否则其他场景的玻璃还在捕获一棵已经被移除的子树
        glassView.backdropSource = null
        // TEXT 场景会改软键盘模式，切走时还原
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        // SHEET 场景的弹层在独立 window 里，不随 sceneHost 的清空而消失
        dismissGlassBottomSheet()

        val root = when (scene) {
            Scene.SCROLL -> buildScrollScene()
            Scene.IMAGE -> buildImageScene()
            Scene.ANIMATED -> buildAnimatedScene()
            Scene.MERGE -> buildMergeScene()
            Scene.HOME -> buildHomeScene()
            Scene.LIST -> buildListScene()
            Scene.SHEET -> buildSheetScene()
            Scene.TEXT -> buildTextScene()
            Scene.SHOWCASE -> buildShowcaseScene()
            Scene.WIDGETS -> buildWidgetsScene()
        }
        sceneHost.addView(root)
        updateSceneBar()
    }

    private fun centerGlassParams(): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER }

    /** 场景 1：彩色渐变滚动背景 + 顶部渐进模糊 + 玻璃按钮 */
    private fun buildScrollScene(): View {
        val root = FrameLayout(this)
        val scroll = createColorScroll()
        root.addView(scroll)

        // 顶部渐进模糊（Scroll Edge Effect：内容滚入顶部时从清晰渐变到模糊）
        val edgeBlur = ScrollEdgeBlurView(this).apply {
            edge = ScrollEdgeBlurView.Edge.TOP
            maxBlurRadius = dpF(14)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(110)
            ).apply { gravity = Gravity.TOP }
        }
        edgeBlur.bindScrollView(scroll)
        root.addView(edgeBlur)

        glassView.layoutParams = centerGlassParams()
        root.addView(glassView)
        return root
    }

    /**
     * 场景 7：玻璃文字（原型，尚未进库）—— 输入什么字，那几个字就是玻璃。
     *
     * 走的是 [GlassTextPrototypeView]：文字轮廓烘成距离场喂进透镜着色器，
     * 于是折射/色散/高光落在笔画上，而不是一个矩形容器上。
     * 背景用桌面壁纸 + 图标网格：折射在平滑渐变上根本看不出来。
     */
    private fun buildTextScene(): View {
        val root = FrameLayout(this)
        statsSource = null   // 本场景不含 LiquidGlassView，性能悬浮窗无数据源

        // 键盘弹起时不要压缩布局，否则居中的玻璃字会被顶到 tab 栏后面
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)

        // 背景：iOS 风格壁纸，纵向可滚动。第二张上下翻转，接缝处颜色连续
        val backdrop = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            repeat(2) { i ->
                addView(ImageView(this@ProfessionalDemoActivity).apply {
                    setImageResource(R.drawable.text_backdrop)
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    if (i == 1) scaleY = -1f
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                })
            }
        }
        root.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            setBackgroundColor(0xFF060B18.toInt())
            addView(backdrop)
        }, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        val glassText = GlassTextPrototypeView(this).apply {
            textSizePx = dpF(125)
            text = getString(R.string.text_scene_default)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER }
        }
        root.addView(glassText)

        // 输入不即时生效：连打连删会反复重烘距离场（一次数百毫秒），
        // 改成点右侧按钮或按回车才提交
        val input = EditText(this).apply {
            setText(glassText.text)
            hint = getString(R.string.text_scene_hint)
            textSize = 16f
            setTextColor(Color.WHITE)
            setHintTextColor(0x99FFFFFF.toInt())
            background = GradientDrawable().apply {
                cornerRadius = dpF(14)
                setColor(0x59000000)
                setStroke(dp(1), 0x40FFFFFF)
            }
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_DONE
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        val applyText = {
            glassText.text = input.text.toString()
            input.clearFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(input.windowToken, 0)
        }
        val applyButton = TextView(this).apply {
            text = getString(R.string.text_scene_apply)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dpF(14)
                setColor(COLOR_ACCENT)
            }
            setPadding(dp(18), dp(13), dp(18), dp(13))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(8) }
            setOnClickListener { applyText() }
        }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                applyText()
                true
            } else {
                false
            }
        }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(input)
            addView(applyButton)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP
                topMargin = systemBarTop + dp(72)
                leftMargin = dp(24)
                rightMargin = dp(24)
            }
        })
        root.addView(buildTextParamPanel(glassText))

        // 场景里只有输入框可聚焦，进场景就会自动弹键盘把效果挡住；
        // 让根容器先接住焦点，用户点了输入框才弹
        root.isFocusableInTouchMode = true
        root.requestFocus()

        return root
    }

    /**
     * 玻璃字调参面板
     *
     * 顶部那行等宽绿字是当前全部参数的汇总——调舒服了直接照着它报数即可。
     * 字号/斜面/折射三项会触发距离场重烘，视图内部有 60ms 合并，拖滑杆不会每步都算。
     */
    private fun buildTextParamPanel(glass: GlassTextPrototypeView): View {
        val density = resources.displayMetrics.density

        val summary = TextView(this).apply {
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextColor(0xFF4CFF7A.toInt())
            setPadding(0, dp(4), 0, dp(4))
        }
        var tintDark = false
        var tintAlpha = Color.alpha(glass.tint)

        fun refreshSummary() {
            summary.text = ("size=%.0fdp blur=%.0f bevel=%.2f refr=%.2f disp=%.2f\n" +
                "spec=%.2f shadow=%.2f sat=%.0f tint=%s@%d").format(
                glass.textSizePx / density,
                glass.blurRadius, glass.bevelFactor, glass.refractFactor, glass.dispersion,
                glass.specStrength, glass.innerShadow, glass.saturation,
                if (tintDark) "#000" else "#fff", tintAlpha
            )
        }

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panelSlider(body, getString(R.string.param_size), 24, 160, (glass.textSizePx / density).toInt(), { "$it" }) {
            glass.textSizePx = dpF(it); refreshSummary()
        }
        panelSlider(body, getString(R.string.param_blur), 0, 60, glass.blurRadius.toInt(), { "$it" }) {
            glass.blurRadius = it.toFloat(); refreshSummary()
        }
        panelSlider(body, getString(R.string.param_bevel), 2, 30, (glass.bevelFactor * 100).toInt(), { "%.2f".format(it / 100f) }) {
            glass.bevelFactor = it / 100f; refreshSummary()
        }
        panelSlider(body, getString(R.string.param_refract), 2, 70, (glass.refractFactor * 100).toInt(), { "%.2f".format(it / 100f) }) {
            glass.refractFactor = it / 100f; refreshSummary()
        }
        panelSlider(body, getString(R.string.param_dispersion), 0, 60, (glass.dispersion * 100).toInt(), { "%.2f".format(it / 100f) }) {
            glass.dispersion = it / 100f; refreshSummary()
        }
        panelSlider(body, getString(R.string.param_spec), 0, 400, (glass.specStrength * 100).toInt(), { "%.2f".format(it / 100f) }) {
            glass.specStrength = it / 100f; refreshSummary()
        }
        panelSlider(body, getString(R.string.param_shadow), 0, 250, (glass.innerShadow * 100).toInt(), { "%.2f".format(it / 100f) }) {
            glass.innerShadow = it / 100f; refreshSummary()
        }
        panelSlider(body, getString(R.string.param_saturation), 50, 220, glass.saturation.toInt(), { "$it%" }) {
            glass.saturation = it.toFloat(); refreshSummary()
        }
        panelSlider(body, getString(R.string.param_tint), 0, 120, tintAlpha, { "$it" }) {
            tintAlpha = it
            val c = if (tintDark) 0 else 255
            glass.tint = Color.argb(tintAlpha, c, c, c)
            refreshSummary()
        }
        body.addView(TextView(this).apply {
            text = getString(R.string.param_tint_light)
            textSize = 12f
            setTextColor(0xFF4CA6FF.toInt())
            setPadding(0, dp(10), 0, dp(6))
            setOnClickListener {
                tintDark = !tintDark
                text = getString(if (tintDark) R.string.param_tint_dark else R.string.param_tint_light)
                val c = if (tintDark) 0 else 255
                glass.tint = Color.argb(tintAlpha, c, c, c)
                refreshSummary()
            }
        })

        // 滑杆多，装进固定高度的滚动区，免得面板顶满整屏
        val bodyScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(body)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(230)
            )
        }

        val header = TextView(this).apply {
            text = "▾ " + getString(R.string.text_param_title)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(2))
            setOnClickListener {
                val show = bodyScroll.visibility != View.VISIBLE
                bodyScroll.visibility = if (show) View.VISIBLE else View.GONE
                text = (if (show) "▾ " else "▸ ") + getString(R.string.text_param_title)
            }
        }

        refreshSummary()

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dpF(16)
                setColor(0xE60E0E12.toInt())
            }
            setPadding(dp(14), dp(10), dp(14), dp(10))
            addView(header)
            addView(summary)
            addView(bodyScroll)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM
                leftMargin = dp(12)
                rightMargin = dp(12)
                // 让开底部的场景切换条，并且高过设置 FAB——否则 FAB 会压住汇总行右端
                bottomMargin = dp(152)
            }
        }
    }

    /** 面板里的深色滑杆：标签自带当前值 */
    private fun panelSlider(
        parent: LinearLayout,
        label: String,
        min: Int,
        max: Int,
        initial: Int,
        display: (Int) -> String,
        onChange: (Int) -> Unit
    ) {
        val clamped = initial.coerceIn(min, max)
        val tv = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFFDDDDDD.toInt())
            text = "$label   ${display(clamped)}"
            setPadding(0, dp(6), 0, 0)
        }
        val seek = SeekBar(this).apply {
            this.max = max - min
            progress = clamped - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = p + min
                    tv.text = "$label   ${display(v)}"
                    if (fromUser) onChange(v)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                override fun onStopTrackingTouch(sb: SeekBar?) = Unit
            })
        }
        parent.addView(tv)
        parent.addView(seek)
    }

    /**
     * 场景 6：列表 —— 玻璃悬浮在滚动列表上，背景由 [LiquidGlassView.backdropSource] 指定。
     *
     * 和其他场景的关键区别：玻璃套在一层全透明的宿主容器里，和列表**没有父子关系**。
     * 默认的"捕获直接父容器"在这里只会拍到透明，画面全黑；背景完全来自 backdropSource，
     * 滚动时的重绘也由该 API 自动挂的滚动监听触发。
     *
     * 文字行提供高频边界，和桌面场景的图标网格一样适合观察折射与色散。
     */
    private fun buildListScene(): View {
        val root = FrameLayout(this)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(80), 0, dp(160))
        }
        repeat(24) { i ->
            content.addView(TextView(this).apply {
                text = "ROW $i  ▍▍▍  ROW $i  ▍▍▍"
                textSize = 20f
                setTextColor(if (i % 2 == 0) Color.WHITE else 0xFFFFE066.toInt())
                setBackgroundColor(if (i % 2 == 0) 0xFF1B3A6B.toInt() else 0xFF0B1D3A.toInt())
                setPadding(dp(16), dp(16), dp(16), dp(16))
                maxLines = 1
            })
        }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            setBackgroundColor(COLOR_SCROLL_GUTTER)
            addView(content)
        }
        root.addView(scroll, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // 玻璃挂在独立的透明子树里：背景只能靠 backdropSource 拿到
        val isolatedHost = FrameLayout(this)
        glassView.layoutParams = centerGlassParams()
        glassView.backdropSource = content
        isolatedHost.addView(glassView)
        root.addView(isolatedHost, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        return root
    }

    /**
     * 场景 7：底部弹层 —— 玻璃在 [BottomSheetDialog] 里，背景取自 Activity 的内容视图。
     *
     * 这是**跨 window** 的用法。弹层自带一个独立 window，玻璃在那个 window 里的直接父容器
     * 是透明的，默认的"捕获直接父容器"只会拍到空白，所以背景必须用
     * [LiquidGlassView.backdropSource] 指到 Activity 的 content view 上。两个 window 之间的
     * 偏移由屏幕坐标算（见 GlassLensRenderer 的 getLocationOnScreen），这条路保留 GPU 管线。
     *
     * 另有两处 Material 默认行为必须关掉，否则玻璃底下不是壁纸：
     * - 弹层的窗口变暗（dim）画在 Activity 之上、弹层之下，玻璃采到的是**没变暗**的内容，
     *   折射出来会比周围亮一截，所以 dimAmount 归零
     * - design_bottom_sheet 容器默认白底，不清成透明的话玻璃背后是一层白
     */
    private fun buildSheetScene(): View {
        val root = FrameLayout(this)

        root.addView(ImageView(this).apply {
            setImageResource(R.drawable.ios_wallpaper)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // 图标网格提供高频边界，弹层滑动时的折射变化才看得出来
        root.addView(HomeScreenGridView(this), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // 两个入口：BottomSheetDialog 和 AlertDialog，都是跨 window 采背景
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(Button(this@ProfessionalDemoActivity).apply {
                text = getString(R.string.sheet_open)
                setOnClickListener { showGlassBottomSheet() }
            })
            addView(Button(this@ProfessionalDemoActivity).apply {
                text = getString(R.string.sheet_dialog_open)
                setOnClickListener { showGlassDialog() }
            })
        }, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER })

        return root
    }

    /** 玻璃弹窗：面板整个套进 LiquidGlassView，背景来自 Activity 的 content view */
    private fun showGlassDialog() {
        LiquidGlassDialogBuilder(this)
            .setTitle(R.string.sheet_dialog_title)
            .setMessage(R.string.sheet_dialog_body)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showGlassBottomSheet() {
        dismissGlassBottomSheet()

        // 关键一行：背景来源跨到 Activity 的 window。
        // 注意必须显式取 Activity 的 content view——写在 glass.apply {} 里的话
        // findViewById 会解析成 View 自己的那个，在玻璃子树里找不到，静默返回 null，
        // 背景来源退回默认的直接父容器（弹层里是透明的），画面全黑
        val activityContent = findViewById<View>(android.R.id.content)

        // 玻璃比可见高度多出 CORNER 的量并用负 margin 顶到屏幕外，
        // 这样下面两个圆角被切掉，只剩上边圆角——iOS 弹层的形状
        val visibleH = dp(300)
        val overshoot = dp(40)
        val glass = newExtraGlass(dpF(28)).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, visibleH + overshoot
            ).apply { bottomMargin = -overshoot }
            backdropSource = activityContent
        }
        glass.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(12), dp(24), overshoot + dp(24))
            // iOS 弹层顶部的拖拽指示条
            addView(View(this@ProfessionalDemoActivity).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dpF(3)
                    setColor(0x80FFFFFF.toInt())
                }
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(5)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    bottomMargin = dp(20)
                }
            })
            addView(TextView(this@ProfessionalDemoActivity).apply {
                text = getString(R.string.sheet_title)
                textSize = 20f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setShadowLayer(6f, 0f, 1f, Color.BLACK)
            })
            addView(TextView(this@ProfessionalDemoActivity).apply {
                text = getString(R.string.sheet_body)
                textSize = 13f
                setTextColor(0xFFEEEEEE.toInt())
                gravity = Gravity.CENTER
                setShadowLayer(6f, 0f, 1f, Color.BLACK)
                setPadding(0, dp(10), 0, 0)
            })
        }, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        val sheetRoot = FrameLayout(this).apply { addView(glass) }

        sheetGlass = glass
        statsSource = glass
        bottomSheetDialog = BottomSheetDialog(this, R.style.Theme_LiquidGlass_GlassBottomSheet).apply {
            setContentView(sheetRoot)

            // 变暗层夹在 Activity 和弹层之间，玻璃采不到它，留着就会亮暗不接
            window?.setDimAmount(0f)
            // 弹层的黑底来自这几层，只清 design_bottom_sheet 不够：
            // window 自己的 windowBackground、外层 container、CoordinatorLayout 都要清
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            // 导航栏的深色底衬（edge-to-edge 由主题开，见 Theme.LiquidGlass.GlassBottomSheet）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window?.isNavigationBarContrastEnforced = false
            }
            findViewById<View>(com.google.android.material.R.id.container)
                ?.setBackgroundColor(Color.TRANSPARENT)
            findViewById<View>(com.google.android.material.R.id.coordinator)
                ?.setBackgroundColor(Color.TRANSPARENT)
            val sheet = findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.setBackgroundColor(Color.TRANSPARENT)
            sheet?.elevation = 0f

            // 默认的进出场是 **window 动画**：SurfaceFlinger 直接平移整个 window 的 surface，
            // window 内的 view 树全程不重绘，getLocationOnScreen 不变，玻璃只能按动画前的
            // 偏移采样——看起来就是"最后一帧被拖着走"，没有动态折射。
            // 解法：关掉 window 动画，把位移放进 view 树里自己做，并逐帧 invalidate 玻璃，
            // 强制重录 display list、重算跨 window 偏移。
            window?.setWindowAnimations(0)

            // 拖拽/收起同理：BottomSheetBehavior 用 offsetTopAndBottom 挪容器，
            // 不会重录子视图的 display list，必须在 onSlide 里手动 invalidate
            behavior.skipCollapsed = true
            behavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
                override fun onStateChanged(bottomSheet: View, newState: Int) = glass.invalidate()
                override fun onSlide(bottomSheet: View, slideOffset: Float) = glass.invalidate()
            })
            // 收起走 behavior 的动画（会触发 onSlide），而不是 window 动画
            setDismissWithAnimation(true)

            setOnShowListener {
                if (sheet == null) return@setOnShowListener
                // 首帧还没布局时 height 为 0，先按 alpha 藏住，post 到布局后再起手
                sheet.alpha = 0f
                sheet.post {
                    sheet.translationY = sheet.height.toFloat()
                    sheet.alpha = 1f
                    sheet.animate()
                        .translationY(0f)
                        .setDuration(340)
                        .setInterpolator(DecelerateInterpolator(1.8f))
                        .setUpdateListener { glass.invalidate() }
                        .start()
                }
            }
            show()
        }
    }

    private fun dismissGlassBottomSheet() {
        bottomSheetDialog?.dismiss()
        bottomSheetDialog = null
        sheetGlass?.let { extraGlassViews.remove(it) }
        sheetGlass = null
    }

    /**
     * 场景 5：iOS 桌面 —— 壁纸 + 4×6 图标网格 + 可拖动的玻璃药丸。
     *
     * 这是 README 首屏用的构图，也是调参时最该用的场景：图标网格提供高频边界，
     * 折射的边缘压缩环和色散彩边才看得出来。在渐变背景的场景里调 [LiquidGlassView.refractionHeight]
     * 或 [LiquidGlassView.dispersionStrength] 基本看不出差别。
     */
    private fun buildHomeScene(): View {
        val root = FrameLayout(this)

        root.addView(ImageView(this).apply {
            setImageResource(R.drawable.ios_wallpaper)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        root.addView(HomeScreenGridView(this), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        glassView.layoutParams = FrameLayout.LayoutParams(dp(300), dp(112)).apply {
            gravity = Gravity.CENTER
        }
        glassView.setOnTouchListener(object : View.OnTouchListener {
            private var dx = 0f
            private var dy = 0f
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        dx = v.translationX - e.rawX
                        dy = v.translationY - e.rawY
                    }
                    MotionEvent.ACTION_MOVE -> {
                        v.translationX = e.rawX + dx
                        v.translationY = e.rawY + dy
                    }
                }
                return false  // 继续交给 LiquidGlassView 自己的按压形变逻辑
            }
        })
        root.addView(glassView)
        return root
    }

    /** 场景 4：液态融合（拖动圆形玻璃靠近胶囊 dock，边缘 smin 黏连合并；API 33+） */
    private fun buildMergeScene(): View {
        val root = FrameLayout(this)
        root.addView(createColorScroll())

        val panelW = dp(320)
        val panelH = dp(340)
        val mergeGlass = newExtraGlass(999f).apply {
            layoutParams = FrameLayout.LayoutParams(panelW, panelH).apply {
                gravity = Gravity.CENTER
            }
            enableDynamicBackground = true
        }
        statsSource = mergeGlass

        // 主形状 = 底部胶囊 dock；副形状 = 可拖动的圆形玻璃
        val w = panelW.toFloat()
        val h = panelH.toFloat()
        val dockH = dpF(72)
        val dockHalfW = dpF(120)
        mergeGlass.setPrimaryShape(
            android.graphics.RectF(w / 2f - dockHalfW, h - dockH - dpF(16), w / 2f + dockHalfW, h - dpF(16)),
            dockH / 2f
        )

        val bubbleR = dpF(44)
        var bx = w / 2f
        var by = h * 0.30f
        fun applyBubble() {
            mergeGlass.setSecondaryShape(
                android.graphics.RectF(bx - bubbleR, by - bubbleR, bx + bubbleR, by + bubbleR),
                bubbleR,
                dpF(40)
            )
        }
        applyBubble()

        // 拖动气泡（listener 优先于内部 onTouchEvent，融合场景不需要弹性缩放）
        mergeGlass.setOnTouchListener { v, e ->
            when (e.action) {
                android.view.MotionEvent.ACTION_DOWN,
                android.view.MotionEvent.ACTION_MOVE -> {
                    bx = e.x.coerceIn(bubbleR, w - bubbleR)
                    by = e.y.coerceIn(bubbleR, h - bubbleR)
                    applyBubble()
                }
                android.view.MotionEvent.ACTION_UP -> v.performClick()
            }
            true
        }
        root.addView(mergeGlass)

        // 提示文字
        root.addView(TextView(this).apply {
            text = getString(R.string.merge_hint)
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setShadowLayer(6f, 0f, 1f, Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = systemBarTop + dp(58)  // 让开状态栏 + 右上角性能悬浮窗
            }
            setPadding(dp(24), 0, dp(24), 0)
        })
        return root
    }

    private fun createColorScroll(): ScrollView {
        val scroll = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            isVerticalScrollBarEnabled = false
            // content 上下留白是给顶部渐进模糊留的滚入空间；不铺底色的话
            // 这两条会直接露出窗口背景，全屏后就是屏幕顶端一条白带
            setBackgroundColor(COLOR_SCROLL_GUTTER)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(100), 0, dp(100))
        }

        val blocks = listOf(
            Triple(0xFFFF6B6B.toInt(), 0xFFC0392B.toInt(), "🌹 Red"),
            Triple(0xFF4ECDC4.toInt(), 0xFF16A085.toInt(), "🌊 Cyan"),
            Triple(0xFF45B7D1.toInt(), 0xFF2C3E90.toInt(), "💙 Blue"),
            Triple(0xFFFFA07A.toInt(), 0xFFE67E22.toInt(), "🍊 Orange"),
            Triple(0xFF98D8C8.toInt(), 0xFF27AE60.toInt(), "🌿 Green"),
            Triple(0xFFF7DC6F.toInt(), 0xFFF39C12.toInt(), "⭐ Yellow"),
            Triple(0xFFBB8FCE.toInt(), 0xFF8E44AD.toInt(), "💜 Purple"),
            Triple(0xFF85C1E2.toInt(), 0xFF2980B9.toInt(), "☁️ Sky")
        )
        blocks.forEach { (from, to, label) ->
            val block = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(160))
                background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR, intArrayOf(from, to)
                )
            }
            block.addView(TextView(this).apply {
                text = label
                textSize = 24f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setShadowLayer(4f, 2f, 2f, Color.BLACK)
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            })
            content.addView(block)
        }
        scroll.addView(content)
        return scroll
    }

    /** 场景 2：图片背景（用户图片或程序生成的风景图）+ 玻璃按钮 */
    private fun buildImageScene(): View {
        val root = FrameLayout(this)

        val scroll = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            isVerticalScrollBarEnabled = false
        }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val bitmap = customBackgroundBitmap ?: getOrCreateScenicBitmap()
        // 平铺 2 份以支持滚动
        repeat(2) {
            content.addView(ImageView(this).apply {
                setImageBitmap(bitmap)
                scaleType = ImageView.ScaleType.FIT_XY
                adjustViewBounds = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            })
        }
        scroll.addView(content)
        root.addView(scroll)

        glassView.layoutParams = centerGlassParams()
        root.addView(glassView)
        return root
    }

    private fun getOrCreateScenicBitmap(): Bitmap {
        scenicBitmap?.let { return it }
        val w = resources.displayMetrics.widthPixels.coerceAtLeast(320)
        val h = (resources.displayMetrics.heightPixels * 1.2f).toInt().coerceAtLeast(480)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // 落日天空
        paint.shader = LinearGradient(
            0f, 0f, 0f, h * 0.72f,
            intArrayOf(0xFF2C3E70.toInt(), 0xFFB0527A.toInt(), 0xFFFF9966.toInt(), 0xFFFFD194.toInt()),
            floatArrayOf(0f, 0.45f, 0.8f, 1f), Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, w.toFloat(), h * 0.72f, paint)
        paint.shader = null

        // 太阳
        paint.shader = RadialGradient(
            w * 0.5f, h * 0.62f, w * 0.22f,
            intArrayOf(0xFFFFF3B0.toInt(), 0x00FFF3B0), null, Shader.TileMode.CLAMP
        )
        c.drawCircle(w * 0.5f, h * 0.62f, w * 0.22f, paint)
        paint.shader = null
        paint.color = 0xFFFFE29A.toInt()
        c.drawCircle(w * 0.5f, h * 0.62f, w * 0.09f, paint)

        // 远山与近山
        paint.color = 0xFF4A3B5E.toInt()
        c.drawPath(mountainPath(w.toFloat(), h * 0.72f, h * 0.16f, 3), paint)
        paint.color = 0xFF31284A.toInt()
        c.drawPath(mountainPath(w.toFloat(), h * 0.72f, h * 0.10f, 4), paint)

        // 水面
        paint.shader = LinearGradient(
            0f, h * 0.72f, 0f, h.toFloat(),
            intArrayOf(0xFFE8956D.toInt(), 0xFF3C2E5A.toInt()), null, Shader.TileMode.CLAMP
        )
        c.drawRect(0f, h * 0.72f, w.toFloat(), h.toFloat(), paint)
        paint.shader = null

        // 水面反光条
        paint.color = 0x66FFE9B0
        var y = h * 0.74f
        var half = w * 0.16f
        while (y < h * 0.95f) {
            c.drawRect(w * 0.5f - half, y, w * 0.5f + half, y + dpF(2), paint)
            y += dpF(10)
            half *= 0.92f
        }

        scenicBitmap = bmp
        return bmp
    }

    private fun mountainPath(w: Float, baseY: Float, peakH: Float, peaks: Int): Path {
        val path = Path()
        path.moveTo(0f, baseY)
        val step = w / peaks
        for (i in 0 until peaks) {
            path.lineTo(step * i + step * 0.5f, baseY - peakH * (0.7f + 0.3f * ((i * 37) % 10) / 10f))
            path.lineTo(step * (i + 1), baseY)
        }
        path.close()
        return path
    }

    /** 场景 3：动画渐变光斑背景 + 玻璃按钮（考验动态背景实时性） */
    private fun buildAnimatedScene(): View {
        val root = FrameLayout(this)
        root.addView(AnimatedBlobView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        })
        glassView.layoutParams = centerGlassParams()
        root.addView(glassView)
        return root
    }

    /** 动画光斑背景视图 */
    private class AnimatedBlobView(context: Context) : View(context) {
        private data class Blob(val color: Int, val phase: Float, val speed: Float, val rx: Float, val ry: Float, val radius: Float)

        private val blobs = listOf(
            Blob(0xFFFF5E7E.toInt(), 0.0f, 1.0f, 0.32f, 0.26f, 0.34f),
            Blob(0xFF56CCF2.toInt(), 1.7f, 0.8f, 0.36f, 0.30f, 0.40f),
            Blob(0xFFB465DA.toInt(), 3.1f, 1.2f, 0.30f, 0.34f, 0.32f),
            Blob(0xFFF2C94C.toInt(), 4.6f, 0.6f, 0.38f, 0.24f, 0.28f),
            Blob(0xFF6FCF97.toInt(), 5.9f, 0.9f, 0.28f, 0.32f, 0.30f)
        )
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var t = 0f
        private val animator = ValueAnimator.ofFloat(0f, (2 * Math.PI).toFloat()).apply {
            duration = 12000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                t = it.animatedValue as Float
                invalidate()
            }
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            animator.start()
        }

        override fun onDetachedFromWindow() {
            animator.cancel()
            super.onDetachedFromWindow()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(0xFF14141E.toInt())
            val w = width.toFloat()
            val h = height.toFloat()
            blobs.forEach { b ->
                val cx = w * 0.5f + w * b.rx * cos(t * b.speed + b.phase)
                val cy = h * 0.5f + h * b.ry * sin(t * b.speed * 1.3f + b.phase)
                val r = w * b.radius
                paint.shader = RadialGradient(
                    cx, cy, r,
                    intArrayOf(b.color, b.color and 0x00FFFFFF), null, Shader.TileMode.CLAMP
                )
                canvas.drawCircle(cx, cy, r, paint)
            }
            paint.shader = null
        }
    }

    /** 场景 4：多玻璃组件展示（导航栏 / 卡片 / 圆形按钮 / 主按钮） */
    private fun buildShowcaseScene(): View {
        val root = FrameLayout(this)
        root.addView(createColorScroll())

        // 顶部玻璃导航栏
        root.addView(newExtraGlass(dpF(24)).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(56)
            ).apply {
                gravity = Gravity.TOP
                setMargins(dp(16), systemBarTop + dp(58), dp(16), 0)  // 让开状态栏 + 性能悬浮窗
            }
            addView(TextView(this@ProfessionalDemoActivity).apply {
                text = getString(R.string.showcase_navbar)
                textSize = 16f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setShadowLayer(6f, 0f, 1f, Color.BLACK)
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            })
        })

        // 中央主按钮（复用主 glassView，面板参数直接生效）
        glassView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
            topMargin = -dp(60)
        }
        root.addView(glassView)

        // 信息卡片
        root.addView(newExtraGlass(dpF(20)).apply {
            layoutParams = FrameLayout.LayoutParams(dp(300), dp(110)).apply {
                gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
                bottomMargin = dp(150)
            }
            addView(TextView(this@ProfessionalDemoActivity).apply {
                text = getString(R.string.showcase_card)
                textSize = 15f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setShadowLayer(6f, 0f, 1f, Color.BLACK)
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            })
        })

        // 圆形玻璃按钮
        root.addView(newExtraGlass(999f).apply {
            layoutParams = FrameLayout.LayoutParams(dp(72), dp(72)).apply {
                gravity = Gravity.START or Gravity.BOTTOM
                setMargins(dp(24), 0, 0, dp(96))
            }
            addView(TextView(this@ProfessionalDemoActivity).apply {
                text = "🎵"
                textSize = 26f
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            })
        })

        return root
    }

    /** 场景 10：现成小部件（LiquidGlassButton / LiquidGlassTabBar / LiquidGlassFab，全部库默认参数开箱展示） */
    private fun buildWidgetsScene(): View {
        val root = FrameLayout(this)
        root.addView(createColorScroll())

        // 顶部玻璃标签条：iOS 26 风格（图标+小字，玻璃滴指示可点可拖）
        val tabTitles = listOf(
            getString(R.string.widgets_tab_1),
            getString(R.string.widgets_tab_2),
            getString(R.string.widgets_tab_3)
        )
        val tabIcons = listOf(
            R.drawable.ic_tab_home,
            R.drawable.ic_tab_explore,
            R.drawable.ic_tab_library
        )
        val tabBar = LiquidGlassTabBar(this).apply {
            enableDynamicBackground = true
            setTabs(tabTitles.zip(tabIcons) { title, iconRes ->
                LiquidGlassTabBar.TabItem(title, ContextCompat.getDrawable(this@ProfessionalDemoActivity, iconRes))
            })
            onTabSelected = { index ->
                Toast.makeText(
                    this@ProfessionalDemoActivity,
                    getString(R.string.widgets_toast_tab, tabTitles[index]),
                    Toast.LENGTH_SHORT
                ).show()
            }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP
                setMargins(dp(20), systemBarTop + dp(58), dp(20), 0)  // 让开状态栏 + 性能悬浮窗
            }
        }
        root.addView(tabBar)

        // 中央按钮：Regular / Clear 两种材质对比。
        // 注意必须直接挂在 root 下——玻璃捕获直接父容器，包一层透明
        // LinearLayout 的话捕获到的就是空内容，按钮会渲染成黑底
        val regularButton = LiquidGlassButton(this).apply {
            enableDynamicBackground = true
            text = getString(R.string.widgets_button_regular)
            setOnClickListener {
                Toast.makeText(
                    this@ProfessionalDemoActivity,
                    getString(R.string.widgets_toast_button, text),
                    Toast.LENGTH_SHORT
                ).show()
            }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
                topMargin = -dp(44)
            }
        }
        root.addView(regularButton)
        root.addView(LiquidGlassButton(this).apply {
            enableDynamicBackground = true
            material = GlassMaterial.CLEAR
            text = getString(R.string.widgets_button_clear)
            setOnClickListener {
                Toast.makeText(
                    this@ProfessionalDemoActivity,
                    getString(R.string.widgets_toast_button, text),
                    Toast.LENGTH_SHORT
                ).show()
            }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
                topMargin = dp(44)
            }
        })

        // 左下圆形玻璃 FAB
        root.addView(LiquidGlassFab(this).apply {
            enableDynamicBackground = true
            setIconResource(android.R.drawable.ic_input_add)
            setOnClickListener {
                Toast.makeText(
                    this@ProfessionalDemoActivity,
                    getString(R.string.widgets_toast_fab),
                    Toast.LENGTH_SHORT
                ).show()
            }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.START or Gravity.BOTTOM
                setMargins(dp(24), 0, 0, dp(96))
            }
        })

        // 本场景不含主 glassView，性能悬浮窗读常规材质按钮的数据
        statsSource = regularButton
        return root
    }

    /** 创建随主 glassView 参数同步的附属玻璃组件 */
    private fun newExtraGlass(cornerRadiusPx: Float): LiquidGlassView {
        val view = LiquidGlassView(this).apply {
            enableDynamicBackground = true
            cornerRadius = cornerRadiusPx
        }
        syncGlassParams(view)
        view.cornerRadius = cornerRadiusPx  // 圆角保持各自形状
        extraGlassViews += view
        return view
    }

    private fun syncGlassParams(target: LiquidGlassView) {
        val src = glassView
        target.useHardwareBlurWhenPossible = src.useHardwareBlurWhenPossible
        target.debugApiLevelCap = src.debugApiLevelCap
        target.useShaderPipeline = src.useShaderPipeline
        target.material = src.material
        target.bevelWidth = src.bevelWidth
        target.refractionHeight = src.refractionHeight
        target.dispersionStrength = src.dispersionStrength
        target.enableSensorHighlight = src.enableSensorHighlight
        target.enableAdaptiveTint = src.enableAdaptiveTint
        target.accessibilityMode = src.accessibilityMode
        target.enablePressEffect = src.enablePressEffect
        target.pressScale = src.pressScale
        target.elasticity = src.elasticity
        target.enableBackdropBlur = src.enableBackdropBlur
        target.blurAmount = src.blurAmount
        target.saturation = src.saturation
        target.overLight = src.overLight
        target.enableEdgeHighlight = src.enableEdgeHighlight
        target.edgeHighlightBorderWidth = src.edgeHighlightBorderWidth
        target.edgeHighlightOpacity = src.edgeHighlightOpacity
        target.enableChromaticAberration = src.enableChromaticAberration
        target.enableChromaticDispersion = src.enableChromaticDispersion
        target.aberrationIntensity = src.aberrationIntensity
        target.displacementScale = src.displacementScale
        target.displacementMode = src.displacementMode
        target.aberrationRedOffset = src.aberrationRedOffset
        target.aberrationGreenOffset = src.aberrationGreenOffset
        target.aberrationBlueOffset = src.aberrationBlueOffset
        target.blurMethod = src.blurMethod
        target.chromaticAberrationMode = src.chromaticAberrationMode
        target.globalDownsampleFactor = src.globalDownsampleFactor
        target.aberrationDownsample = src.aberrationDownsample
    }

    /** 面板参数应用到当前场景的全部玻璃组件 */
    private fun applyGlass(block: (LiquidGlassView) -> Unit) {
        block(glassView)
        extraGlassViews.forEach(block)
    }

    // ==================== 调试面板 ====================

    private fun createDrawer(): View {
        val drawer = LinearLayout(this).apply {
            layoutParams = DrawerLayout.LayoutParams(
                (resources.displayMetrics.widthPixels * 0.86f).toInt(),
                DrawerLayout.LayoutParams.MATCH_PARENT
            ).apply { gravity = GravityCompat.END }
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_BG)
            setPadding(dp(16), dp(44), dp(16), dp(12))
        }

        drawer.addView(TextView(this).apply {
            text = getString(R.string.debug_panel_title)
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_TEXT)
            setPadding(dp(6), 0, 0, dp(4))
        })

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            isVerticalScrollBarEnabled = false
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(24))
        }
        buildPanel(content)
        scroll.addView(content)
        drawer.addView(scroll)
        return drawer
    }

    private fun buildPanel(root: LinearLayout) {
        // ---------- 性能 ----------
        val perfCard = addCard(root, getString(R.string.section_performance))
        addSwitchRow(perfCard, getString(R.string.switch_performance_overlay), true) { checked ->
            tvPerformanceOverlay.visibility = if (checked) View.VISIBLE else View.GONE
            isMonitoring = checked
        }
        tvDebugInfo = TextView(this).apply {
            textSize = 11f
            setTextColor(COLOR_TEXT)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(4), 0, dp(4))
            text = getString(R.string.debug_info_waiting)
        }
        perfCard.addView(tvDebugInfo)

        // ---------- 渲染路径 ----------
        val pathCard = addCard(root, getString(R.string.section_render_path))
        val initialPath = when {
            !glassView.useHardwareBlurWhenPossible -> 2
            !glassView.useShaderPipeline -> 1
            else -> 0
        }
        addSegmented(
            pathCard,
            listOf(
                getString(R.string.render_lens),
                getString(R.string.render_classic_gpu),
                getString(R.string.render_force_cpu)
            ),
            initialPath
        ) { index ->
            applyGlass {
                it.useHardwareBlurWhenPossible = index != 2
                it.useShaderPipeline = index == 0
            }
            lensGroup.visibility = if (index == 0) View.VISIBLE else View.GONE
            cpuOptionsGroup.visibility = if (index == 2) View.VISIBLE else View.GONE
        }
        addNote(pathCard, getString(R.string.gpu_render_desc))

        // 模拟 API 级别：钳制库的管线分层，在高版本设备上预览低版本效果
        addLabel(pathCard, getString(R.string.simulate_api_label))
        addSegmented(
            pathCard,
            listOf(getString(R.string.api_cap_device), "33–35", "31–32", "≤ 30"),
            0
        ) { index ->
            val cap = when (index) {
                1 -> 35
                2 -> 32
                3 -> 30
                else -> Int.MAX_VALUE
            }
            applyGlass { it.debugApiLevelCap = cap }
        }
        addNote(pathCard, getString(R.string.simulate_api_desc))

        // Liquid Glass 2.0 透镜选项（仅透镜路径时显示）
        lensGroup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (initialPath == 0) View.VISIBLE else View.GONE
        }
        pathCard.addView(lensGroup)
        buildLensOptions(lensGroup)

        // CPU 算法选项（仅强制 CPU 时显示）
        cpuOptionsGroup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (initialPath == 2) View.VISIBLE else View.GONE
        }
        pathCard.addView(cpuOptionsGroup)
        buildCpuOptions(cpuOptionsGroup)

        // ---------- 玻璃参数（GPU + CPU 通用） ----------
        val glassCard = addCard(root, getString(R.string.section_glass_params))

        addSwitchRow(glassCard, getString(R.string.switch_enable_blur), glassView.enableBackdropBlur) { checked ->
            applyGlass { it.enableBackdropBlur = checked }
        }
        addSlider(glassCard, 100, (glassView.blurAmount * 100).toInt(),
            { getString(R.string.blur_amount, it / 100f) }) { p ->
            applyGlass { it.blurAmount = p / 100f }
        }
        addSlider(glassCard, 100, (glassView.saturation - 100).toInt(),
            { getString(R.string.saturation_value, it + 100) }) { p ->
            applyGlass { it.saturation = (p + 100).toFloat() }
        }
        addSlider(glassCard, 999, glassView.cornerRadius.toInt(),
            { getString(R.string.corner_radius_value, it.toFloat()) }) { p ->
            // 圆角只作用于主组件，附属组件保持各自形状
            glassView.cornerRadius = p.toFloat()
        }
        addSwitchRow(glassCard, getString(R.string.switch_over_light), glassView.overLight) { checked ->
            applyGlass { it.overLight = checked }
        }
        addSwitchRow(glassCard, getString(R.string.switch_enable_edge_highlight), glassView.enableEdgeHighlight) { checked ->
            applyGlass { it.enableEdgeHighlight = checked }
        }
        addSlider(glassCard, 100, ((glassView.edgeHighlightBorderWidth - 0.5f) / 4.5f * 100).toInt(),
            { getString(R.string.border_width, 0.5f + it / 100f * 4.5f) }) { p ->
            applyGlass { it.edgeHighlightBorderWidth = 0.5f + p / 100f * 4.5f }
        }
        addSlider(glassCard, 100, glassView.edgeHighlightOpacity.toInt(),
            { getString(R.string.highlight_opacity, it.toFloat()) }) { p ->
            applyGlass { it.edgeHighlightOpacity = p.toFloat() }
        }

        // ---------- 交互 · 点击效果 ----------
        val interactionCard = addCard(root, getString(R.string.section_interaction))
        addSwitchRow(interactionCard, getString(R.string.switch_press_effect), glassView.enablePressEffect) { checked ->
            applyGlass { it.enablePressEffect = checked }
        }
        // 按压缩放 1.00 - 0.80（进度 0 = 不缩放）
        addSlider(interactionCard, 20, ((1f - glassView.pressScale) * 100).toInt(),
            { getString(R.string.press_scale_value, 1f - it / 100f) }) { p ->
            applyGlass { it.pressScale = 1f - p / 100f }
        }
        // 弹性系数 0 - 0.50（拖拽时的拉伸强度）
        addSlider(interactionCard, 50, (glassView.elasticity * 100).toInt(),
            { getString(R.string.elasticity_value, it / 100f) }) { p ->
            applyGlass { it.elasticity = p / 100f }
        }

        // ---------- 色彩效果 ----------
        val colorCard = addCard(root, getString(R.string.section_color_effect))
        val initialEffect = when {
            glassView.enableChromaticDispersion -> 2
            glassView.enableChromaticAberration -> 1
            else -> 0
        }
        addSegmented(
            colorCard,
            listOf(
                getString(R.string.effect_none),
                getString(R.string.algorithm_aberration),
                getString(R.string.algorithm_dispersion)
            ),
            initialEffect
        ) { index ->
            when (index) {
                0 -> applyGlass {
                    it.enableChromaticDispersion = false
                    it.enableChromaticAberration = false
                }
                1 -> applyGlass {
                    it.enableChromaticDispersion = false
                    it.enableChromaticAberration = true
                }
                2 -> applyGlass {
                    it.enableChromaticAberration = false
                    it.enableChromaticDispersion = true
                }
            }
            aberrationGroup.visibility = if (index == 1) View.VISIBLE else View.GONE
            dispersionGroup.visibility = if (index == 2) View.VISIBLE else View.GONE
        }

        // 色差参数
        aberrationGroup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (initialEffect == 1) View.VISIBLE else View.GONE
        }
        colorCard.addView(aberrationGroup)
        buildAberrationOptions(aberrationGroup)

        // 色散参数
        dispersionGroup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (initialEffect == 2) View.VISIBLE else View.GONE
        }
        colorCard.addView(dispersionGroup)
        buildDispersionOptions(dispersionGroup)

        // ---------- 背景与语言 ----------
        val miscCard = addCard(root, getString(R.string.section_background_language))
        addButton(miscCard, getString(R.string.button_change_background)) {
            checkPermissionAndOpenPicker()
        }
        addButton(miscCard, getLanguageSwitchButtonText()) {
            val prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val currentLang = prefs.getString(KEY_LANGUAGE, LANG_ENGLISH) ?: LANG_ENGLISH
            switchLanguage(if (currentLang == LANG_CHINESE) LANG_ENGLISH else LANG_CHINESE)
        }
    }

    /** Liquid Glass 2.0 透镜管线选项 */
    private fun buildLensOptions(group: LinearLayout) {
        addNote(group, getString(R.string.lens_note))

        // 材质：Regular / Clear
        addLabel(group, getString(R.string.lens_material))
        addSegmented(
            group,
            listOf(getString(R.string.material_regular), getString(R.string.material_clear)),
            if (glassView.material == GlassMaterial.CLEAR) 1 else 0
        ) { index ->
            applyGlass { it.material = if (index == 1) GlassMaterial.CLEAR else GlassMaterial.REGULAR }
        }

        addSwitchRow(group, getString(R.string.switch_sensor_highlight), glassView.enableSensorHighlight) { checked ->
            applyGlass { it.enableSensorHighlight = checked }
        }
        addSwitchRow(group, getString(R.string.switch_adaptive_tint), glassView.enableAdaptiveTint) { checked ->
            applyGlass { it.enableAdaptiveTint = checked }
        }

        // 斜面宽度 2-120 px
        addSlider(group, 118, (glassView.bevelWidth - 2f).toInt(),
            { getString(R.string.lens_bevel, it + 2f) }) { p ->
            applyGlass { it.bevelWidth = p + 2f }
        }
        // 折射强度 0-200 px（属性上限 300，采样有安全钳制不会越界）
        addSlider(group, 200, glassView.refractionHeight.toInt(),
            { getString(R.string.lens_refraction, it.toFloat()) }) { p ->
            applyGlass { it.refractionHeight = p.toFloat() }
        }
        // 色散强度 0-1
        addSlider(group, 100, (glassView.dispersionStrength * 100f).toInt(),
            { getString(R.string.lens_dispersion, it / 100f) }) { p ->
            applyGlass { it.dispersionStrength = p / 100f }
        }

        // 无障碍不透明降级（演示 Reduce Transparency 行为）
        addSwitchRow(
            group,
            getString(R.string.switch_a11y_opaque),
            glassView.accessibilityMode == GlassAccessibilityMode.FORCE_OPAQUE
        ) { checked ->
            applyGlass {
                it.accessibilityMode =
                    if (checked) GlassAccessibilityMode.FORCE_OPAQUE else GlassAccessibilityMode.AUTO
            }
        }
    }

    private fun buildCpuOptions(group: LinearLayout) {
        addNote(group, getString(R.string.section_cpu_options))

        // 模糊算法
        val methods = listOf(
            getString(R.string.blur_method_smart) to BlurMethod.SMART,
            getString(R.string.blur_method_box) to BlurMethod.BOX_BLUR,
            getString(R.string.blur_method_box_cpp) to BlurMethod.BOX_BLUR_CPP,
            getString(R.string.blur_method_iir) to BlurMethod.IIR_GAUSSIAN,
            getString(R.string.blur_method_neon) to BlurMethod.IIR_GAUSSIAN_NEON,
            getString(R.string.blur_method_box3) to BlurMethod.BOX3,
            getString(R.string.blur_method_downsample) to BlurMethod.DOWNSAMPLE
        )
        addLabel(group, getString(R.string.section_blur_method))
        val spinner = Spinner(this)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, methods.map { it.first })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.setSelection(methods.indexOfFirst { it.second == glassView.blurMethod }.coerceAtLeast(0))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                applyGlass { it.blurMethod = methods[position].second }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        group.addView(spinner)

        // 色差实现
        addLabel(group, getString(R.string.section_aberration_method))
        val modes = listOf(
            ChromaticAberrationEffect.PerformanceMode.AUTO,
            ChromaticAberrationEffect.PerformanceMode.CPP,
            ChromaticAberrationEffect.PerformanceMode.KOTLIN
        )
        addSegmented(
            group,
            listOf(getString(R.string.impl_auto), "C++", "Kotlin"),
            modes.indexOf(glassView.chromaticAberrationMode).coerceAtLeast(0)
        ) { index ->
            applyGlass { it.chromaticAberrationMode = modes[index] }
        }

        addSwitchRow(group, getString(R.string.switch_bilinear_interpolation), glassView.aberrationUseBilinearInterpolation) { checked ->
            applyGlass { it.aberrationUseBilinearInterpolation = checked }
        }
        addSwitchRow(group, getString(R.string.switch_high_quality), glassView.highQualityBlur) { checked ->
            applyGlass { it.highQualityBlur = checked }
        }
        addSwitchRow(group, getString(R.string.switch_enable_optimized_capture), glassView.enableOptimizedCapture) { checked ->
            applyGlass { it.enableOptimizedCapture = checked }
        }

        // 全局下采样 0.25 - 1.0
        addSlider(group, 100, ((glassView.globalDownsampleFactor - 0.25f) / 0.75f * 100).toInt(),
            { getString(R.string.global_downsample_value, 0.25f + it / 100f * 0.75f) }) { p ->
            applyGlass { it.globalDownsampleFactor = 0.25f + p / 100f * 0.75f }
        }
        // 色差下采样 0.25 - 1.0
        addSlider(group, 100, ((glassView.aberrationDownsample - 0.25f) / 0.75f * 100).toInt(),
            { getString(R.string.aberration_downsample_value, 0.25f + it / 100f * 0.75f) }) { p ->
            applyGlass { it.aberrationDownsample = 0.25f + p / 100f * 0.75f }
        }
    }

    private fun buildAberrationOptions(group: LinearLayout) {
        // 强度 0 - 10
        addSlider(group, 100, (glassView.aberrationIntensity * 10).toInt(),
            { getString(R.string.aberration_value, it / 10f) }) { p ->
            applyGlass { it.aberrationIntensity = p / 10f }
        }
        // 位移强度 0 - 200
        addSlider(group, 200, glassView.displacementScale.toInt(),
            { getString(R.string.displacement_scale_value, it.toFloat()) }) { p ->
            applyGlass { it.displacementScale = p.toFloat() }
        }
        // 位移模式
        addLabel(group, getString(R.string.displacement_mode))
        val dispModes = listOf(DisplacementMode.STANDARD, DisplacementMode.POLAR, DisplacementMode.PROMINENT)
        addSegmented(
            group,
            listOf(
                getString(R.string.disp_mode_standard),
                getString(R.string.disp_mode_polar),
                getString(R.string.disp_mode_prominent)
            ),
            dispModes.indexOf(glassView.displacementMode).coerceAtLeast(0)
        ) { index ->
            applyGlass { it.displacementMode = dispModes[index] }
        }

        // 通道偏移
        addLabel(group, getString(R.string.channel_offset_advanced))
        addSlider(group, 200, (glassView.aberrationRedOffset * 500 + 100).toInt(),
            { getString(R.string.red_offset, (it - 100) / 500f) }) { p ->
            applyGlass { it.aberrationRedOffset = (p - 100) / 500f }
        }
        addSlider(group, 200, (glassView.aberrationGreenOffset * 500 + 100).toInt(),
            { getString(R.string.green_offset, (it - 100) / 500f) }) { p ->
            applyGlass { it.aberrationGreenOffset = (p - 100) / 500f }
        }
        addSlider(group, 200, (glassView.aberrationBlueOffset * 500 + 100).toInt(),
            { getString(R.string.blue_offset, (it - 100) / 500f) }) { p ->
            applyGlass { it.aberrationBlueOffset = (p - 100) / 500f }
        }
    }

    private fun buildDispersionOptions(group: LinearLayout) {
        addNote(group, getString(R.string.dispersion_cpu_only_note))

        // 预设
        addLabel(group, getString(R.string.dispersion_preset))
        val presets = listOf(
            Triple(getString(R.string.preset_glass), 100f, 1.5f to 7f),
            Triple(getString(R.string.preset_diamond), 80f, 2.4f to 15f),
            Triple(getString(R.string.preset_crystal), 120f, 1.8f to 10f),
            Triple(getString(R.string.preset_rainbow), 150f, 1.3f to 20f),
            Triple(getString(R.string.preset_subtle), 200f, 1.2f to 3f)
        )

        lateinit var updateSliders: () -> Unit

        val spinner = Spinner(this)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, presets.map { it.first })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val (_, thickness, rest) = presets[position]
                val (factor, gain) = rest
                applyGlass {
                    it.dispersionThickness = thickness
                    it.dispersionFactor = factor
                    it.dispersionGain = gain
                }
                updateSliders()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        group.addView(spinner)

        // 厚度 50-200
        val (tvThickness, seekThickness) = addSlider(group, 150, (glassView.dispersionThickness - 50).toInt(),
            { getString(R.string.dispersion_thickness, it + 50f) }) { p ->
            applyGlass { it.dispersionThickness = p + 50f }
        }
        // 系数 1.0-3.0
        val (tvFactor, seekFactor) = addSlider(group, 100, ((glassView.dispersionFactor - 1f) * 50).toInt(),
            { getString(R.string.dispersion_factor, 1f + it / 50f) }) { p ->
            applyGlass { it.dispersionFactor = 1f + p / 50f }
        }
        // 增益 0-50
        val (tvGain, seekGain) = addSlider(group, 50, glassView.dispersionGain.toInt(),
            { getString(R.string.dispersion_gain, it.toFloat()) }) { p ->
            applyGlass { it.dispersionGain = p.toFloat() }
        }
        // 色散下采样 0.25-1.0
        addSlider(group, 100, ((glassView.dispersionDownsample - 0.25f) / 0.75f * 100).toInt(),
            { getString(R.string.dispersion_downscale, 0.25f + it / 100f * 0.75f) }) { p ->
            applyGlass { it.dispersionDownsample = 0.25f + p / 100f * 0.75f }
        }

        updateSliders = {
            seekThickness.progress = (glassView.dispersionThickness - 50).toInt()
            tvThickness.text = getString(R.string.dispersion_thickness, glassView.dispersionThickness)
            seekFactor.progress = ((glassView.dispersionFactor - 1f) * 50).toInt()
            tvFactor.text = getString(R.string.dispersion_factor, glassView.dispersionFactor)
            seekGain.progress = glassView.dispersionGain.toInt()
            tvGain.text = getString(R.string.dispersion_gain, glassView.dispersionGain)
        }
    }

    // ==================== 面板 UI 构件 ====================

    private fun addCard(parent: LinearLayout, title: String): LinearLayout {
        parent.addView(TextView(this).apply {
            text = title
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_TEXT_DIM)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(18)
                bottomMargin = dp(6)
                marginStart = dp(8)
            }
        })
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dpF(14)
                setColor(COLOR_CARD)
            }
            setPadding(dp(14), dp(6), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        parent.addView(card)
        return card
    }

    private fun addLabel(parent: LinearLayout, text: String) {
        parent.addView(TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(COLOR_TEXT)
            setPadding(0, dp(12), 0, dp(4))
        })
    }

    private fun addNote(parent: LinearLayout, text: String) {
        parent.addView(TextView(this).apply {
            this.text = text
            textSize = 11f
            setTextColor(COLOR_TEXT_DIM)
            setPadding(0, dp(6), 0, dp(4))
        })
    }

    private fun addButton(parent: LinearLayout, text: String, onClick: () -> Unit) {
        parent.addView(Button(this).apply {
            this.text = text
            isAllCaps = false
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        })
    }

    private fun addSwitchRow(
        parent: LinearLayout,
        label: String,
        checked: Boolean,
        onChange: (Boolean) -> Unit
    ): Switch {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        row.addView(TextView(this).apply {
            text = label
            textSize = 14f
            setTextColor(COLOR_TEXT)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val sw = Switch(this).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, c -> onChange(c) }
        }
        row.addView(sw)
        parent.addView(row)
        return sw
    }

    private fun addSlider(
        parent: LinearLayout,
        max: Int,
        initial: Int,
        format: (Int) -> String,
        onChange: (Int) -> Unit
    ): Pair<TextView, SeekBar> {
        val clamped = initial.coerceIn(0, max)
        val tv = TextView(this).apply {
            text = format(clamped)
            textSize = 13f
            setTextColor(COLOR_TEXT)
            setPadding(0, dp(8), 0, 0)
        }
        val seek = SeekBar(this).apply {
            this.max = max
            progress = clamped
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    tv.text = format(p)
                    if (fromUser) onChange(p)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        parent.addView(tv)
        parent.addView(seek)
        return tv to seek
    }

    /** 分段选择控件（iOS Segmented Control 风格） */
    private fun addSegmented(
        parent: LinearLayout,
        options: List<String>,
        selectedIndex: Int,
        onSelect: (Int) -> Unit
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
                bottomMargin = dp(4)
            }
            background = GradientDrawable().apply {
                cornerRadius = dpF(10)
                setColor(COLOR_SEG_BG)
            }
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }

        val labels = mutableListOf<TextView>()
        var current = selectedIndex.coerceIn(0, options.size - 1)

        fun render() {
            labels.forEachIndexed { i, tv ->
                if (i == current) {
                    tv.background = GradientDrawable().apply {
                        cornerRadius = dpF(8)
                        setColor(Color.WHITE)
                    }
                    tv.setTextColor(COLOR_TEXT)
                    tv.typeface = Typeface.DEFAULT_BOLD
                } else {
                    tv.background = null
                    tv.setTextColor(0xFF666666.toInt())
                    tv.typeface = Typeface.DEFAULT
                }
            }
        }

        options.forEachIndexed { i, label ->
            val tv = TextView(this).apply {
                text = label
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(0, dp(7), 0, dp(7))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    if (current != i) {
                        current = i
                        render()
                        onSelect(i)
                    }
                }
            }
            labels += tv
            row.addView(tv)
        }
        render()
        parent.addView(row)
    }

    // ==================== 性能监控 ====================

    private fun startPerformanceMonitoring() {
        val runnable = object : Runnable {
            override fun run() {
                if (isMonitoring) updatePerformanceDisplay()
                performanceHandler.postDelayed(this, 500L)
            }
        }
        performanceHandler.post(runnable)
    }

    private fun updatePerformanceDisplay() {
        val stats = (statsSource ?: glassView).lastFrameStats ?: return
        val isGpu = stats.effectName.startsWith("GPU")

        val fps = when {
            stats.drawFps > 0 -> stats.drawFps
            stats.totalMs > 0f -> (1000f / stats.totalMs).toInt()
            else -> 0
        }

        fun fmt(ms: Float) = String.format("%.2f", ms)

        val overlayText = if (isGpu) {
            "FPS: $fps\n${stats.effectName}\n${fmt(stats.totalMs)}ms"
        } else {
            val blurTag = if (stats.blurRecomputed) "" else "*"
            val effectTag = if (stats.effectRecomputed) "" else "*"
            "FPS: $fps  CPU\n" +
                "capture ${fmt(stats.captureMs)}ms\n" +
                "blur    ${fmt(stats.blurMs)}ms$blurTag\n" +
                "effect  ${fmt(stats.effectMs)}ms$effectTag\n" +
                "total   ${fmt(stats.totalMs)}ms"
        }

        val debugText = if (isGpu) {
            "${stats.effectName} · RenderEffect\n" +
                "record ${fmt(stats.totalMs)}ms · $fps FPS"
        } else {
            "CPU · ${glassView.blurMethod}\n" +
                "capture ${fmt(stats.captureMs)} | blur ${fmt(stats.blurMs)} | " +
                "effect ${fmt(stats.effectMs)}\n" +
                "total ${fmt(stats.totalMs)}ms · $fps FPS · " +
                "${stats.processedWidth}×${stats.processedHeight}\n" +
                "(* = cache hit)"
        }

        // 实际 / 生效 API（模拟低版本时显示箭头）
        val apiLine = if (glassView.debugApiLevelCap != Int.MAX_VALUE) {
            "API ${Build.VERSION.SDK_INT}→${glassView.effectiveApiLevel}(sim)"
        } else {
            "API ${Build.VERSION.SDK_INT}"
        }

        tvPerformanceOverlay.text = "$overlayText\n$apiLine"
        tvDebugInfo.text = "$debugText\n$apiLine"
    }

    // ==================== 背景图片 ====================

    private fun checkPermissionAndOpenPicker() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        when {
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED ->
                openImagePicker()
            else -> permissionLauncher.launch(permission)
        }
    }

    private fun openImagePicker() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        imagePickerLauncher.launch(intent)
    }

    private fun loadBackgroundImage(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()

            if (bitmap != null) {
                customBackgroundBitmap?.recycle()
                customBackgroundBitmap = bitmap
                showScene(Scene.IMAGE)
                drawerLayout.closeDrawer(GravityCompat.END)
                Toast.makeText(this, getString(R.string.toast_image_selected), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, getString(R.string.toast_no_image_selected), Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load image", e)
            Toast.makeText(this, getString(R.string.toast_no_image_selected), Toast.LENGTH_SHORT).show()
        }
    }

    // ==================== 语言 ====================

    private fun applySavedLanguage() {
        val prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        // 默认英文；用户在面板里切换过语言后跟随其选择
        val savedLang = prefs.getString(KEY_LANGUAGE, LANG_ENGLISH) ?: LANG_ENGLISH
        setAppLocale(savedLang)
    }

    private fun setAppLocale(languageCode: String) {
        val locale = when (languageCode) {
            LANG_ENGLISH -> Locale.ENGLISH
            else -> Locale.CHINESE
        }
        Locale.setDefault(locale)
        val config = Configuration(resources.configuration)
        config.setLocale(locale)
        @Suppress("DEPRECATION")
        resources.updateConfiguration(config, resources.displayMetrics)
    }

    private fun switchLanguage(languageCode: String) {
        getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_LANGUAGE, languageCode).apply()
        val intent = intent
        finish()
        startActivity(intent)
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun getLanguageSwitchButtonText(): String {
        val prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val currentLang = prefs.getString(KEY_LANGUAGE, LANG_ENGLISH) ?: LANG_ENGLISH
        return if (currentLang == LANG_CHINESE) {
            getString(R.string.button_language_english)
        } else {
            getString(R.string.button_language_chinese)
        }
    }

    // ==================== 生命周期 ====================

    override fun onDestroy() {
        super.onDestroy()
        dismissGlassBottomSheet()
        performanceHandler.removeCallbacksAndMessages(null)
        customBackgroundBitmap?.recycle()
        customBackgroundBitmap = null
        scenicBitmap?.recycle()
        scenicBitmap = null
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.END)) {
            drawerLayout.closeDrawer(GravityCompat.END)
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }
}
