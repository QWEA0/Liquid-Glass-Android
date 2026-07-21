/**
 * 无障碍与省电降级检测（对应 iOS 的 Reduce Transparency / Reduce Motion / Increase Contrast）
 *
 * Android 没有与 iOS 完全一一对应的系统开关，这里取最接近的信号：
 * - 降低透明度 → 「高对比度文字」(Settings.Secure high_text_contrast_enabled)
 * - 减弱动效   → 「移除动画」(ValueAnimator.areAnimatorsEnabled / animator_duration_scale)
 * - 省电模式   → PowerManager.isPowerSaveMode（降低采样频率、关闭传感器光源）
 *
 * [LiquidGlassView] 在 attach 时查询一次并缓存；宿主可调用
 * [LiquidGlassView.refreshAccessibilityState] 主动刷新。
 */
package com.example.liquidglass

import android.animation.ValueAnimator
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/** 无障碍渲染模式 */
enum class GlassAccessibilityMode {
    /** 跟随系统设置：高对比度文字开启时自动退化为不透明材质 */
    AUTO,

    /** 始终使用完整玻璃效果（忽略系统无障碍设置） */
    FORCE_FULL,

    /** 始终使用不透明降级材质（调试或宿主自行接管无障碍逻辑时使用） */
    FORCE_OPAQUE
}

object GlassAccessibility {

    /**
     * 用户是否偏好降低透明度
     *
     * 以「高对比度文字」为信号：开启它的用户通常也需要更实的背景来保证可读性。
     */
    fun prefersReducedTransparency(context: Context): Boolean {
        return try {
            Settings.Secure.getInt(
                context.contentResolver,
                "high_text_contrast_enabled",
                0
            ) == 1
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 用户是否偏好减弱动效（系统「移除动画」）
     */
    fun prefersReducedMotion(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return !ValueAnimator.areAnimatorsEnabled()
        }
        return try {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) == 0f
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 是否处于省电模式
     */
    fun isPowerSaveMode(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isPowerSaveMode
    }
}
