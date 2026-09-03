/**
 * 玻璃列表组：装 [LiquidGlassListItem] 的竖向 LinearLayout，两种排布
 *
 * - [Style.MERGED]：行与行贴边拼成一块面板——首行圆上角、末行圆下角、中间直角，
 *   只有组的外沿有透镜边缘（iOS 设置页 / M3 分组列表的样子）
 * - [Style.SEPARATED]：每一行都是独立的圆角卡片，行间留 [itemSpacing]
 *
 * 子项加进来 / 拿掉时自动重新分配 position 与间距，切换 [style] 也是。
 * 组本身是透明容器，行默认"捕获直接父容器"在这里拍不到东西，所以背景来源统一
 * 由 [backdropSource] 下发到每一行；[enableDynamicBackground] 同理。
 *
 * 行之间的竖向间距归组管：子项自己的 topMargin 会被覆盖。
 *
 * ```xml
 * <com.example.liquidglass.LiquidGlassListGroup
 *     android:layout_width="match_parent"
 *     android:layout_height="wrap_content"
 *     app:glassListStyle="separated"
 *     app:glassItemSpacing="8dp"
 *     app:backdropSourceId="@id/wallpaper">
 *     <com.example.liquidglass.LiquidGlassListItem ... app:glassHeadline="Wi‑Fi" />
 *     <com.example.liquidglass.LiquidGlassListItem ... app:glassHeadline="Bluetooth" />
 * </com.example.liquidglass.LiquidGlassListGroup>
 * ```
 * ```kotlin
 * group.style = LiquidGlassListGroup.Style.MERGED   // 运行时切换
 * ```
 */
package com.example.liquidglass

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.widget.LinearLayout

open class LiquidGlassListGroup @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    enum class Style { MERGED, SEPARATED }

    var style: Style = Style.MERGED
        set(value) {
            if (field == value) return
            field = value
            applyPositions()
        }

    /** 分离样式的行间距（px），默认 8dp；合并样式下不生效 */
    var itemSpacing: Int = dp(8)
        set(value) {
            field = value.coerceAtLeast(0)
            applyPositions()
        }

    /**
     * 圆角（px）：合并样式是组外沿的圆角，分离样式是每张卡片的圆角。
     * 负值（默认 [AUTO_CORNER]）= 自动：合并 20dp、分离 12dp
     */
    var cornerRadius: Float = AUTO_CORNER
        set(value) {
            field = value
            applyPositions()
        }

    /** 下发给每一行的背景来源；组是透明容器，不指定的话行拍到的是空的 */
    var backdropSource: View? = null
        set(value) {
            field = value
            pendingBackdropSourceId = 0
            forEachItem { it.backdropSource = value }
        }

    /** 下发给每一行的动态背景开关 */
    var enableDynamicBackground: Boolean = false
        set(value) {
            field = value
            forEachItem { it.enableDynamicBackground = value }
        }

    /** XML 里 app:backdropSourceId 指定的 id，挂载后从根视图解析 */
    private var pendingBackdropSourceId = 0

    init {
        orientation = VERTICAL
        attrs?.let { parseGroupAttributes(context, it) }
    }

    private fun parseGroupAttributes(context: Context, attrs: AttributeSet) {
        val ta = context.obtainStyledAttributes(attrs, R.styleable.LiquidGlassListGroup)
        try {
            style = if (ta.getInt(R.styleable.LiquidGlassListGroup_glassListStyle, 0) == 1) {
                Style.SEPARATED
            } else {
                Style.MERGED
            }
            itemSpacing = ta.getDimensionPixelSize(R.styleable.LiquidGlassListGroup_glassItemSpacing, itemSpacing)
            if (ta.hasValue(R.styleable.LiquidGlassListGroup_cornerRadius)) {
                cornerRadius = ta.getDimension(R.styleable.LiquidGlassListGroup_cornerRadius, cornerRadius)
            }
            pendingBackdropSourceId = ta.getResourceId(R.styleable.LiquidGlassListGroup_backdropSourceId, 0)
        } finally {
            ta.recycle()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // 与 LiquidGlassView 同一套：整棵树 inflate 完才能按 id 找到
        if (backdropSource == null && pendingBackdropSourceId != 0) {
            val id = pendingBackdropSourceId
            rootView?.findViewById<View>(id)?.let { backdropSource = it }
                ?: Log.w(TAG, "backdropSourceId 未在视图树中找到")
            pendingBackdropSourceId = 0
        }
    }

    override fun onViewAdded(child: View) {
        super.onViewAdded(child)
        if (child is LiquidGlassListItem) {
            backdropSource?.let { child.backdropSource = it }
            if (enableDynamicBackground) child.enableDynamicBackground = true
        }
        applyPositions()
    }

    override fun onViewRemoved(child: View) {
        super.onViewRemoved(child)
        applyPositions()
    }

    /**
     * 按当前样式重新分配每一行的位置、圆角和间距。
     * 加减子项、改属性时自动调用；改了某一行的 visibility 之后需要手动调一次
     */
    fun applyPositions() {
        val radius = if (cornerRadius >= 0f) {
            cornerRadius
        } else {
            dpF(if (style == Style.MERGED) 20f else 12f)
        }
        var itemCount = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c is LiquidGlassListItem && c.visibility != View.GONE) itemCount++
        }
        var itemIndex = 0
        var visibleIndex = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            val gap = if (visibleIndex == 0 || style == Style.MERGED) 0 else itemSpacing
            val lp = child.layoutParams as? MarginLayoutParams
            if (lp != null && lp.topMargin != gap) {
                lp.topMargin = gap
                child.layoutParams = lp
            }
            if (child is LiquidGlassListItem) {
                val pos = if (style == Style.MERGED) {
                    LiquidGlassListItem.positionFor(itemIndex, itemCount)
                } else {
                    LiquidGlassListItem.Position.SINGLE
                }
                if (child.groupCornerRadius != radius) child.groupCornerRadius = radius
                if (child.position != pos) child.position = pos
                itemIndex++
            }
            visibleIndex++
        }
    }

    private inline fun forEachItem(block: (LiquidGlassListItem) -> Unit) {
        for (i in 0 until childCount) {
            (getChildAt(i) as? LiquidGlassListItem)?.let(block)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun dpF(v: Float): Float = v * resources.displayMetrics.density

    companion object {
        private const val TAG = "LiquidGlassListGroup"

        /** [cornerRadius] 的"自动"值 */
        const val AUTO_CORNER = -1f
    }
}
