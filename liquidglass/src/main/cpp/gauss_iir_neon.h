/**
 * gauss_iir_neon.h - IIR 递归高斯模糊 NEON 向量化实现
 * 
 * 优化策略：
 * - 使用 ARM NEON SIMD 指令并行处理 4 个通道（RGBA）
 * - 向量化浮点运算，减少指令数
 * - 优化内存访问模式，提高缓存命中率
 * 
 * 性能提升：
 * - 预期加速比：2-4× (相比标量实现)
 * - 主要来源：
 *   1. SIMD 并行处理 4 个通道
 *   2. 减少循环开销
 *   3. 更好的指令流水线利用
 * 
 * 兼容性：
 * - ARMv7 (armeabi-v7a): 需要 NEON 支持（大部分设备支持）
 * - ARMv8 (arm64-v8a): 默认支持 NEON
 * - 运行时检测：使用 android_getCpuFeatures() 检测 NEON 支持
 * 
 * 使用建议：
 * - 优先使用 NEON 版本（如果硬件支持）
 * - 小图（< 64×64）可能标量版本更快（SIMD 开销）
 * - 大图（> 128×128）NEON 优势明显
 */

#ifndef GAUSS_IIR_NEON_H
#define GAUSS_IIR_NEON_H

#include <cstdint>
#include <cstddef>

/**
 * 检测当前设备是否支持 NEON
 * 
 * @return true 如果支持 NEON，false 否则
 */
bool has_neon_support();

/**
 * IIR 递归高斯模糊（NEON 优化版本）
 * 
 * @param base 像素数据指针（ARGB_8888，预乘 Alpha）
 * @param w 图像宽度
 * @param h 图像高度
 * @param stride 行跨度（字节数）
 * @param sigma 高斯标准差
 * @param doLinear 是否在线性色彩空间处理
 * 
 * 注意：
 * - 仅在支持 NEON 的设备上调用
 * - 调用前应使用 has_neon_support() 检测
 * - 如果不支持 NEON，应回退到标量版本
 */
void gaussian_iir_rgba8888_neon(
    uint8_t* base,
    int w,
    int h,
    int stride,
    float sigma,
    bool doLinear
);

/**
 * IIR 递归高斯模糊（NEON 优化，性能优先）
 * 
 * 等价于 gaussian_iir_rgba8888_neon(base, w, h, stride, sigma, false)
 */
void gaussian_iir_rgba8888_neon_fast(
    uint8_t* base,
    int w,
    int h,
    int stride,
    float sigma
);

/**
 * IIR 递归高斯模糊（NEON 优化，质量优先）
 * 
 * 等价于 gaussian_iir_rgba8888_neon(base, w, h, stride, sigma, true)
 */
void gaussian_iir_rgba8888_neon_quality(
    uint8_t* base,
    int w,
    int h,
    int stride,
    float sigma
);

#endif // GAUSS_IIR_NEON_H

