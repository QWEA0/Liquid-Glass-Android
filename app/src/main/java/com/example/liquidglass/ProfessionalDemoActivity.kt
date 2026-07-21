/**
 * LiquidGlass 专业演示 Activity
 *
 * 功能特性：
 * - 可滚动的彩色背景或自定义图片背景
 * - 悬浮按钮（FAB）
 * - 侧边栏调试面板（DrawerLayout）
 * - 实时性能监控显示
 * - 背景图片选择功能
 * - 中英文语言切换
 */
package com.example.liquidglass

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.util.Locale

class ProfessionalDemoActivity : AppCompatActivity() {

    // 主要组件
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var glassView: LiquidGlassView
    private lateinit var fabSettings: FloatingActionButton

    // 背景相关
    private lateinit var scrollView: ScrollView
    private lateinit var backgroundImageView: ImageView
    private var customBackgroundBitmap: Bitmap? = null

    // 性能监控
    private lateinit var tvPerformanceOverlay: TextView
    private val performanceHandler = Handler(Looper.getMainLooper())
    private var isMonitoring = true

    // 调试面板控件
    private lateinit var seekBlur: SeekBar
    private lateinit var seekSaturation: SeekBar
    private lateinit var seekAberration: SeekBar
    private lateinit var spinnerBlurMethod: Spinner
    private lateinit var spinnerAberrationMethod: Spinner
    private lateinit var switchHighQuality: Switch
    private lateinit var switchPerformanceOverlay: Switch
    private lateinit var switchEnableBlur: Switch
    private lateinit var switchEnableAberration: Switch
    private lateinit var switchEnableSaturation: Switch
    private lateinit var switchBilinearInterpolation: Switch

    // 色散效果控件
    private lateinit var radioGroupAlgorithm: RadioGroup
    private lateinit var radioAberration: RadioButton
    private lateinit var radioDispersion: RadioButton
    private lateinit var seekDispersionThickness: SeekBar
    private lateinit var seekDispersionFactor: SeekBar
    private lateinit var seekDispersionGain: SeekBar
    private lateinit var seekDispersionDownsample: SeekBar
    private lateinit var spinnerDispersionPreset: Spinner

    private lateinit var tvBlur: TextView
    private lateinit var tvSaturation: TextView
    private lateinit var tvAberration: TextView
    private lateinit var tvBlurMethod: TextView
    private lateinit var tvAberrationMethod: TextView
    private lateinit var tvDispersionThickness: TextView
    private lateinit var tvDispersionFactor: TextView
    private lateinit var tvDispersionGain: TextView
    private lateinit var tvDispersionDownsample: TextView
    private lateinit var tvDebugInfo: TextView
    private lateinit var tvImageSizes: TextView
    private lateinit var tvCurrentLanguage: TextView

