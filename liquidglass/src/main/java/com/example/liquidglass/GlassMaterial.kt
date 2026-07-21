/**
 * 玻璃材质变体（对应 Apple Liquid Glass 的 Regular / Clear 两档）
 *
 * - REGULAR：默认材质。较强模糊 + 亮度自适应染色，优先保证前景内容可读性，
 *   适用于按钮、导航栏、卡片等常规控件。
 * - CLEAR：高透材质。弱模糊 + 固定压暗层（dimming layer），不做自适应翻转，
 *   适用于覆盖在照片/视频等媒体内容上、且下层内容本身就是视觉主体的场景。
 *
 * 每档材质携带一组渲染参数，由 [LiquidGlassView.material] 应用到透镜管线。
 */
package com.example.liquidglass

enum class GlassMaterial(
    /** 模糊半径缩放（乘到 blurAmount 计算出的半径上） */
    val blurScale: Float,
    /** Clear 材质的压暗层强度（0-1，保证上层符号可读） */
    val dimAmount: Float,
    /** 是否启用背景亮度自适应染色（Regular 特性） */
    val adaptiveTint: Boolean,
    /** 无自适应时的基础染色（straight-alpha ARGB） */
    val baseTint: Int,
    /** 镜面高光强度缩放 */
    val specBoost: Float,
    /** 内阴影强度（0-1） */
    val innerShadow: Float
) {
    /** 常规材质：强模糊 + 自适应染色，重可读性 */
    REGULAR(
        blurScale = 1.0f,
        dimAmount = 0.0f,
        adaptiveTint = true,
        baseTint = 0x24FFFFFF,
        specBoost = 1.0f,
        innerShadow = 0.55f
    ),

    /** 高透材质：弱模糊 + 固定压暗层，重下层内容 */
    CLEAR(
        blurScale = 0.35f,
        dimAmount = 0.16f,
        adaptiveTint = false,
        baseTint = 0x14FFFFFF,
        specBoost = 1.2f,
        innerShadow = 0.35f
    )
}
