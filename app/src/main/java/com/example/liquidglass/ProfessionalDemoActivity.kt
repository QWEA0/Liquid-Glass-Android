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
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
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
        SHOWCASE(R.string.scene_showcase)
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
    private val sceneChips = mutableListOf<TextView>()

    // 动态显隐的面板分组
    private lateinit var cpuOptionsGroup: LinearLayout
    private lateinit var lensGroup: LinearLayout
    private lateinit var aberrationGroup: LinearLayout
    private lateinit var dispersionGroup: LinearLayout

    // 性能监控数据源（融合场景使用场景内的玻璃视图）
    private var statsSource: LiquidGlassView? = null

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
        private const val COLOR_CARD = 0xFFFFFFFF.toInt()     // 卡片
        private const val COLOR_TEXT = 0xFF111111.toInt()     // 主文字
        private const val COLOR_TEXT_DIM = 0xFF8E8E93.toInt() // 次要文字
        private const val COLOR_ACCENT = 0xFF007AFF.toInt()   // 强调色
        private const val COLOR_SEG_BG = 0xFFE9E9EB.toInt()   // 分段控件底
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applySavedLanguage()
        super.onCreate(savedInstanceState)

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
                setMargins(dp(8), dp(40), dp(8), 0)
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
        mainContent.addView(createSceneBar())

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
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(28)
            }
            background = GradientDrawable().apply {
                cornerRadius = dpF(22)
                setColor(0xB3000000.toInt())
            }
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }

        Scene.entries.forEach { scene ->
            val chip = TextView(this).apply {
                text = getString(scene.labelRes)
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(8), dp(16), dp(8))
                setOnClickListener { if (currentScene != scene) showScene(scene) }
            }
            sceneChips += chip
            bar.addView(chip)
        }
        return bar
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
    }

    // ==================== 场景 ====================

    private fun showScene(scene: Scene) {
        currentScene = scene
        extraGlassViews.clear()
        (glassView.parent as? ViewGroup)?.removeView(glassView)
        sceneHost.removeAllViews()
        statsSource = glassView

        val root = when (scene) {
            Scene.SCROLL -> buildScrollScene()
            Scene.IMAGE -> buildImageScene()
            Scene.ANIMATED -> buildAnimatedScene()
            Scene.MERGE -> buildMergeScene()
            Scene.SHOWCASE -> buildShowcaseScene()
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
                topMargin = dp(56)
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
                setMargins(dp(16), dp(48), dp(16), 0)
            }
            addView(TextView(this@ProfessionalDemoActivity).apply {
                text = "🧭  Liquid NavBar"
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
                text = "💳  Glass Card\nBlur · Aberration · Highlight"
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
        target.useShaderPipeline = src.useShaderPipeline
        target.material = src.material
        target.bevelWidth = src.bevelWidth
        target.refractionHeight = src.refractionHeight
        target.dispersionStrength = src.dispersionStrength
        target.enableSensorHighlight = src.enableSensorHighlight
        target.enableAdaptiveTint = src.enableAdaptiveTint
        target.accessibilityMode = src.accessibilityMode
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
            val currentLang = prefs.getString(KEY_LANGUAGE, LANG_CHINESE) ?: LANG_CHINESE
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

        tvPerformanceOverlay.text = overlayText
        tvDebugInfo.text = debugText
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
        val savedLang = prefs.getString(KEY_LANGUAGE, LANG_CHINESE) ?: LANG_CHINESE
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
        val currentLang = prefs.getString(KEY_LANGUAGE, LANG_CHINESE) ?: LANG_CHINESE
        return if (currentLang == LANG_CHINESE) {
            getString(R.string.button_language_english)
        } else {
            getString(R.string.button_language_chinese)
        }
    }

    // ==================== 生命周期 ====================

    override fun onDestroy() {
        super.onDestroy()
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