    // 图片选择器
    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                loadBackgroundImage(uri)
            }
        }
    }

    // 权限请求
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            openImagePicker()
        } else {
            Toast.makeText(this, "需要存储权限才能选择图片", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val TAG = "ProfessionalDemo"
        private const val PREF_NAME = "LiquidGlassPrefs"
        private const val KEY_LANGUAGE = "language"
        private const val LANG_ENGLISH = "en"
        private const val LANG_CHINESE = "zh"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 应用保存的语言设置
        applySavedLanguage()

        super.onCreate(savedInstanceState)

        // 创建主布局
        createMainLayout()

        // 初始化组件
        initViews()
        setupControls()
        startPerformanceMonitoring()
    }

    /**
     * 应用保存的语言设置
     */
    private fun applySavedLanguage() {
        val prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val savedLang = prefs.getString(KEY_LANGUAGE, LANG_CHINESE) ?: LANG_CHINESE
        setAppLocale(savedLang)
    }

    /**
     * 设置应用语言
     */
    private fun setAppLocale(languageCode: String) {
        val locale = when (languageCode) {
            LANG_ENGLISH -> Locale.ENGLISH
            LANG_CHINESE -> Locale.CHINESE
            else -> Locale.CHINESE
        }

        Locale.setDefault(locale)
        val config = Configuration(resources.configuration)
        config.setLocale(locale)
        resources.updateConfiguration(config, resources.displayMetrics)
    }

    /**
     * 保存语言设置
     */
    private fun saveLanguagePreference(languageCode: String) {
        val prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LANGUAGE, languageCode).apply()
    }

    /**
     * 切换语言
     */
    private fun switchLanguage(languageCode: String) {
        saveLanguagePreference(languageCode)

        // 重启Activity以应用新语言
        val intent = intent
        finish()
        startActivity(intent)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun createMainLayout() {
        // 创建 DrawerLayout
        drawerLayout = DrawerLayout(this).apply {
            layoutParams = DrawerLayout.LayoutParams(
                DrawerLayout.LayoutParams.MATCH_PARENT,
                DrawerLayout.LayoutParams.MATCH_PARENT
            )
        }
        
        // 创建主内容区域
        val mainContent = createMainContent()
        drawerLayout.addView(mainContent)
        
        // 创建侧边栏
        val drawer = createDrawer()
        drawerLayout.addView(drawer)
        
        setContentView(drawerLayout)
    }

    private fun createMainContent(): FrameLayout {
        val container = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // ✅ 先创建背景图片视图（用于自定义背景,放在ScrollView内部）
        backgroundImageView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE  // 默认隐藏
        }

        // 创建可滚动背景
        scrollView = createScrollableBackground()
        container.addView(scrollView)
        
        // 创建 LiquidGlass 悬浮按钮
        glassView = LiquidGlassView(this).apply {
            id = View.generateViewId()
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
            }
            // ✅ 启用动态背景模式（因为有滚动背景）
            enableDynamicBackground = true
        }
        
        // 添加按钮内容
        val buttonContent = TextView(this).apply {
            text = getString(R.string.glass_button_text)
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(100, 80, 100, 80)
            setShadowLayer(8f, 0f, 2f, Color.BLACK)
        }
        glassView.addView(buttonContent)
        container.addView(glassView)
        
        // 创建性能监控悬浮窗
        tvPerformanceOverlay = TextView(this).apply {
            id = View.generateViewId()
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                setMargins(16, 16, 16, 16)
            }
            setBackgroundColor(0xCC000000.toInt())
            setTextColor(Color.GREEN)
            textSize = 10f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(12, 8, 12, 8)
            text = getString(R.string.performance_waiting)
        }
        container.addView(tvPerformanceOverlay)
        
        // 创建 FAB
        fabSettings = FloatingActionButton(this).apply {
            id = View.generateViewId()
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                setMargins(0, 0, 32, 32)
            }
            setImageResource(android.R.drawable.ic_menu_preferences)
            setOnClickListener {
                drawerLayout.openDrawer(GravityCompat.END)
            }
        }
        container.addView(fabSettings)
        
        return container
    }

    private fun createScrollableBackground(): ScrollView {
        val scrollView = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val scrollContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // ✅ 添加上下边距，使内容可以滚动到顶部和底部
            setPadding(0, 300, 0, 300)
        }

        // ✅ 添加自定义背景图片容器（无限拼接）
        // 将 backgroundImageView 添加到这里,稍后在选择图片时填充
        scrollContent.addView(backgroundImageView)

        // 添加多个彩色块作为背景
        val colorData = listOf(
            Pair(0xFFFF6B6B.toInt(), "🌹 红色区域\nRed Zone"),
            Pair(0xFF4ECDC4.toInt(), "🌊 青色区域\nCyan Zone"),
            Pair(0xFF45B7D1.toInt(), "💙 蓝色区域\nBlue Zone"),
            Pair(0xFFFFA07A.toInt(), "🍊 橙色区域\nOrange Zone"),
            Pair(0xFF98D8C8.toInt(), "🌿 绿色区域\nGreen Zone"),
            Pair(0xFFF7DC6F.toInt(), "⭐ 黄色区域\nYellow Zone"),
            Pair(0xFFBB8FCE.toInt(), "💜 紫色区域\nPurple Zone"),
            Pair(0xFF85C1E2.toInt(), "☁️ 浅蓝区域\nLight Blue Zone")
        )

        colorData.forEach { (color, text) ->
            val container = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    400
                )
                setBackgroundColor(color)
            }

            val textView = TextView(this).apply {
                this.text = text
                textSize = 24f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setShadowLayer(4f, 2f, 2f, Color.BLACK)
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }

            container.addView(textView)
            scrollContent.addView(container)
        }
        
        scrollView.addView(scrollContent)
        return scrollView
    }

    private fun createDrawer(): LinearLayout {
        return LinearLayout(this).apply {
            layoutParams = DrawerLayout.LayoutParams(
                (resources.displayMetrics.widthPixels * 0.85f).toInt(),
                DrawerLayout.LayoutParams.MATCH_PARENT
            ).apply {
                gravity = GravityCompat.END
            }
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFFFFFFF.toInt())
            setPadding(24, 48, 24, 24)
            
            // 标题
            addView(TextView(this@ProfessionalDemoActivity).apply {
                text = getString(R.string.debug_panel_title)
                textSize = 24f
                setTextColor(Color.BLACK)
                setPadding(0, 0, 0, 24)
            })
            
            // 创建 ScrollView 包含所有控件
            val scrollView = ScrollView(this@ProfessionalDemoActivity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            }
            
            val controlsContainer = LinearLayout(this@ProfessionalDemoActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            
            // 添加所有控件到 controlsContainer
            addDrawerControls(controlsContainer)
            
            scrollView.addView(controlsContainer)
            addView(scrollView)
        }
    }

    private fun addDrawerControls(container: LinearLayout) {
        // 性能监控开关
        container.addView(createSectionTitle(getString(R.string.section_performance)))

        switchPerformanceOverlay = Switch(this).apply {
            id = View.generateViewId()
            text = getString(R.string.switch_performance_overlay)
            setTextColor(Color.BLACK)
            isChecked = true
            setOnCheckedChangeListener { _, isChecked ->
                tvPerformanceOverlay.visibility = if (isChecked) View.VISIBLE else View.GONE
                isMonitoring = isChecked
            }
        }
        container.addView(switchPerformanceOverlay)

        tvDebugInfo = TextView(this).apply {
            id = View.generateViewId()
            textSize = 11f
            setTextColor(Color.BLACK)
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, 8, 0, 16)
            text = getString(R.string.debug_info_waiting)
        }
        container.addView(tvDebugInfo)

        // ✅ 渲染路径切换（GPU / CPU）
        container.addView(createSectionTitle(getString(R.string.section_render_path)))

        val switchGpuRender = Switch(this).apply {
            id = View.generateViewId()
            text = getString(R.string.switch_gpu_render)
            setTextColor(Color.BLACK)
            isChecked = glassView.useHardwareBlurWhenPossible
            setOnCheckedChangeListener { _, isChecked ->
                glassView.useHardwareBlurWhenPossible = isChecked
            }
        }
        container.addView(switchGpuRender)

        val gpuRenderDesc = TextView(this).apply {
            text = getString(R.string.gpu_render_desc)
            textSize = 10f
            setTextColor(0xFF666666.toInt())
            setPadding(0, 8, 0, 0)
        }
        container.addView(gpuRenderDesc)

        container.addView(createDivider())

        // 模糊方法选择
        container.addView(createSectionTitle(getString(R.string.section_blur_method)))

        tvBlurMethod = TextView(this).apply {
            id = View.generateViewId()
            text = getString(R.string.current_blur_method)
            textSize = 12f
            setTextColor(Color.BLACK)
            setPadding(0, 0, 0, 4)
        }
        container.addView(tvBlurMethod)

        spinnerBlurMethod = Spinner(this).apply {
            id = View.generateViewId()
            // ✅ 优化 Spinner 可见性：添加背景和内边距
            setBackgroundColor(Color.WHITE)
            setPadding(16, 16, 16, 16)
        }
        container.addView(spinnerBlurMethod)

        // 算法说明
        val blurMethodDesc = TextView(this).apply {
            text = getString(R.string.blur_method_desc)
            textSize = 10f
            setTextColor(0xFF666666.toInt())
            setPadding(0, 8, 0, 0)
        }
        container.addView(blurMethodDesc)

        container.addView(createDivider())

        // 色差算法选择
        container.addView(createSectionTitle(getString(R.string.section_aberration_method)))

        tvAberrationMethod = TextView(this).apply {
            id = View.generateViewId()
            text = getString(R.string.current_aberration_method)
            textSize = 12f
            setTextColor(Color.BLACK)
            setPadding(0, 0, 0, 4)
        }
        container.addView(tvAberrationMethod)

        spinnerAberrationMethod = Spinner(this).apply {
            id = View.generateViewId()
            // ✅ 优化 Spinner 可见性：添加背景和内边距
            setBackgroundColor(Color.WHITE)
            setPadding(16, 16, 16, 16)
        }
        container.addView(spinnerAberrationMethod)

        // 算法说明
        val aberrationMethodDesc = TextView(this).apply {
            text = getString(R.string.aberration_method_desc)
            textSize = 10f
            setTextColor(0xFF666666.toInt())
            setPadding(0, 8, 0, 0)
        }
        container.addView(aberrationMethodDesc)

        container.addView(createDivider())

        // 色差质量设置
        container.addView(createSectionTitle(getString(R.string.section_aberration_quality)))

        switchBilinearInterpolation = Switch(this).apply {
            id = View.generateViewId()
            text = getString(R.string.switch_bilinear_interpolation)
            setTextColor(Color.BLACK)
            isChecked = true  // 默认启用双线性插值
            setOnCheckedChangeListener { _, isChecked ->
                glassView.aberrationUseBilinearInterpolation = isChecked
            }
        }
        container.addView(switchBilinearInterpolation)

        // 双线性插值说明
        val bilinearDesc = TextView(this).apply {
            text = getString(R.string.bilinear_desc)
            textSize = 10f
            setTextColor(0xFF666666.toInt())
            setPadding(0, 8, 0, 0)
        }
        container.addView(bilinearDesc)

        container.addView(createDivider())

        // 效果开关
        container.addView(createSectionTitle(getString(R.string.section_effect_switches)))

        switchEnableBlur = Switch(this).apply {
            id = View.generateViewId()
            text = getString(R.string.switch_enable_blur)
            setTextColor(Color.BLACK)
            isChecked = true
            setOnCheckedChangeListener { _, isChecked ->
                glassView.enableBackdropBlur = isChecked
            }
        }
        container.addView(switchEnableBlur)

        switchEnableAberration = Switch(this).apply {
            id = View.generateViewId()
            text = getString(R.string.switch_enable_chromatic_effect)
            setTextColor(Color.BLACK)
            isChecked = true
            setOnCheckedChangeListener { _, isChecked ->
                // 根据当前选择的算法启用/禁用对应效果
                if (radioAberration.isChecked) {
                    glassView.enableChromaticAberration = isChecked
                } else {
                    glassView.enableChromaticDispersion = isChecked
                }
            }
        }
        container.addView(switchEnableAberration)

        switchEnableSaturation = Switch(this).apply {
            id = View.generateViewId()
            text = getString(R.string.switch_enable_saturation)
            setTextColor(Color.BLACK)
            isChecked = true
            setOnCheckedChangeListener { _, isChecked ->
                // 饱和度通过设置为 100 来禁用
                if (!isChecked) {
                    glassView.saturation = 100f
                    seekSaturation.isEnabled = false
                } else {
                    seekSaturation.isEnabled = true
                }
            }
        }
        container.addView(switchEnableSaturation)

        container.addView(createDivider())

        // 模糊参数
        container.addView(createSectionTitle(getString(R.string.section_blur_params)))

        tvBlur = TextView(this).apply {
            id = View.generateViewId()
            setTextColor(Color.BLACK)
        }
        container.addView(tvBlur)
        seekBlur = SeekBar(this).apply { id = View.generateViewId() }
        container.addView(seekBlur)

        container.addView(createDivider())

        // 饱和度
        container.addView(createSectionTitle(getString(R.string.section_saturation)))

        tvSaturation = TextView(this).apply {
            id = View.generateViewId()
            setTextColor(Color.BLACK)
        }
        container.addView(tvSaturation)
        seekSaturation = SeekBar(this).apply { id = View.generateViewId() }
        container.addView(seekSaturation)

        container.addView(createDivider())

        // ✅ 效果算法选择（单选框）
        container.addView(createSectionTitle(getString(R.string.effect_algorithm)))

        radioGroupAlgorithm = RadioGroup(this).apply {
            id = View.generateViewId()
            orientation = RadioGroup.VERTICAL
            setPadding(0, 8, 0, 16)
        }

        radioAberration = RadioButton(this).apply {
            id = View.generateViewId()
            text = getString(R.string.algorithm_aberration)
            setTextColor(Color.BLACK)
            isChecked = true
        }
        radioGroupAlgorithm.addView(radioAberration)

        radioDispersion = RadioButton(this).apply {
            id = View.generateViewId()
            text = getString(R.string.algorithm_dispersion)
            setTextColor(Color.BLACK)
        }
        radioGroupAlgorithm.addView(radioDispersion)

        radioGroupAlgorithm.setOnCheckedChangeListener { _, checkedId ->
            Log.d(TAG, "📻 算法切换事件触发: checkedId=$checkedId")
            when (checkedId) {
                radioAberration.id -> {
                    Log.d(TAG, "  → 切换到色差效果")
                    // 切换到色差效果
                    val shouldEnable = switchEnableAberration.isChecked
                    Log.d(TAG, "  开关状态: $shouldEnable")
                    glassView.enableChromaticDispersion = false
                    // 强制触发 setter：先设置为相反值，再设置为目标值
                    glassView.enableChromaticAberration = !shouldEnable
                    glassView.enableChromaticAberration = shouldEnable
                    // 显示色差参数，隐藏色散参数
                    seekAberration.visibility = View.VISIBLE
                    tvAberration.visibility = View.VISIBLE
                    seekDispersionThickness.visibility = View.GONE
                    seekDispersionFactor.visibility = View.GONE
                    seekDispersionGain.visibility = View.GONE
                    seekDispersionDownsample.visibility = View.GONE
                    tvDispersionThickness.visibility = View.GONE
                    tvDispersionFactor.visibility = View.GONE
                    tvDispersionGain.visibility = View.GONE
                    tvDispersionDownsample.visibility = View.GONE
                    spinnerDispersionPreset.visibility = View.GONE
                }
                radioDispersion.id -> {
                    Log.d(TAG, "  → 切换到色散效果")
                    // 切换到色散效果
                    val shouldEnable = switchEnableAberration.isChecked
                    Log.d(TAG, "  开关状态: $shouldEnable")
                    glassView.enableChromaticAberration = false
                    // 强制触发 setter：先设置为相反值，再设置为目标值
                    glassView.enableChromaticDispersion = !shouldEnable
                    glassView.enableChromaticDispersion = shouldEnable
                    // 隐藏色差参数，显示色散参数
                    seekAberration.visibility = View.GONE
                    tvAberration.visibility = View.GONE
                    seekDispersionThickness.visibility = View.VISIBLE
                    seekDispersionFactor.visibility = View.VISIBLE
                    seekDispersionGain.visibility = View.VISIBLE
                    seekDispersionDownsample.visibility = View.VISIBLE
                    tvDispersionThickness.visibility = View.VISIBLE
                    tvDispersionFactor.visibility = View.VISIBLE
                    tvDispersionGain.visibility = View.VISIBLE
                    tvDispersionDownsample.visibility = View.VISIBLE
                    spinnerDispersionPreset.visibility = View.VISIBLE
                }
            }
        }

        container.addView(radioGroupAlgorithm)

        container.addView(createDivider())

        // 色差强度
        container.addView(createSectionTitle(getString(R.string.section_aberration)))

        tvAberration = TextView(this).apply {
            id = View.generateViewId()
            setTextColor(Color.BLACK)
        }
        container.addView(tvAberration)
        seekAberration = SeekBar(this).apply { id = View.generateViewId() }
        container.addView(seekAberration)

        // ✅ 色散效果参数（初始隐藏）
        // 预设选择
        val tvDispersionPreset = TextView(this).apply {
            id = View.generateViewId()
            text = getString(R.string.dispersion_preset)
            setTextColor(Color.BLACK)
            setPadding(0, 16, 0, 8)
        }
        container.addView(tvDispersionPreset)

        val presets = arrayOf(
            getString(R.string.preset_glass),
            getString(R.string.preset_diamond),
            getString(R.string.preset_crystal),
            getString(R.string.preset_rainbow),
            getString(R.string.preset_subtle)
        )

        spinnerDispersionPreset = Spinner(this).apply {
            id = View.generateViewId()
            visibility = View.GONE
        }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, presets)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerDispersionPreset.adapter = adapter
        spinnerDispersionPreset.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                when (position) {
                    0 -> { // Glass
                        glassView.dispersionThickness = 100f
                        glassView.dispersionFactor = 1.5f
                        glassView.dispersionGain = 7f
                    }
                    1 -> { // Diamond
                        glassView.dispersionThickness = 80f
                        glassView.dispersionFactor = 2.4f
                        glassView.dispersionGain = 15f
                    }
                    2 -> { // Crystal
                        glassView.dispersionThickness = 120f
                        glassView.dispersionFactor = 1.8f
                        glassView.dispersionGain = 10f
                    }
                    3 -> { // Rainbow
                        glassView.dispersionThickness = 150f
                        glassView.dispersionFactor = 1.3f
                        glassView.dispersionGain = 20f
                    }
                    4 -> { // Subtle
                        glassView.dispersionThickness = 200f
                        glassView.dispersionFactor = 1.2f
                        glassView.dispersionGain = 3f
                    }
                }
                updateDispersionUI()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        container.addView(spinnerDispersionPreset)

        // 折射厚度
        tvDispersionThickness = TextView(this).apply {
            id = View.generateViewId()
            setTextColor(Color.BLACK)
            text = getString(R.string.dispersion_thickness, 100f)
            visibility = View.GONE
        }
        container.addView(tvDispersionThickness)

        seekDispersionThickness = SeekBar(this).apply {
            id = View.generateViewId()
            max = 150  // 50-200
            progress = 50  // 默认 100
            visibility = View.GONE
        }
        container.addView(seekDispersionThickness)

        // 折射系数
        tvDispersionFactor = TextView(this).apply {
            id = View.generateViewId()
            setTextColor(Color.BLACK)
            text = getString(R.string.dispersion_factor, 1.5f)
            visibility = View.GONE
        }
        container.addView(tvDispersionFactor)

        seekDispersionFactor = SeekBar(this).apply {
            id = View.generateViewId()
            max = 100  // 1.0-3.0
            progress = 25  // 默认 1.5
            visibility = View.GONE
        }
        container.addView(seekDispersionFactor)

        // 色散增益
        tvDispersionGain = TextView(this).apply {
            id = View.generateViewId()
            setTextColor(Color.BLACK)
            text = getString(R.string.dispersion_gain, 7f)
            visibility = View.GONE
        }
        container.addView(tvDispersionGain)

        seekDispersionGain = SeekBar(this).apply {
            id = View.generateViewId()
            max = 50  // 0-50
            progress = 7  // 默认 7
            visibility = View.GONE
        }
        container.addView(seekDispersionGain)

        // 色散降采样
        tvDispersionDownsample = TextView(this).apply {
            id = View.generateViewId()
            setTextColor(Color.BLACK)
            text = getString(R.string.dispersion_downscale, 0.5f)
            visibility = View.GONE
        }
        container.addView(tvDispersionDownsample)

        seekDispersionDownsample = SeekBar(this).apply {
            id = View.generateViewId()
            max = 75  // 0.25-1.0
            progress = 25  // 默认 0.5
            visibility = View.GONE
        }
        container.addView(seekDispersionDownsample)

        container.addView(createDivider())

        // ✅ 全局下采样比例
        container.addView(createSectionTitle(getString(R.string.section_global_downsample)))

        val tvGlobalDownsample = TextView(this).apply {
            id = View.generateViewId()
            setTextColor(Color.BLACK)
            text = getString(R.string.global_downsample_none)
        }
        container.addView(tvGlobalDownsample)

        val seekGlobalDownsample = SeekBar(this).apply {
            id = View.generateViewId()
            max = 100  // 0.25 - 1.0
            progress = 75  // 默认 1.0
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val factor = 0.25f + (progress / 100f) * 0.75f
                    glassView.globalDownsampleFactor = factor
                    tvGlobalDownsample.text = getString(R.string.global_downsample_value, factor)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        container.addView(seekGlobalDownsample)

        container.addView(createDivider())

        // ✅ 色差下采样比例
        container.addView(createSectionTitle(getString(R.string.section_aberration_downsample)))

        val tvAberrationDownsample = TextView(this).apply {
            id = View.generateViewId()
            setTextColor(Color.BLACK)
            text = getString(R.string.aberration_downsample_value, 0.50f)
        }
        container.addView(tvAberrationDownsample)

        val seekAberrationDownsample = SeekBar(this).apply {
            id = View.generateViewId()
            max = 100  // 0.25 - 1.0
            progress = 33  // 默认 0.5
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val factor = 0.25f + (progress / 100f) * 0.75f
                    glassView.aberrationDownsample = factor
                    tvAberrationDownsample.text = getString(R.string.aberration_downsample_value, factor)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        container.addView(seekAberrationDownsample)

        container.addView(createDivider())

        // ✅ 色差通道偏移量
        container.addView(createSectionTitle(getString(R.string.section_channel_offset)))

        // 红色通道偏移
        val tvRedOffset = TextView(this).apply {
            id = View.generateViewId()
            setTextColor(Color.BLACK)
            text = getString(R.string.red_offset, 0.00f)
        }
        container.addView(tvRedOffset)

        val seekRedOffset = SeekBar(this).apply {
            id = View.generateViewId()
            max = 200  // -0.2 到 0.2
            progress = 100  // 默认 0
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val offset = (progress - 100) / 500f  // -0.2 到 0.2
                    glassView.aberrationRedOffset = offset
                    tvRedOffset.text = getString(R.string.red_offset, offset)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        container.addView(seekRedOffset)

        // 绿色通道偏移
        val tvGreenOffset = TextView(this).apply {
            id = View.generateViewId()
            setTextColor(Color.BLACK)
            text = getString(R.string.green_offset, -0.05f)
        }
        container.addView(tvGreenOffset)

        val seekGreenOffset = SeekBar(this).apply {
            id = View.generateViewId()
            max = 200  // -0.2 到 0.2
            progress = 75  // 默认 -0.05
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val offset = (progress - 100) / 500f  // -0.2 到 0.2
                    glassView.aberrationGreenOffset = offset
                    tvGreenOffset.text = getString(R.string.green_offset, offset)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        container.addView(seekGreenOffset)

        // 蓝色通道偏移
        val tvBlueOffset = TextView(this).apply {
            id = View.generateViewId()
            setTextColor(Color.BLACK)
            text = getString(R.string.blue_offset, -0.10f)
        }
        container.addView(tvBlueOffset)

        val seekBlueOffset = SeekBar(this).apply {
            id = View.generateViewId()
            max = 200  // -0.2 到 0.2
            progress = 50  // 默认 -0.1
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val offset = (progress - 100) / 500f  // -0.2 到 0.2
                    glassView.aberrationBlueOffset = offset
                    tvBlueOffset.text = getString(R.string.blue_offset, offset)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        container.addView(seekBlueOffset)

        container.addView(createDivider())

        // 高质量模式（移到效果参数下方）
        switchHighQuality = Switch(this).apply {
            id = View.generateViewId()
            text = getString(R.string.switch_high_quality)
            setTextColor(Color.BLACK)
            isChecked = false
            setOnCheckedChangeListener { _, isChecked ->
                glassView.highQualityBlur = isChecked
            }
        }
        container.addView(switchHighQuality)

        container.addView(createDivider())

        // ✅ 边缘高光设置
        container.addView(createSectionTitle(getString(R.string.section_edge_highlight)))

        // 启用边缘高光开关
        val switchEnableEdgeHighlight = Switch(this).apply {
            id = View.generateViewId()
            text = getString(R.string.switch_enable_edge_highlight)
            setTextColor(Color.BLACK)
            isChecked = true
            setOnCheckedChangeListener { _, isChecked ->
                glassView.enableEdgeHighlight = isChecked
            }
        }
        container.addView(switchEnableEdgeHighlight)

        // 亮背景模式开关
        val switchOverLight = Switch(this).apply {
            id = View.generateViewId()
            text = getString(R.string.switch_over_light)
            setTextColor(Color.BLACK)
            isChecked = false
            setOnCheckedChangeListener { _, isChecked ->
                glassView.overLight = isChecked
            }
        }
        container.addView(switchOverLight)

        // 边框宽度
        val tvBorderWidth = TextView(this).apply {
            id = View.generateViewId()
            setTextColor(Color.BLACK)
            text = getString(R.string.border_width, 1.5f)
        }
        container.addView(tvBorderWidth)

        val seekBorderWidth = SeekBar(this).apply {
            id = View.generateViewId()
            max = 100  // 0.5 到 5.0
            progress = 20  // 默认 1.5
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val width = 0.5f + (progress / 100f) * 4.5f
                    glassView.edgeHighlightBorderWidth = width
                    tvBorderWidth.text = getString(R.string.border_width, width)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        container.addView(seekBorderWidth)

        // 高光不透明度
        val tvHighlightOpacity = TextView(this).apply {
            id = View.generateViewId()
            setTextColor(Color.BLACK)
            text = getString(R.string.highlight_opacity, 100f)
        }
        container.addView(tvHighlightOpacity)

        val seekHighlightOpacity = SeekBar(this).apply {
            id = View.generateViewId()
            max = 100  // 0 到 100
            progress = 100  // 默认 100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    glassView.edgeHighlightOpacity = progress.toFloat()
                    tvHighlightOpacity.text = getString(R.string.highlight_opacity, progress.toFloat())
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        container.addView(seekHighlightOpacity)

        container.addView(createDivider())

        // ✅ 优化捕获设置
        container.addView(createSectionTitle(getString(R.string.section_optimized_capture)))

        // 启用优化捕获开关
        val switchEnableOptimizedCapture = Switch(this).apply {
            id = View.generateViewId()
            text = getString(R.string.switch_enable_optimized_capture)
            setTextColor(Color.BLACK)
            isChecked = false  // 默认关闭
            setOnCheckedChangeListener { _, isChecked ->
                glassView.enableOptimizedCapture = isChecked
            }
        }
        container.addView(switchEnableOptimizedCapture)

        // 优化捕获说明
        val tvOptimizedCaptureDesc = TextView(this).apply {
            id = View.generateViewId()
            text = getString(R.string.optimized_capture_desc)
            textSize = 11f
            setTextColor(0xFF666666.toInt())
            setPadding(0, 8, 0, 16)
        }
        container.addView(tvOptimizedCaptureDesc)

        container.addView(createDivider())

        // 图片尺寸信息
        container.addView(createSectionTitle(getString(R.string.section_image_size)))

        tvImageSizes = TextView(this).apply {
            id = View.generateViewId()
            textSize = 11f
            setTextColor(Color.BLACK)
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, 8, 0, 16)
            text = getString(R.string.image_size_waiting)
        }
        container.addView(tvImageSizes)

        container.addView(createDivider())

        // ✅ 背景与语言设置
        container.addView(createSectionTitle(getString(R.string.section_background_language)))

        // 更换背景图片按钮
        val btnChangeBackground = Button(this).apply {
            id = View.generateViewId()
            text = getString(R.string.button_change_background)
            setOnClickListener {
                checkPermissionAndOpenPicker()
            }
        }
        container.addView(btnChangeBackground)

        // 当前语言显示
        tvCurrentLanguage = TextView(this).apply {
            id = View.generateViewId()
            text = getCurrentLanguageText()
            textSize = 12f
            setTextColor(Color.BLACK)
            setPadding(0, 16, 0, 8)
        }
        container.addView(tvCurrentLanguage)

        // 语言切换按钮
        val btnSwitchLanguage = Button(this).apply {
            id = View.generateViewId()
            text = getLanguageSwitchButtonText()
            setOnClickListener {
                val prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                val currentLang = prefs.getString(KEY_LANGUAGE, LANG_CHINESE) ?: LANG_CHINESE
                val newLang = if (currentLang == LANG_CHINESE) LANG_ENGLISH else LANG_CHINESE
                switchLanguage(newLang)
            }
        }
        container.addView(btnSwitchLanguage)
    }

    /**
     * 获取当前语言文本
     */
    private fun getCurrentLanguageText(): String {
        return getString(R.string.current_language)
    }

    /**
     * 获取语言切换按钮文本
     */
    private fun getLanguageSwitchButtonText(): String {
        val prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val currentLang = prefs.getString(KEY_LANGUAGE, LANG_CHINESE) ?: LANG_CHINESE
        return if (currentLang == LANG_CHINESE) {
            getString(R.string.button_language_english)
        } else {
            getString(R.string.button_language_chinese)
        }
    }

    /**
     * 检查权限并打开图片选择器
     */
    private fun checkPermissionAndOpenPicker() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        when {
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED -> {
                openImagePicker()
            }
            else -> {
                permissionLauncher.launch(permission)
            }
        }
    }

    /**
     * 打开图片选择器
     */
    private fun openImagePicker() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        imagePickerLauncher.launch(intent)
    }

    /**
     * 加载背景图片（支持无限拼接滚动）
     */
    private fun loadBackgroundImage(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()

            if (bitmap != null) {
                customBackgroundBitmap?.recycle()
                customBackgroundBitmap = bitmap

                // ✅ 创建无限拼接的背景
                createTiledBackground(bitmap)

                Toast.makeText(this, getString(R.string.toast_image_selected), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, getString(R.string.toast_no_image_selected), Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("ProfessionalDemo", "Failed to load image", e)
            Toast.makeText(this, "加载图片失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 创建无限拼接的背景图片
     */
    private fun createTiledBackground(bitmap: Bitmap) {
        // 清空 backgroundImageView 的父容器
        val parent = backgroundImageView.parent as? LinearLayout
        if (parent != null) {
            // 移除所有彩色块,只保留 backgroundImageView
            val childCount = parent.childCount
            for (i in childCount - 1 downTo 0) {
                val child = parent.getChildAt(i)
                if (child != backgroundImageView) {
                    parent.removeViewAt(i)
                }
            }
        }

        // 设置 backgroundImageView 的布局参数以支持无限拼接
        backgroundImageView.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        // 计算需要拼接多少次才能填满屏幕并支持滚动
        val screenHeight = resources.displayMetrics.heightPixels
        val imageHeight = bitmap.height
        val imageWidth = bitmap.width

        // 计算缩放后的高度（保持宽高比,宽度填满屏幕）
        val screenWidth = resources.displayMetrics.widthPixels
        val scaledHeight = (imageHeight.toFloat() / imageWidth.toFloat() * screenWidth).toInt()

        // 至少拼接5次,确保有足够的滚动空间
        val repeatCount = maxOf(5, (screenHeight * 3) / scaledHeight)

        // 创建一个垂直的 LinearLayout 来容纳多个图片副本
        val tiledContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            // 添加上下边距
            setPadding(0, 300, 0, 300)
        }

        // 添加多个图片副本实现无限拼接效果
        for (i in 0 until repeatCount) {
            val imageView = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    scaledHeight
                )
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(bitmap)
            }
            tiledContainer.addView(imageView)
        }

        // 替换 ScrollView 的内容
        scrollView.removeAllViews()
        scrollView.addView(tiledContainer)

        // 隐藏原来的 backgroundImageView (因为我们用新的容器了)
        backgroundImageView.visibility = View.GONE
    }

    private fun createSectionTitle(title: String): TextView {
        return TextView(this).apply {
            text = title
            textSize = 14f
            setTextColor(Color.BLACK)
            setPadding(0, 16, 0, 8)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
    }

    private fun createDivider(): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                1
            ).apply {
                setMargins(0, 16, 0, 0)
            }
            setBackgroundColor(0xFFE0E0E0.toInt())
        }
    }

    private fun initViews() {
        // 初始化已在 createDrawer 中完成
    }

    private fun setupControls() {
        // 模糊方法选择
        val blurMethods = arrayOf(
            getString(R.string.blur_method_smart),
            getString(R.string.blur_method_box),
            getString(R.string.blur_method_box_cpp),
            getString(R.string.blur_method_iir),
            getString(R.string.blur_method_neon),
            getString(R.string.blur_method_box3),
            getString(R.string.blur_method_downsample)
        )
        val blurAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, blurMethods)
        blurAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerBlurMethod.adapter = blurAdapter
        spinnerBlurMethod.setSelection(0)

        spinnerBlurMethod.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                glassView.blurMethod = when (position) {
                    0 -> BlurMethod.SMART
                    1 -> BlurMethod.BOX_BLUR
                    2 -> BlurMethod.BOX_BLUR_CPP
                    3 -> BlurMethod.IIR_GAUSSIAN
                    4 -> BlurMethod.IIR_GAUSSIAN_NEON
                    5 -> BlurMethod.BOX3
                    6 -> BlurMethod.DOWNSAMPLE
                    else -> BlurMethod.SMART
                }
                tvBlurMethod.text = "${getString(R.string.current_blur_method).substringBefore(':')}：${blurMethods[position]}"
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 色差算法选择
        val aberrationMethods = arrayOf(
            getString(R.string.aberration_method_auto),
            getString(R.string.aberration_method_cpp),
            getString(R.string.aberration_method_kotlin)
        )
        val aberrationAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, aberrationMethods)
        aberrationAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerAberrationMethod.adapter = aberrationAdapter
        spinnerAberrationMethod.setSelection(0)

        spinnerAberrationMethod.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                glassView.chromaticAberrationMode = when (position) {
                    0 -> ChromaticAberrationEffect.PerformanceMode.AUTO
                    1 -> ChromaticAberrationEffect.PerformanceMode.CPP
                    2 -> ChromaticAberrationEffect.PerformanceMode.KOTLIN
                    else -> ChromaticAberrationEffect.PerformanceMode.AUTO
                }
                tvAberrationMethod.text = "${getString(R.string.current_aberration_method).substringBefore(':')}：${aberrationMethods[position]}"
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 模糊强度
        seekBlur.max = 100
        seekBlur.progress = (glassView.blurAmount * 1000).toInt()
        tvBlur.text = getString(R.string.blur_amount, glassView.blurAmount)
        seekBlur.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progress / 1000f
                glassView.blurAmount = value
                tvBlur.text = getString(R.string.blur_amount, value)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 饱和度
        seekSaturation.max = 200
        seekSaturation.progress = glassView.saturation.toInt()
        tvSaturation.text = getString(R.string.saturation_value, glassView.saturation.toInt())
        seekSaturation.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                glassView.saturation = progress.toFloat()
                tvSaturation.text = getString(R.string.saturation_value, progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 色差强度
        seekAberration.max = 100
        seekAberration.progress = (glassView.aberrationIntensity * 10).toInt()
        tvAberration.text = getString(R.string.aberration_value, glassView.aberrationIntensity)
        seekAberration.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progress / 10f
                glassView.aberrationIntensity = value
                tvAberration.text = getString(R.string.aberration_value, value)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // ✅ 色散参数监听器
        seekDispersionThickness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = 50f + progress
                glassView.dispersionThickness = value
                tvDispersionThickness.text = getString(R.string.dispersion_thickness, value)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        seekDispersionFactor.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = 1.0f + progress / 50f
                glassView.dispersionFactor = value
                tvDispersionFactor.text = getString(R.string.dispersion_factor, value)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        seekDispersionGain.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progress.toFloat()
                glassView.dispersionGain = value
                tvDispersionGain.text = getString(R.string.dispersion_gain, value)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        seekDispersionDownsample.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = 0.25f + progress / 100f
                glassView.dispersionDownsample = value
                tvDispersionDownsample.text = getString(R.string.dispersion_downscale, value)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 高质量模式
        switchHighQuality.setOnCheckedChangeListener { _, isChecked ->
            glassView.highQualityBlur = isChecked
        }
    }

    /**
     * 更新色散UI显示
     */
    private fun updateDispersionUI() {
        seekDispersionThickness.progress = (glassView.dispersionThickness - 50).toInt()
        seekDispersionFactor.progress = ((glassView.dispersionFactor - 1.0f) * 50).toInt()
        seekDispersionGain.progress = glassView.dispersionGain.toInt()
        seekDispersionDownsample.progress = ((glassView.dispersionDownsample - 0.25f) * 100).toInt()

        tvDispersionThickness.text = getString(R.string.dispersion_thickness, glassView.dispersionThickness)
        tvDispersionFactor.text = getString(R.string.dispersion_factor, glassView.dispersionFactor)
        tvDispersionGain.text = getString(R.string.dispersion_gain, glassView.dispersionGain)
        tvDispersionDownsample.text = getString(R.string.dispersion_downscale, glassView.dispersionDownsample)
    }

    private fun startPerformanceMonitoring() {
        val updateInterval = 500L
        
        val runnable = object : Runnable {
            override fun run() {
                if (isMonitoring) {
                    updatePerformanceDisplay()
                }
                performanceHandler.postDelayed(this, updateInterval)
            }
        }
        
        performanceHandler.post(runnable)
    }

    private fun updatePerformanceDisplay() {
        // ✅ 直接读取 LiquidGlassView 的结构化帧统计（不再执行 logcat 子进程解析日志）
        val stats = glassView.lastFrameStats ?: return

        val capturedSize = "${glassView.width}×${glassView.height}"

        // 获取当前算法信息
        val blurMethodName = when (glassView.blurMethod) {
            BlurMethod.SMART -> "智能"
            BlurMethod.BOX_BLUR -> "Box(KT)"
            BlurMethod.BOX_BLUR_CPP -> "Box(C++)"
            BlurMethod.IIR_GAUSSIAN -> "IIR"
            BlurMethod.IIR_GAUSSIAN_NEON -> "NEON"
            BlurMethod.BOX3 -> "Box3"
            BlurMethod.DOWNSAMPLE -> "下采样"
        }

        val aberrationMethodName = when (glassView.chromaticAberrationMode) {
            ChromaticAberrationEffect.PerformanceMode.AUTO -> "自动"
            ChromaticAberrationEffect.PerformanceMode.CPP -> "C++"
            ChromaticAberrationEffect.PerformanceMode.KOTLIN -> "KT"
        }

        val isGpu = stats.effectName.startsWith("GPU")
        // 优先显示实测帧率；无重绘循环时退化为按管线耗时推算
        val fps = when {
            stats.drawFps > 0 -> stats.drawFps
            stats.totalMs > 0f -> (1000f / stats.totalMs).toInt()
            else -> 0
        }

        fun fmt(ms: Float) = String.format("%.2f", ms)
        val blurStatus = if (stats.blurRecomputed) "" else " (缓存)"
        val effectStatus = if (stats.effectRecomputed) "" else " (缓存)"

        val overlayText = if (isGpu) {
            """
                FPS: $fps
                渲染: ${stats.effectName} (RenderEffect)
                录制: ${fmt(stats.totalMs)}ms
            """.trimIndent()
        } else {
            """
                FPS: $fps
                捕获: ${fmt(stats.captureMs)}ms
                模糊: ${fmt(stats.blurMs)}ms ($blurMethodName)$blurStatus
                ${stats.effectName}: ${fmt(stats.effectMs)}ms ($aberrationMethodName)$effectStatus
                总计: ${fmt(stats.totalMs)}ms
            """.trimIndent()
        }

        val debugText = if (isGpu) {
            """
                性能详情：
                - 渲染路径: ${stats.effectName} (RenderNode + RenderEffect)
                - CPU 侧录制耗时: ${fmt(stats.totalMs)}ms
                - 帧率: $fps FPS

                说明：模糊/饱和度（API 31+）与色差（API 33+）
                完全由 GPU 执行，无 Bitmap 分配、无像素回读。
                色散效果或低版本系统会回退到 CPU 管线。
            """.trimIndent()
        } else {
            """
                性能详情：
                - 捕获背景: ${fmt(stats.captureMs)}ms
                - 模糊处理: ${fmt(stats.blurMs)}ms (算法: $blurMethodName)$blurStatus
                - ${stats.effectName}效果: ${fmt(stats.effectMs)}ms (算法: $aberrationMethodName)$effectStatus
                - 总耗时: ${fmt(stats.totalMs)}ms
                - 帧率: $fps FPS

                算法说明：
                - 模糊: $blurMethodName = ${getBlurMethodDescription()}
                - 色差: $aberrationMethodName = ${getAberrationMethodDescription()}
            """.trimIndent()
        }

        val sizeText = if (isGpu) {
            """
                截取图片尺寸: $capturedSize
                实际模糊尺寸: GPU 直接处理（无截图）
            """.trimIndent()
        } else if (stats.processedWidth > 0) {
            """
                截取图片尺寸: $capturedSize
                实际模糊尺寸: ${stats.processedWidth}×${stats.processedHeight}

                说明：
                - 截取尺寸 = LiquidGlass 按钮大小
                - 模糊尺寸 = 实际处理的图片大小
                - 如果两者不同，说明使用了下采样优化
            """.trimIndent()
        } else {
            """
                截取图片尺寸: $capturedSize
                实际模糊尺寸: 等待数据...
            """.trimIndent()
        }

        tvPerformanceOverlay.text = overlayText
        tvDebugInfo.text = debugText
        tvImageSizes.text = sizeText
    }

    override fun onDestroy() {
        super.onDestroy()
        performanceHandler.removeCallbacksAndMessages(null)

        // 释放背景图片资源
        customBackgroundBitmap?.recycle()
        customBackgroundBitmap = null
    }

    override fun onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.END)) {
            drawerLayout.closeDrawer(GravityCompat.END)
        } else {
            super.onBackPressed()
        }
    }

    /**
     * 获取模糊算法描述
     */
    private fun getBlurMethodDescription(): String {
        return when (glassView.blurMethod) {
            BlurMethod.SMART -> "自动选择最优算法"
            BlurMethod.BOX_BLUR -> "传统盒式模糊 (Kotlin)"
            BlurMethod.BOX_BLUR_CPP -> "盒式模糊 (C++ 原生)"
            BlurMethod.IIR_GAUSSIAN -> "C++ 递归高斯(标量)"
            BlurMethod.IIR_GAUSSIAN_NEON -> "C++ 递归高斯(NEON)"
            BlurMethod.BOX3 -> "3次盒式近似高斯"
            BlurMethod.DOWNSAMPLE -> "下采样优化管线"
        }
    }

    /**
     * 获取色差算法描述
     */
    private fun getAberrationMethodDescription(): String {
        return when (glassView.chromaticAberrationMode) {
            ChromaticAberrationEffect.PerformanceMode.AUTO -> "根据图像大小自动选择"
            ChromaticAberrationEffect.PerformanceMode.CPP -> "C++ 原生实现 (3-5x 提升)"
            ChromaticAberrationEffect.PerformanceMode.KOTLIN -> "Kotlin 实现 (兼容性好)"
        }
    }
}

