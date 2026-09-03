/**
 * 玻璃列表项：按 Material 3 Lists 的解剖做的 LiquidGlassView
 *
 * 结构对应 M3 的 list item：leading icon → headline / supporting text → trailing text / icon，
 * 一行 56dp、两行 72dp 的最小高度。玻璃形状由 [position] 决定——分组列表里首行只圆上面
 * 两个角、中间行直角、末行只圆下面两个角，几行叠在一起就是一块带圆角的玻璃面板；
 * 透镜 SDF 跟着形状走，折射和高光落在真正圆的那几个角上。
 *
 * 可展开：给 [expandedView] 塞一个视图，点击整行就会带动画展开 / 收起（玻璃随高度一起长），
 * 尾部图标同时旋转 180°。行本身默认关掉按压缩放和手指凸起——列表行一按就缩小，
 * 行与行之间会露出一条缝。
 *
 * 每一行都是独立的 LiquidGlassView（各自截背景、各跑一遍着色器），适合设置页 / 菜单这类
 * 行数有限的列表，不建议用在无限滚动的信息流里。
 *
 * ```xml
 * <com.example.liquidglass.LiquidGlassListItem
 *     android:layout_width="match_parent"
 *     android:layout_height="wrap_content"
 *     app:glassListPosition="first"
 *     app:glassLeadingIcon="@drawable/ic_wifi"
 *     app:glassHeadline="Wi-Fi"
 *     app:glassSupportingText="Connected" />
 * ```
 * ```kotlin
 * LiquidGlassListItem.applyGroupPositions(rows)   // 按顺序自动分配 first / middle / last
 * row.expandedView = detailView                   // 点击展开
 * ```
 */
package com.example.liquidglass

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

open class LiquidGlassListItem @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LiquidGlassView(context, attrs, defStyleAttr) {

    /** 在分组里的位置：决定圆哪几个角 */
    enum class Position { SINGLE, FIRST, MIDDLE, LAST }

    val leadingImageView: ImageView = ImageView(context)
    val headlineTextView: TextView = TextView(context)
    val supportingTextView: TextView = TextView(context)
    val trailingTextView: TextView = TextView(context)
    val trailingImageView: ImageView = ImageView(context)

    private val container = LinearLayout(context)
    private val row = LinearLayout(context)
    private val textColumn = LinearLayout(context)
    private val expandSection = LinearLayout(context)

    /** 显式设置过文字颜色后不再跟随背景明暗自动切换 */
    private var autoColors = true

    private var expandAnimator: ValueAnimator? = null

    /** 组外沿圆角（px），默认 20dp */
    var groupCornerRadius: Float = dpF(20f)
        set(value) {
            field = value.coerceAtLeast(0f)
            applyShape()
        }

    /** 组内相邻边的圆角（px），默认 0 = 直角拼接 */
    var innerCornerRadius: Float = 0f
        set(value) {
            field = value.coerceAtLeast(0f)
            applyShape()
        }

    var position: Position = Position.SINGLE
        set(value) {
            field = value
            applyShape()
        }

    var headline: CharSequence
        get() = headlineTextView.text
        set(value) {
            headlineTextView.text = value
        }

    var supportingText: CharSequence?
        get() = supportingTextView.text
        set(value) {
            supportingTextView.text = value
            supportingTextView.visibility = if (value.isNullOrEmpty()) View.GONE else View.VISIBLE
            updateMinHeight()
        }

    var trailingText: CharSequence?
        get() = trailingTextView.text
        set(value) {
            trailingTextView.text = value
            trailingTextView.visibility = if (value.isNullOrEmpty()) View.GONE else View.VISIBLE
        }

    var leadingIcon: Drawable?
        get() = leadingImageView.drawable
        set(value) {
            leadingImageView.setImageDrawable(value)
            leadingImageView.visibility = if (value == null) View.GONE else View.VISIBLE
        }

    var trailingIcon: Drawable?
        get() = trailingImageView.drawable
        set(value) {
            trailingImageView.setImageDrawable(value)
            trailingImageView.visibility = if (value == null) View.GONE else View.VISIBLE
        }

    // ==================== 展开 / 收起 ====================

