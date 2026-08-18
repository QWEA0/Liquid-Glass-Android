/**
 * 圆形玻璃悬浮按钮（FAB）：预置居中图标的 LiquidGlassView
 *
 * 开箱即用——默认 56dp 圆形、按压液态反馈、点击派发都已配好；
 * 开启 enableAdaptiveTint 后图标颜色跟随背景明暗自动切换
 * （显式设置过图标颜色则不再自动切换）。
 *
 * 使用示例：
 * ```xml
 * <com.example.liquidglass.LiquidGlassFab
 *     android:layout_width="wrap_content"
 *     android:layout_height="wrap_content"
 *     android:src="@drawable/ic_add" />
 * ```
 * ```kotlin
 * fab.setOnClickListener { ... }
 * ```
 */
package com.example.liquidglass

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.Gravity
import android.widget.ImageView
import androidx.annotation.DrawableRes

open class LiquidGlassFab @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LiquidGlassView(context, attrs, defStyleAttr) {

    /** 内置图标视图（缩放模式等细节直接拿它设置） */
    val imageView: ImageView = ImageView(context)

    /** 显式设置过图标颜色后不再跟随背景明暗自动切换 */
    private var autoIconTint = true

    /** 图标 drawable */
    var icon: Drawable?
        get() = imageView.drawable
        set(value) {
            imageView.setImageDrawable(value)
        }

    init {
        isClickable = true
        isFocusable = true

        // wrap_content 时的默认尺寸（FrameLayout 测量会尊重 minimum 值）
        minimumWidth = dp(56)
        minimumHeight = dp(56)

        addView(imageView, LayoutParams(dp(24), dp(24), Gravity.CENTER))
        applyAutoIconTint(isOverLightBackground)

        attrs?.let { parseFabAttributes(context, it) }
    }

    private fun parseFabAttributes(context: Context, attrs: AttributeSet) {
        val ta = context.obtainStyledAttributes(attrs, R.styleable.LiquidGlassFab)
        try {
            ta.getDrawable(R.styleable.LiquidGlassFab_android_src)?.let { imageView.setImageDrawable(it) }
            if (ta.hasValue(R.styleable.LiquidGlassFab_glassIconTint)) {
                setIconTint(ta.getColor(R.styleable.LiquidGlassFab_glassIconTint, Color.WHITE))
            }
        } finally {
            ta.recycle()
        }
    }

    /** 按资源 id 设置图标 */
    fun setIconResource(@DrawableRes resId: Int) {
        imageView.setImageResource(resId)
    }

    /** 显式指定图标颜色（同时关闭明暗自动切换） */
    fun setIconTint(color: Int) {
        autoIconTint = false
        imageView.imageTintList = ColorStateList.valueOf(color)
    }

    override fun onAppearanceChanged(isOverLight: Boolean) {
        if (autoIconTint) applyAutoIconTint(isOverLight)
    }

    private fun applyAutoIconTint(isOverLight: Boolean) {
        val color = if (isOverLight) 0xDE000000.toInt() else Color.WHITE
        imageView.imageTintList = ColorStateList.valueOf(color)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
