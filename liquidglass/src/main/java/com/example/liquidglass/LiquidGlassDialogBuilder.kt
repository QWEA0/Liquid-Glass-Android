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
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class LiquidGlassDialogBuilder(
    context: Context,
    themeResId: Int = 0,
    private val cornerRadiusDp: Float = 28f,
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

    fun dismissAnimated() {
        val dialog = alertDialog ?: return
        val glassView = glass
        if (glassView == null || !glassView.isAttachedToWindow) {
            dialog.dismiss()
            return
        }
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
        window.setWindowAnimations(0)
        clearBackgrounds(panel)

        val originalLp = panel.layoutParams
        val glassView = LiquidGlassView(context).apply {
            cornerRadius = cornerRadiusDp * resources.displayMetrics.density
            enableDynamicBackground = true
            backdropSource = context.findActivity()?.findViewById(android.R.id.content)
            layoutParams = originalLp
            alpha = if (animateShow) 0f else 1f
            scaleX = if (animateShow) ENTER_SCALE else 1f
            scaleY = if (animateShow) ENTER_SCALE else 1f
            glassSetup?.invoke(this)
        }
        glass = glassView

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

    private fun clearBackgrounds(view: View) {
        if (view.background != null) view.background = null
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) clearBackgrounds(view.getChildAt(i))
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
    }
}