    /**
     * 展开后显示在主行下方的内容；null = 不可展开。
     * 直接换视图即可，已展开状态下会立刻按新内容重新量高度。
     */
    var expandedView: View? = null
        set(value) {
            if (field === value) return
            expandSection.removeAllViews()
            field = value
            if (value != null) {
                expandSection.addView(value, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ))
                if (isExpanded) {
                    expandSection.visibility = View.VISIBLE
                    expandSection.layoutParams.height = LinearLayout.LayoutParams.WRAP_CONTENT
                    requestLayout()
                }
            } else {
                expandSection.visibility = View.GONE
                isExpandedInternal = false
            }
        }

    /** 点击整行是否切换展开（有 [expandedView] 时才生效） */
    var expandOnClick = true

    /** 展开时尾部图标旋转 180°（放个向下的箭头就是标准的展开指示） */
    var rotateTrailingIconOnExpand = true

    /** 展开动画时长（ms） */
    var expandDuration = 280L

    private var isExpandedInternal = false

    val isExpanded: Boolean
        get() = isExpandedInternal

    /** 展开状态变化回调 */
    var onExpandedChanged: ((Boolean) -> Unit)? = null

    fun toggleExpanded(animate: Boolean = true) = setExpanded(!isExpandedInternal, animate)

    fun setExpanded(expanded: Boolean, animate: Boolean = true) {
        if (expandedView == null) return
        if (expanded == isExpandedInternal) return
        isExpandedInternal = expanded
        expandAnimator?.cancel()

        val lp = expandSection.layoutParams
        val startH = if (expandSection.visibility == View.VISIBLE) expandSection.height else 0
        val targetH = if (expanded) measureExpandedHeight() else 0

        if (rotateTrailingIconOnExpand && trailingImageView.visibility == View.VISIBLE) {
            trailingImageView.animate()
                .rotation(if (expanded) 180f else 0f)
                .setDuration(if (animate) expandDuration else 0L)
                .start()
        }

        if (!animate) {
            expandSection.visibility = if (expanded) View.VISIBLE else View.GONE
            lp.height = if (expanded) LinearLayout.LayoutParams.WRAP_CONTENT else 0
            expandSection.layoutParams = lp
            requestLayout()
            onExpandedChanged?.invoke(expanded)
            return
        }

        expandSection.visibility = View.VISIBLE
        lp.height = startH
        expandSection.layoutParams = lp
        expandAnimator = ValueAnimator.ofInt(startH, targetH).apply {
            duration = expandDuration
            // 展开带一点点过冲，像玻璃被撑开；收起就干脆地减速
            interpolator = if (expanded) OvershootInterpolator(0.6f) else DecelerateInterpolator()
            addUpdateListener {
                lp.height = (it.animatedValue as Int).coerceAtLeast(0)
                expandSection.layoutParams = lp
                invalidate()
            }
            doOnEnd {
                if (expanded) {
                    lp.height = LinearLayout.LayoutParams.WRAP_CONTENT
                } else {
                    lp.height = 0
                    expandSection.visibility = View.GONE
                }
                expandSection.layoutParams = lp
                requestLayout()
            }
            start()
        }
        onExpandedChanged?.invoke(expanded)
    }

    private fun measureExpandedHeight(): Int {
        val innerWidth = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        val widthSpec = if (innerWidth > 0) {
            MeasureSpec.makeMeasureSpec(innerWidth, MeasureSpec.EXACTLY)
        } else {
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        }
        // 量 WRAP_CONTENT 的自然高度，量完再把高度记回去（当前值可能是动画中途的定值）
        val lp = expandSection.layoutParams
        val saved = lp.height
        lp.height = LinearLayout.LayoutParams.WRAP_CONTENT
        expandSection.layoutParams = lp
        expandSection.measure(widthSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val h = expandSection.measuredHeight
        lp.height = saved
        expandSection.layoutParams = lp
        return h
    }

    override fun performClick(): Boolean {
        if (expandOnClick && expandedView != null) toggleExpanded(true)
        return super.performClick()
    }

    init {
        isClickable = true
        isFocusable = true
        // 列表行不做按压缩放 / 手指凸起：一按整行缩小，行间会露出缝
        enablePressEffect = false
        // 默认透镜参数是给大面板定的（斜面 40px、折射 200px），一行只有 56–72dp 高，
        // 照搬会让压缩带占掉半行、变成一条硬线；这里按行高收一档，再给边缘一点柔化
        bevelWidth = dpF(14f)
        refractionHeight = dpF(22f)
        edgeSoftness = dpF(3f)
        blurAmount = 0.10f

        val hPad = dp(16)
        container.orientation = LinearLayout.VERTICAL

        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(hPad, dp(12), hPad, dp(12))

        leadingImageView.scaleType = ImageView.ScaleType.CENTER_INSIDE
        leadingImageView.visibility = View.GONE
        row.addView(leadingImageView, LinearLayout.LayoutParams(dp(24), dp(24)).apply {
            marginEnd = dp(16)
        })

        textColumn.orientation = LinearLayout.VERTICAL
        textColumn.gravity = Gravity.CENTER_VERTICAL
        headlineTextView.textSize = 16f
        headlineTextView.maxLines = 1
        // 去掉字体自带的上下留白，单行时标题才和图标在同一条水平线上
        headlineTextView.includeFontPadding = false
        supportingTextView.textSize = 14f
        supportingTextView.includeFontPadding = false
        supportingTextView.maxLines = 2
        supportingTextView.visibility = View.GONE
        textColumn.addView(headlineTextView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        textColumn.addView(supportingTextView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(2) })
        row.addView(textColumn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        trailingTextView.textSize = 12f
        trailingTextView.typeface = Typeface.DEFAULT_BOLD
        trailingTextView.maxLines = 1
        trailingTextView.visibility = View.GONE
        row.addView(trailingTextView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(16) })

        trailingImageView.scaleType = ImageView.ScaleType.CENTER_INSIDE
        trailingImageView.visibility = View.GONE
        row.addView(trailingImageView, LinearLayout.LayoutParams(dp(24), dp(24)).apply {
            marginStart = dp(16)
        })

        container.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        expandSection.orientation = LinearLayout.VERTICAL
        expandSection.setPadding(hPad, 0, hPad, dp(16))
        expandSection.visibility = View.GONE
        container.addView(expandSection, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0
        ))

        addView(container, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        updateMinHeight()
        applyAutoColors(isOverLightBackground)
        applyShape()

        attrs?.let { parseItemAttributes(context, it) }
    }

    private fun parseItemAttributes(context: Context, attrs: AttributeSet) {
        val ta = context.obtainStyledAttributes(attrs, R.styleable.LiquidGlassListItem)
        try {
            ta.getText(R.styleable.LiquidGlassListItem_glassHeadline)?.let { headline = it }
            ta.getText(R.styleable.LiquidGlassListItem_glassSupportingText)?.let { supportingText = it }
            ta.getText(R.styleable.LiquidGlassListItem_glassTrailingText)?.let { trailingText = it }
            val leadRes = ta.getResourceId(R.styleable.LiquidGlassListItem_glassLeadingIcon, 0)
            if (leadRes != 0) leadingIcon = ContextCompat.getDrawable(context, leadRes)
            val trailRes = ta.getResourceId(R.styleable.LiquidGlassListItem_glassTrailingIcon, 0)
            if (trailRes != 0) trailingIcon = ContextCompat.getDrawable(context, trailRes)
            if (ta.hasValue(R.styleable.LiquidGlassListItem_glassGroupCornerRadius)) {
                groupCornerRadius = ta.getDimension(R.styleable.LiquidGlassListItem_glassGroupCornerRadius, groupCornerRadius)
            }
            if (ta.hasValue(R.styleable.LiquidGlassListItem_glassInnerCornerRadius)) {
                innerCornerRadius = ta.getDimension(R.styleable.LiquidGlassListItem_glassInnerCornerRadius, innerCornerRadius)
            }
            position = when (ta.getInt(R.styleable.LiquidGlassListItem_glassListPosition, 0)) {
                1 -> Position.FIRST
                2 -> Position.MIDDLE
                3 -> Position.LAST
                else -> Position.SINGLE
            }
            if (ta.hasValue(R.styleable.LiquidGlassListItem_android_textColor)) {
                setTextColor(ta.getColor(R.styleable.LiquidGlassListItem_android_textColor, Color.WHITE))
            }
        } finally {
            ta.recycle()
        }
    }

    fun setLeadingIconResource(resId: Int) {
        leadingIcon = if (resId == 0) null else ContextCompat.getDrawable(context, resId)
    }

    fun setTrailingIconResource(resId: Int) {
        trailingIcon = if (resId == 0) null else ContextCompat.getDrawable(context, resId)
    }

    /** 标题字号（sp） */
    fun setHeadlineTextSize(sp: Float) {
        headlineTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    }

    /** 显式指定文字/图标颜色（同时关闭明暗自动切换与文字阴影） */
    fun setTextColor(color: Int) {
        autoColors = false
        val secondary = Color.argb(
            (Color.alpha(color) * 0.7f).toInt(), Color.red(color), Color.green(color), Color.blue(color)
        )
        headlineTextView.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
        supportingTextView.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
        headlineTextView.setTextColor(color)
        supportingTextView.setTextColor(secondary)
        trailingTextView.setTextColor(secondary)
        leadingImageView.setColorFilter(color)
        trailingImageView.setColorFilter(secondary)
    }

    override fun onAppearanceChanged(isOverLight: Boolean) {
        if (autoColors) applyAutoColors(isOverLight)
    }

    /** 暗背景：白字加投影；亮背景：深色字去投影（与 LiquidGlassButton 同一套规则） */
    private fun applyAutoColors(isOverLight: Boolean) {
        val primary: Int
        val secondary: Int
        if (isOverLight) {
            primary = 0xDE000000.toInt()
            secondary = 0x99000000.toInt()
            headlineTextView.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
            supportingTextView.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
        } else {
            primary = Color.WHITE
            secondary = 0xB3FFFFFF.toInt()
            headlineTextView.setShadowLayer(8f, 0f, 2f, Color.BLACK)
            supportingTextView.setShadowLayer(8f, 0f, 2f, Color.BLACK)
        }
        headlineTextView.setTextColor(primary)
        supportingTextView.setTextColor(secondary)
        trailingTextView.setTextColor(secondary)
        leadingImageView.setColorFilter(primary)
        trailingImageView.setColorFilter(secondary)
    }

    /**
     * 首行圆上角、末行圆下角、中间直角、单独一行四角全圆；
     * 组内相邻的边设为平边，拼接处不出现各自的透镜边缘
     */
    private fun applyShape() {
        val g = groupCornerRadius
        val i = innerCornerRadius
        when (position) {
            Position.SINGLE -> {
                setCornerRadii(g, g, g, g)
                setFlatEdges(top = false, right = false, bottom = false, left = false)
            }
            Position.FIRST -> {
                setCornerRadii(g, g, i, i)
                setFlatEdges(top = false, right = false, bottom = true, left = false)
            }
            Position.MIDDLE -> {
                setCornerRadii(i, i, i, i)
                setFlatEdges(top = true, right = false, bottom = true, left = false)
            }
            Position.LAST -> {
                setCornerRadii(i, i, g, g)
                setFlatEdges(top = true, right = false, bottom = false, left = false)
            }
        }
    }

    private fun updateMinHeight() {
        minimumHeight = dp(if (supportingTextView.visibility == View.VISIBLE) 72 else 56)
    }

    private inline fun ValueAnimator.doOnEnd(crossinline block: () -> Unit) {
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) = block()
        })
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun dpF(v: Float): Float = v * resources.displayMetrics.density

    companion object {
        /**
         * 按顺序给一组列表项分配位置：一项 = SINGLE，否则首项 FIRST、末项 LAST、其余 MIDDLE。
         * RecyclerView 里在 onBindViewHolder 用 [positionFor] 按 position / itemCount 算。
         */
        @JvmStatic
        fun applyGroupPositions(items: List<LiquidGlassListItem>) {
            val count = items.size
            items.forEachIndexed { index, item -> item.position = positionFor(index, count) }
        }

        /** 单个位置的判定，给 RecyclerView 适配器用 */
        @JvmStatic
        fun positionFor(index: Int, count: Int): Position = when {
            count <= 1 -> Position.SINGLE
            index == 0 -> Position.FIRST
            index == count - 1 -> Position.LAST
            else -> Position.MIDDLE
        }
    }
}
