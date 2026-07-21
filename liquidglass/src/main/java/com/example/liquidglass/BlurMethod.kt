/**
 * 模糊方法枚举
 *
 * 定义 LiquidGlass 组件支持的所有模糊算法
 */
package com.example.liquidglass

/**
 * 模糊算法类型
 */
enum class BlurMethod {
    /**
     * 传统 Box Blur（盒式模糊 - Kotlin 实现）
     * - 使用 AdvancedFastBlur 实现
     * - 降采样策略，性能较好
     * - 质量一般，适合实时预览
     */
    BOX_BLUR,

    /**
     * C++ Box Blur（盒式模糊 - C++ 实现）
     * - 使用 C++ 原生实现
     * - 降采样 + 双线性插值
     * - 性能优于 Kotlin 版本
     * - 质量一般，适合实时预览
     */
    BOX_BLUR_CPP,

    /**
     * IIR 递归高斯模糊（标量版本）
     * - Deriche 算法实现
     * - O(W×H) 复杂度，与 σ 无关
     * - 质量高，性能中等
     */
    IIR_GAUSSIAN,

    /**
     * IIR 递归高斯模糊（NEON 优化版本）
     * - ARM NEON SIMD 向量化
     * - 仅在支持 NEON 的设备上可用
     * - 质量高，性能最佳
     */
    IIR_GAUSSIAN_NEON,

    /**
     * Box3 快速模糊
     * - 3 次盒式模糊近似高斯
     * - 性能优于 IIR，质量略低
     * - 适合小图和实时场景
     */
    BOX3,

    /**
     * 智能选择（推荐）
     * - 根据图像尺寸和模糊强度自动选择最优算法
     * - 小图 → Box3
     * - NEON 可用 → IIR NEON
     * - 其他 → IIR 标量
     */
    SMART,

    /**
     * 下采样管线（强模糊推荐）
     * - 下采样 → 模糊 → 上采样
     * - 适合强模糊场景（σ > 15）
     * - 性能提升 2-5×
     */
    DOWNSAMPLE
}

