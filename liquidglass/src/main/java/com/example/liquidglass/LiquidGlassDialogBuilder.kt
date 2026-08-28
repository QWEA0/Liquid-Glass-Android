/**
 * 玻璃弹窗构建器：把 Material 弹窗的整个面板塞进 LiquidGlassView
 *
 * 开箱即用——圆角、进出场动画、跨 window 背景采样、文字明暗自适应都已配好，
 * 其余用法与 MaterialAlertDialogBuilder 完全一致。
 *
 * 注意：库对 appcompat / material 是 compileOnly 依赖（不强制传递给使用方），
 * 用到这个类时使用方需要自己引入这两个库。
 *
 * 使用示例：
 * ```kotlin
 * LiquidGlassDialogBuilder(this)
 *     .setTitle("Liquid Glass")
 *     .setMessage("弹窗面板整个套在玻璃里，背景来自 Activity")
 *     .setPositiveButton("OK", null)
 *     .setNegativeButton("Cancel", null)
 *     .show()
 * ```
 */
package com.example.liquidglass

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class LiquidGlassDialogBuilder @JvmOverloads constructor(
    context: Context,
    themeResId: Int = 0,
    private val cornerRadiusDp: Float = 28f,
    private val glassBlurAmount: Float = DEFAULT_BLUR,
    private val animateShow: Boolean = true,
    private val dimBehind: Boolean = false,
    private val glassSetup: (LiquidGlassView.() -> Unit)? = null
) : MaterialAlertDialogBuilder(context, themeResId) {

    var glass: LiquidGlassView? = null
        private set

    private var alertDialog: AlertDialog? = null

    override fun create(): AlertDialog {
        val dialog = super.create()
        alertDialog = dialog
        val decor = dialog.window?.decorView
        if (decor != null) {
            decor.viewTreeObserver.addOnPreDrawListener(
                object : ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        decor.viewTreeObserver.takeIf { it.isAlive }
                            ?.removeOnPreDrawListener(this)
                        installGlass(dialog)
                        return glass == null
                    }
                }
            )
        }
        return dialog
    }

    /** 缩放淡出后再关闭；返回键 / 点击外部 / 按钮走的是 window 动画，见 [installGlass] */
    fun dismissAnimated() {
        val dialog = alertDialog ?: return
        val glassView = glass
        if (glassView == null || !glassView.isAttachedToWindow) {
            dialog.dismiss()
            return
        }
        // view 层已经在做缩放淡出，再叠一层 window 淡出会显得拖沓
        dialog.window?.setWindowAnimations(0)
        glassView.animate()
            .alpha(0f)
            .scaleX(EXIT_SCALE)
            .scaleY(EXIT_SCALE)
            .setDuration(EXIT_DURATION_MS)
            .setInterpolator(AccelerateInterpolator(1.4f))
            .setUpdateListener { glassView.invalidate() }
            .withEndAction { dialog.dismiss() }
            .start()
    }

    private fun installGlass(dialog: AlertDialog) {
        val window = dialog.window ?: return
        val content = window.decorView.findViewById<ViewGroup>(android.R.id.content) ?: return
        val panel = content.getChildAt(0) ?: return
        if (panel is LiquidGlassView) return

        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.setDimAmount(if (dimBehind) DEFAULT_DIM else 0f)
        // 进场交给 view 树自己做：默认的 window 动画会缩放/平移整个 window 的 surface，
        // window 内的 view 树全程不重绘，玻璃只能按动画前的偏移采样，折射被冻住。
        // 退场换成纯 alpha 的 window 动画——不动 surface 的位置，且返回键、点击外部、
        // 按钮、dismiss() 所有关闭路径都能盖到（只有 dismissAnimated 会另行关掉它）
        window.setWindowAnimations(R.style.Animation_LiquidGlass_Dialog)
        clearPanelBackgrounds(panel)

        val originalLp = panel.layoutParams
        val glassView = object : LiquidGlassView(context) {
            override fun onAppearanceChanged(isOverLight: Boolean) {
                applyTextColors(panel, isOverLight)
            }
        }.apply {
            cornerRadius = cornerRadiusDp * resources.displayMetrics.density
            enableDynamicBackground = true
            // 弹窗面积大、背景细节多，要靠模糊和自适应染色给文字垫出对比度。
            // 默认的 blurAmount 0.0625 换算出来半径只有 6px，几乎不糊，
            // 文字会直接压在背景的图标和文字上（PR #8 的原始版本就是这个问题）
            material = GlassMaterial.REGULAR
            enableAdaptiveTint = true
            blurAmount = glassBlurAmount
            backdropSource = context.findActivity()?.findViewById(android.R.id.content)
            layoutParams = originalLp
            alpha = if (animateShow) 0f else 1f
            scaleX = if (animateShow) ENTER_SCALE else 1f
            scaleY = if (animateShow) ENTER_SCALE else 1f
            glassSetup?.invoke(this)
        }
        glass = glassView
        // 关掉后不再持有玻璃和 dialog：backdropSource 连着 Activity 的 content view
        glassView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                glass = null
                alertDialog = null
            }
        })

        val innerLp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        (originalLp as? ViewGroup.MarginLayoutParams)?.let {
            innerLp.setMargins(it.leftMargin, it.topMargin, it.rightMargin, it.bottomMargin)
        }

        content.removeViewAt(0)
        glassView.addView(panel, innerLp)
        content.addView(glassView)
        // 自适应染色要等亮度采样跑完才回调，先按当前判定上一次色，避免首帧是主题色
        applyTextColors(panel, glassView.isOverLightBackground)
        runEnterAnimation()
    }

    private fun runEnterAnimation() {
        val glassView = glass ?: return
        if (!animateShow) {
            glassView.alpha = 1f
            glassView.scaleX = 1f
            glassView.scaleY = 1f
            return
        }
        glassView.post {
            glassView.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(ENTER_DURATION_MS)
                .setInterpolator(DecelerateInterpolator(1.6f))
                .setUpdateListener { glassView.invalidate() }
                .start()
        }
    }

    /**
     * 只清容器的底色：弹窗的圆角白底画在 parentPanel / topPanel 这些容器上。
     * 递归到叶子会把按钮的 ripple、分割线、输入框下划线一起抹掉，所以跳过非容器
     */
    private fun clearPanelBackgrounds(view: View) {
        if (view !is ViewGroup) return
        if (view.background != null) view.background = null
        for (i in 0 until view.childCount) clearPanelBackgrounds(view.getChildAt(i))
    }

    /** 暗背景白字加投影、亮背景深色字去投影，与 [LiquidGlassButton] 的处理保持一致 */
    private fun applyTextColors(panel: View, isOverLight: Boolean) {
        val color = if (isOverLight) OVER_LIGHT_TEXT else Color.WHITE
        val targets = listOfNotNull(
            panel.findViewById<TextView>(androidx.appcompat.R.id.alertTitle),
            panel.findViewById<TextView>(android.R.id.message),
            panel.findViewById<TextView>(android.R.id.button1),
            panel.findViewById<TextView>(android.R.id.button2),
            panel.findViewById<TextView>(android.R.id.button3)
        )
        targets.forEach { tv ->
            tv.setTextColor(color)
            if (isOverLight) {
                tv.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
            } else {
                tv.setShadowLayer(8f, 0f, 2f, Color.BLACK)
            }
        }
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

    private companion object {
        const val ENTER_SCALE = 0.86f
        const val EXIT_SCALE = 0.88f
        const val ENTER_DURATION_MS = 250L
        const val EXIT_DURATION_MS = 150L
        const val DEFAULT_DIM = 0.32f

        /** 半径 ≈ 4 + 0.6 × 32 ≈ 23px，弹窗这么大一块要这个量级才糊得住背景细节 */
        const val DEFAULT_BLUR = 0.6f
        const val OVER_LIGHT_TEXT = 0xDE000000.toInt()
    }
}
