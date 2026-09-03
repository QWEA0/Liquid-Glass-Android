/**
 * 玻璃 Toast：画在 Activity window 里的短消息条
 *
 * 不走系统 [Toast]：Toast 是独立 window，玻璃在里面采不到下方的界面；而且 API 30 起
 * 自定义 Toast 视图已废弃、应用在后台时不再显示。这里把玻璃直接挂到 Activity 的
 * content view 上，背景就是它底下的整个界面，淡入、停留、淡出后自动移除，不拦截触摸。
 * 同一时刻只保留一条，再次 show 会顶掉上一条。
 *
 * API 沿用 Toast 的习惯：
 * ```kotlin
 * LiquidGlassToast.makeText(this, "Saved", LiquidGlassToast.LENGTH_SHORT).show()
 * LiquidGlassToast.makeText(this, R.string.done, LiquidGlassToast.LENGTH_LONG)
 *     .setIconResource(R.drawable.ic_check)
 *     .setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, 24)
 *     .show()
 * ```
 * ```java
 * LiquidGlassToast.makeText(activity, "Saved", LiquidGlassToast.LENGTH_SHORT).show();
 * ```
 * 需要 Activity 的 Context：Fragment 里传 requireActivity()，View 里传 view.context。
 */
package com.example.liquidglass

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class LiquidGlassToast private constructor(private val activity: Activity) {

    // 先于 glass 初始化：玻璃的明暗回调会碰它们
    val textView: TextView = TextView(activity)
    val imageView: ImageView = ImageView(activity)

    /** 玻璃本体，透镜参数在这上面调 */
    val glass: LiquidGlassView = ToastGlassView(activity)

    /** [LENGTH_SHORT] / [LENGTH_LONG]，与 [Toast] 的常量相同 */
    var duration: Int = LENGTH_SHORT

    /** 自定义停留时长（ms）；大于 0 时覆盖 [duration] */
    var durationMillis: Long = 0L

    private var gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
    private var xOffset = 0
    private var yOffset = dp(64)

    /** 显式设置过文字颜色后不再跟随背景明暗自动切换 */
    private var autoColors = true
    private var iconTintEnabled = true

    private val handler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { cancel() }
    private var host: FrameLayout? = null
    private var dismissing = false

    init {
        // 默认透镜参数是给大面板定的，一条 44dp 高的消息条照搬会只剩压缩带
        glass.apply {
            cornerRadius = 999f
            bevelWidth = dpF(14f)
            refractionHeight = dpF(22f)
            edgeSoftness = dpF(3f)
            blurAmount = 0.25f
            enableAdaptiveTint = true
            enableDynamicBackground = true
            enablePressEffect = false
            isClickable = false
            isFocusable = false
            minimumHeight = dp(44)
        }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        imageView.scaleType = ImageView.ScaleType.CENTER_INSIDE
        imageView.visibility = View.GONE
        row.addView(imageView, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(10) })
        textView.textSize = 14f
        textView.maxLines = 2
        textView.ellipsize = TextUtils.TruncateAt.END
        textView.includeFontPadding = false
        textView.maxWidth = dp(320)
        row.addView(textView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        glass.addView(row, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER
        ))
        glass.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                // Activity 先销毁了：定时器和静态引用都不该再留着
                handler.removeCallbacks(hideRunnable)
                if (current === this@LiquidGlassToast) current = null
            }
        })
        applyAutoColors(glass.isOverLightBackground)
    }

    fun setText(text: CharSequence): LiquidGlassToast {
        textView.text = text
        return this
    }

    fun setText(@StringRes resId: Int): LiquidGlassToast = setText(activity.getText(resId))

    /** 文字左侧的小图标，默认跟文字同色；彩色图标（比如应用图标）配 [setIconTintEnabled] false */
    fun setIcon(icon: Drawable?): LiquidGlassToast {
        imageView.setImageDrawable(icon)
        imageView.visibility = if (icon == null) View.GONE else View.VISIBLE
        applyIconTint()
        return this
    }

    fun setIconResource(@DrawableRes resId: Int): LiquidGlassToast =
        setIcon(if (resId == 0) null else ContextCompat.getDrawable(activity, resId))

    fun setIconTintEnabled(enabled: Boolean): LiquidGlassToast {
        iconTintEnabled = enabled
        applyIconTint()
        return this
    }

    /** 与 [Toast.setGravity] 同义：偏移量为 px，从 gravity 指定的那条边算起 */
    fun setGravity(gravity: Int, xOffset: Int, yOffset: Int): LiquidGlassToast {
        this.gravity = gravity
        this.xOffset = xOffset
        this.yOffset = yOffset
        return this
    }

    fun setDuration(duration: Int): LiquidGlassToast {
        this.duration = duration
        return this
    }

    /** 显式指定文字/图标颜色（同时关闭明暗自动切换与文字阴影） */
    fun setTextColor(color: Int): LiquidGlassToast {
        autoColors = false
        textView.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
        textView.setTextColor(color)
        applyIconTint()
        return this
    }

    fun show(): LiquidGlassToast {
        val host = this.host ?: resolveHost().also { this.host = it }
        // 边距要按已布局的 host 算（伸进系统栏多少）；还没布局就等一帧
        if (!host.isLaidOut) {
            host.post { show() }
            return this
        }
        current?.takeIf { it !== this }?.cancel(animate = false)
        current = this
        handler.removeCallbacks(hideRunnable)
        dismissing = false

        val baseY = applyLayoutParams(host)
        if (glass.parent == null) {
            host.addView(glass)
            animateIn(baseY)
        } else {
            glass.animate().cancel()
            glass.alpha = 1f
            glass.scaleX = 1f
            glass.scaleY = 1f
            glass.translationY = baseY
        }
        glass.announceForAccessibility(textView.text)
        handler.postDelayed(hideRunnable, effectiveDurationMillis())
        return this
    }

    /** 淡出后移除；没在显示时无事发生 */
    fun cancel() = cancel(animate = true)

    private fun cancel(animate: Boolean) {
        handler.removeCallbacks(hideRunnable)
        if (current === this) current = null
        if (glass.parent == null) return
        if (!animate || !glass.isAttachedToWindow) {
            detach()
            return
        }
        if (dismissing) return
        dismissing = true
        glass.animate()
            .alpha(0f)
            .scaleX(EXIT_SCALE)
            .scaleY(EXIT_SCALE)
            .setDuration(EXIT_MS)
            .setInterpolator(AccelerateInterpolator(1.2f))
            .setUpdateListener { glass.invalidate() }
            .withEndAction { detach() }
            .start()
    }

    private fun detach() {
        glass.animate().cancel()
        (glass.parent as? ViewGroup)?.removeView(glass)
        dismissing = false
    }

    private fun effectiveDurationMillis(): Long = when {
        durationMillis > 0L -> durationMillis
        duration == LENGTH_LONG -> LONG_MS
        else -> SHORT_MS
    }

    /**
     * 按 gravity / 偏移生成布局参数；host 铺到系统栏底下时（edge-to-edge），
     * 把被系统栏盖住的那段补进 margin。返回竖直居中时用的 translationY 基线
     */
    private fun applyLayoutParams(host: FrameLayout): Float {
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, gravity
        )
        val edge = dp(16)
        lp.leftMargin = edge
        lp.rightMargin = edge
        var baseY = 0f
        val (coveredTop, coveredBottom) = coveredInsets(host)
        when (gravity and Gravity.VERTICAL_GRAVITY_MASK) {
            Gravity.TOP -> lp.topMargin = yOffset + coveredTop
            Gravity.BOTTOM -> lp.bottomMargin = yOffset + coveredBottom
            else -> baseY = yOffset.toFloat()
        }
        val absolute = Gravity.getAbsoluteGravity(gravity, host.layoutDirection)
        when (absolute and Gravity.HORIZONTAL_GRAVITY_MASK) {
            Gravity.LEFT -> lp.leftMargin = edge + xOffset
            Gravity.RIGHT -> lp.rightMargin = edge + xOffset
            else -> glass.translationX = xOffset.toFloat()
        }
        glass.layoutParams = lp
        return baseY
    }

    /** host 顶边伸进状态栏、底边伸进导航栏的像素数 */
    private fun coveredInsets(host: View): Pair<Int, Int> {
        val decor = activity.window.decorView
        val insets = ViewCompat.getRootWindowInsets(decor)
            ?.getInsets(WindowInsetsCompat.Type.systemBars())
            ?: return 0 to 0
        val loc = IntArray(2)
        host.getLocationInWindow(loc)
        val top = (insets.top - loc[1]).coerceAtLeast(0)
        val bottom = (loc[1] + host.height - (decor.height - insets.bottom)).coerceAtLeast(0)
        return top to bottom
    }

    private fun animateIn(baseY: Float) {
        // 从所在的那条边滑进来一点：底部向上、顶部向下、居中向上
        val slide = when (gravity and Gravity.VERTICAL_GRAVITY_MASK) {
            Gravity.TOP -> -dpF(24f)
            else -> dpF(24f)
        }
        glass.alpha = 0f
        glass.scaleX = ENTER_SCALE
        glass.scaleY = ENTER_SCALE
        glass.translationY = baseY + slide
        glass.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationY(baseY)
            .setDuration(ENTER_MS)
            .setInterpolator(DecelerateInterpolator(1.6f))
            // 位移中每帧重录：玻璃按屏幕坐标采背景，不重绘的话折射会拖着走
            .setUpdateListener { glass.invalidate() }
            .withEndAction(null)
            .start()
    }

    /** content view 一般就是 FrameLayout；不是的话退到 DecorView（它一定是） */
    private fun resolveHost(): FrameLayout {
        val decor = activity.window.decorView
        val content = decor.findViewById<View>(android.R.id.content)
        return content as? FrameLayout
            ?: decor as? FrameLayout
            ?: throw IllegalStateException("LiquidGlassToast: no FrameLayout host in the activity window")
    }

    private fun applyIconTint() {
        if (!iconTintEnabled) {
            imageView.clearColorFilter()
            return
        }
        imageView.setColorFilter(textView.currentTextColor)
    }

    /** 暗背景：白字加投影；亮背景：深色字去投影（与其他小部件同一套规则） */
    private fun applyAutoColors(isOverLight: Boolean) {
        if (isOverLight) {
            textView.setTextColor(0xDE000000.toInt())
            textView.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
        } else {
            textView.setTextColor(Color.WHITE)
            textView.setShadowLayer(8f, 0f, 2f, Color.BLACK)
        }
        applyIconTint()
    }

    /** 不拦截触摸、前景明暗跟随背景 */
    private inner class ToastGlassView(context: Context) : LiquidGlassView(context) {
        override fun onTouchEvent(event: MotionEvent): Boolean = false

        override fun onAppearanceChanged(isOverLight: Boolean) {
            if (autoColors) applyAutoColors(isOverLight)
        }
    }

    private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()
    private fun dpF(v: Float): Float = v * activity.resources.displayMetrics.density

    companion object {
        const val LENGTH_SHORT = Toast.LENGTH_SHORT
        const val LENGTH_LONG = Toast.LENGTH_LONG

        private const val SHORT_MS = 2000L
        private const val LONG_MS = 3500L
        private const val ENTER_MS = 260L
        private const val EXIT_MS = 180L
        private const val ENTER_SCALE = 0.94f
        private const val EXIT_SCALE = 0.94f

        private var current: LiquidGlassToast? = null

        @JvmStatic
        @JvmOverloads
        fun makeText(context: Context, text: CharSequence, duration: Int = LENGTH_SHORT): LiquidGlassToast =
            LiquidGlassToast(findActivity(context)).apply {
                setText(text)
                this.duration = duration
            }

        @JvmStatic
        @JvmOverloads
        fun makeText(context: Context, @StringRes resId: Int, duration: Int = LENGTH_SHORT): LiquidGlassToast =
            makeText(context, context.getText(resId), duration)

        /** 立刻开始收掉正在显示的那条 */
        @JvmStatic
        fun cancelCurrent() {
            current?.cancel()
        }

        private fun findActivity(context: Context): Activity {
            var c: Context? = context
            while (c != null) {
                if (c is Activity) return c
                c = (c as? ContextWrapper)?.baseContext
            }
            throw IllegalArgumentException(
                "LiquidGlassToast needs an Activity context: the toast is drawn inside the " +
                    "activity window so the glass can refract the content beneath it"
            )
        }
    }
}
