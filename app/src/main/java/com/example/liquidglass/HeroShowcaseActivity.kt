/**
 * Hero 展示 Activity —— 用于生成 README 首屏素材。
 *
 * 构图：iOS 风格壁纸 + 桌面图标网格 + 一颗液态玻璃药丸。
 * 图标网格提供高频结构，玻璃移动时边缘压缩环与色散彩边才看得见
 * （平滑渐变背景上折射是不可见的）。
 *
 * 录制模式：`adb shell am start -n <pkg>/com.example.liquidglass.HeroShowcaseActivity --ez auto true`
 * 玻璃会自动巡航一圈并做一次按压形变，无需触摸。
 */
package com.example.liquidglass

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.example.liquidglass.demo.R
import android.view.animation.AccelerateDecelerateInterpolator

class HeroShowcaseActivity : AppCompatActivity() {

    private lateinit var glass: LiquidGlassView
    private var autoPlay = false

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        autoPlay = intent?.getBooleanExtra("auto", false) ?: false

        supportActionBar?.hide()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this)

        root.addView(ImageView(this).apply {
            setImageResource(R.drawable.ios_wallpaper)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }, FrameLayout.LayoutParams(MATCH, MATCH))

        root.addView(HomeScreenGridView(this), FrameLayout.LayoutParams(MATCH, MATCH))

        glass = buildGlass()
        root.addView(glass, FrameLayout.LayoutParams(dp(300), dp(112)).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = dp(30)
            topMargin = dp(300)
        })

        setContentView(root)

        if (autoPlay) root.post { runAutoTour() }
    }

    // ==================== 玻璃 ====================

    private fun buildGlass(): LiquidGlassView {
        val label = TextView(this).apply {
            text = "Liquid Glass"
            textSize = 21f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setShadowLayer(10f, 0f, 2f, 0x66000000)
        }
        return LiquidGlassView(this).apply {
            enableDynamicBackground = true
            useShaderPipeline = true          // AGSL 单遍透镜管线 (API 33+)
            material = GlassMaterial.REGULAR
            cornerRadius = 999f
            // 首屏参数偏“展示向”：折射与色散都拉到肉眼可辨
            refractionHeight = 240f
            bevelWidth = 46f
            dispersionStrength = 0.16f
            blurAmount = 0.055f
            saturation = 150f
            enableEdgeHighlight = true
            enableSensorHighlight = true
            enableAdaptiveTint = true
            addView(label, FrameLayout.LayoutParams(MATCH, MATCH))
            glassAppearanceListener = { overLight ->
                label.setTextColor(if (overLight) 0xDE000000.toInt() else Color.WHITE)
            }
            setOnTouchListener(DragHandler())
        }
    }

    /** 手指拖动玻璃，按下时触发 liquid press 形变 */
    private inner class DragHandler : View.OnTouchListener {
        private var dx = 0f
        private var dy = 0f
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dx = v.x - e.rawX; dy = v.y - e.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    v.x = e.rawX + dx; v.y = e.rawY + dy
                }
            }
            return false // 继续交给 LiquidGlassView 自己的按压逻辑
        }
    }

    /** 自动巡航：绕图标网格走一圈，中途停顿，供 screenrecord 采集 */
    private fun runAutoTour() {
        val w = resources.displayMetrics.widthPixels.toFloat()
        val startX = glass.x
        val startY = glass.y
        val rightX = w - glass.width - dp(46)

        fun move(px: Float, py: Float, ms: Long) = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(glass, View.X, px),
                ObjectAnimator.ofFloat(glass, View.Y, py)
            )
            duration = ms
            interpolator = AccelerateDecelerateInterpolator()
        }

        // 终点必须回到起点，否则 README 的 GIF 循环时会跳帧
        AnimatorSet().apply {
            playSequentially(
                move(rightX, startY + dp(120), 2000),
                move(startX, startY + dp(250), 2000),
                move(rightX, startY + dp(360), 2000),
                move(startX, startY, 2400)
            )
            start()
        }
    }

    // ==================== 桌面图标网格 ====================

    /**
     * 4×6 圆角图标网格。给玻璃提供可被折射的高频边界——
     * 这是整张 hero 图能不能“看出效果”的关键。
     */
    private class HomeScreenGridView(context: Context) : View(context) {

        private val d = context.resources.displayMetrics.density
        private fun dp(v: Float) = v * d

        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xF2FFFFFF.toInt()
            textAlign = Paint.Align.CENTER
            textSize = dp(11f)
            setShadowLayer(dp(3f), 0f, dp(1f), 0x80000000.toInt())
        }
        private val dockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x30FFFFFF }

        // (顶色, 底色, 名称) —— iOS 系统应用配色语言
        private val apps = listOf(
            intArrayOf(0xFF56CCF2.toInt(), 0xFF2D9CDB.toInt()) to "Weather",
            intArrayOf(0xFF7BE495.toInt(), 0xFF27AE60.toInt()) to "Phone",
            intArrayOf(0xFFFFB36B.toInt(), 0xFFF2994A.toInt()) to "Notes",
            intArrayOf(0xFFEB5757.toInt(), 0xFFB33939.toInt()) to "Music",
            intArrayOf(0xFFBB6BD9.toInt(), 0xFF8E44AD.toInt()) to "Photos",
            intArrayOf(0xFF6FCF97.toInt(), 0xFF219653.toInt()) to "Messages",
            intArrayOf(0xFF56CCF2.toInt(), 0xFF2F80ED.toInt()) to "Safari",
            intArrayOf(0xFFF2C94C.toInt(), 0xFFE1A93A.toInt()) to "Camera",
            intArrayOf(0xFFFF8FA3.toInt(), 0xFFE8506B.toInt()) to "Health",
            intArrayOf(0xFF9B9BFF.toInt(), 0xFF5C5CE0.toInt()) to "Calendar",
            intArrayOf(0xFF4ECDC4.toInt(), 0xFF17A398.toInt()) to "Maps",
            intArrayOf(0xFFFFA69E.toInt(), 0xFFE5645B.toInt()) to "Files",
            intArrayOf(0xFF8FD3F4.toInt(), 0xFF3E7CB1.toInt()) to "Mail",
            intArrayOf(0xFFC1E1C1.toInt(), 0xFF6BA368.toInt()) to "Wallet",
            intArrayOf(0xFFFFD6A5.toInt(), 0xFFE0A458.toInt()) to "Clock",
            intArrayOf(0xFFD4A5FF.toInt(), 0xFF9450D8.toInt()) to "Podcasts",
            intArrayOf(0xFF7FDBDA.toInt(), 0xFF2A9D8F.toInt()) to "Reminders",
            intArrayOf(0xFFFFBDBD.toInt(), 0xFFE07A5F.toInt()) to "Books",
            intArrayOf(0xFFA8DADC.toInt(), 0xFF457B9D.toInt()) to "Home",
            intArrayOf(0xFFE9C46A.toInt(), 0xFFCA8A04.toInt()) to "Shortcuts",
            intArrayOf(0xFF90BE6D.toInt(), 0xFF43AA8B.toInt()) to "Fitness",
            intArrayOf(0xFFF4A261.toInt(), 0xFFE76F51.toInt()) to "Stocks",
            intArrayOf(0xFFB5838D.toInt(), 0xFF6D6875.toInt()) to "Settings",
            intArrayOf(0xFF80FFDB.toInt(), 0xFF48BFE3.toInt()) to "Translate"
        )

        private val cols = 4
        private val rows = 6
        private val rect = RectF()
        private val path = Path()

        override fun onDraw(canvas: Canvas) {
            val icon = dp(62f)
            val gapX = (width - cols * icon) / (cols + 1f)
            val top = dp(96f)
            val gapY = dp(30f)

            for (i in 0 until cols * rows) {
                val (colors, name) = apps[i % apps.size]
                val c = i % cols
                val r = i / cols
                val x = gapX + c * (icon + gapX)
                val y = top + r * (icon + gapY + dp(16f))
                rect.set(x, y, x + icon, y + icon)

                fill.shader = LinearGradient(
                    rect.left, rect.top, rect.left, rect.bottom,
                    colors[0], colors[1], Shader.TileMode.CLAMP
                )
                canvas.drawRoundRect(rect, icon * 0.24f, icon * 0.24f, fill)
                fill.shader = null

                drawGlyph(canvas, rect, i)
                canvas.drawText(name, rect.centerX(), rect.bottom + dp(15f), labelPaint)
            }

            // dock
            val dockTop = height - dp(108f)
            rect.set(dp(14f), dockTop, width - dp(14f), height - dp(24f))
            canvas.drawRoundRect(rect, dp(30f), dp(30f), dockPaint)
            val dockIcon = dp(56f)
            val dockGap = (rect.width() - 4 * dockIcon) / 5f
            for (i in 0 until 4) {
                val (colors, _) = apps[(i + 5) % apps.size]
                val x = rect.left + dockGap + i * (dockIcon + dockGap)
                val y = rect.centerY() - dockIcon / 2f
                val ir = RectF(x, y, x + dockIcon, y + dockIcon)
                fill.shader = LinearGradient(
                    ir.left, ir.top, ir.left, ir.bottom,
                    colors[0], colors[1], Shader.TileMode.CLAMP
                )
                canvas.drawRoundRect(ir, dockIcon * 0.24f, dockIcon * 0.24f, fill)
                fill.shader = null
                drawGlyph(canvas, ir, i + 3)
            }
        }

        /** 每个图标里画一个简单白色线条符号——比纯色块提供更多可折射的细节 */
        private fun drawGlyph(canvas: Canvas, r: RectF, seed: Int) {
            glyph.strokeWidth = r.width() * 0.075f
            val cx = r.centerX()
            val cy = r.centerY()
            val s = r.width() * 0.24f
            path.reset()
            when (seed % 6) {
                0 -> { // 圆 + 指针
                    canvas.drawCircle(cx, cy, s, glyph.also { it.style = Paint.Style.STROKE })
                    canvas.drawLine(cx, cy, cx, cy - s * 0.6f, glyph)
                    canvas.drawLine(cx, cy, cx + s * 0.5f, cy, glyph)
                }
                1 -> { // 对勾
                    path.moveTo(cx - s, cy)
                    path.lineTo(cx - s * 0.2f, cy + s * 0.7f)
                    path.lineTo(cx + s, cy - s * 0.7f)
                    canvas.drawPath(path, glyph)
                }
                2 -> { // 三横
                    for (k in -1..1) {
                        val yy = cy + k * s * 0.7f
                        canvas.drawLine(cx - s, yy, cx + s * if (k == 0) 0.3f else 1f, yy, glyph)
                    }
                }
                3 -> { // 三角播放
                    path.moveTo(cx - s * 0.6f, cy - s)
                    path.lineTo(cx + s * 0.9f, cy)
                    path.lineTo(cx - s * 0.6f, cy + s)
                    path.close()
                    canvas.drawPath(path, glyph)
                }
                4 -> { // 方框 + 点
                    canvas.drawRoundRect(
                        RectF(cx - s, cy - s * 0.8f, cx + s, cy + s * 0.8f),
                        s * 0.3f, s * 0.3f, glyph
                    )
                    canvas.drawCircle(cx, cy, s * 0.28f, glyph)
                }
                else -> { // 心形近似
                    canvas.drawCircle(cx - s * 0.45f, cy - s * 0.2f, s * 0.55f, glyph)
                    canvas.drawCircle(cx + s * 0.45f, cy - s * 0.2f, s * 0.55f, glyph)
                    path.moveTo(cx - s * 0.95f, cy + s * 0.05f)
                    path.lineTo(cx, cy + s * 0.9f)
                    path.lineTo(cx + s * 0.95f, cy + s * 0.05f)
                    canvas.drawPath(path, glyph)
                }
            }
        }
    }

    private companion object {
        const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
    }
}
