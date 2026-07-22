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

    private companion object {
        const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
    }
}
