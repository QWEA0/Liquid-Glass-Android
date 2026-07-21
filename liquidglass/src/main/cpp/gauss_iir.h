/**
 * gauss_iir.h - IIR 递归高斯模糊核心算法
 * 
 * 算法：Deriche / Young-van Vliet 递归高斯滤波器
 * 
 * 理论基础：
 * - 高斯核可以分解为因果（causal）和反因果（anti-causal）IIR 滤波器
 * - 时间复杂度：O(W×H)，与 σ 无关
 * - 空间复杂度：O(max(W,H))，仅需一维工作缓冲
 * 
 * 数值稳定性：
 * - 采用 Deriche 参数化：alpha = 1.695 / σ
 * - 系数计算使用双精度浮点数
 * - 边界条件采用稳态增益补偿，避免能量泄露
 * 
 * 边界处理：
 * - 镜像边界（Mirror）：假设图像在边界外镜像延拓
 * - 稳态初始化：前向/后向递归的初始状态基于边界像素的稳态响应
 * 
 * 性能特性：
 * - 每像素约 40-60 次浮点运算（4 通道）
 * - 内存访问模式：顺序读写，缓存友好
 * - 支持 NEON 向量化（可选）
 * 
 * 参考文献：
 * - Deriche, R. (1993). "Recursively Implementing the Gaussian and its Derivatives"
 * - Young, I.T., van Vliet, L.J. (1995). "Recursive implementation of the Gaussian filter"
 */

#ifndef GAUSS_IIR_H
#define GAUSS_IIR_H

#include <cstdint>
#include <cstddef>

/**
 * IIR 递归高斯模糊（RGBA8888 格式，原位处理）
 * 
 * @param base 像素数据指针（ARGB_8888，预乘 Alpha）
 * @param w 图像宽度
 * @param h 图像高度
 * @param stride 行跨度（字节数），通常为 w * 4，但可能有对齐填充
 * @param sigma 高斯标准差，推荐范围 [0.5, 50.0]
 * @param doLinear 是否在线性色彩空间处理
 *                 - true：sRGB→Linear→去预乘→处理→再预乘→sRGB
 *                 - false：直接在预乘 sRGB 空间处理
 * 
 * 注意事项：
 * - 函数会直接修改 base 指向的内存
 * - sigma ≤ 0.1 时直接返回，不做处理
 * - 内部会分配临时缓冲区，大小为 max(w, h) * 4 * sizeof(float)
 * - 非线程安全，不要对同一块内存并发调用
 */
void gaussian_iir_rgba8888_inplace(
    uint8_t* base,
    int w,
    int h,
    int stride,
    float sigma,
    bool doLinear
);

/**
 * IIR 递归高斯模糊（性能优先版本，跳过色彩空间转换）
 * 
 * 等价于 gaussian_iir_rgba8888_inplace(base, w, h, stride, sigma, false)
 * 提供此函数以便在性能关键路径上减少参数传递开销
 */
void gaussian_iir_rgba8888_fast(
    uint8_t* base,
    int w,
    int h,
    int stride,
    float sigma
);

/**
 * IIR 递归高斯模糊（质量优先版本，线性色彩空间）
 * 
 * 等价于 gaussian_iir_rgba8888_inplace(base, w, h, stride, sigma, true)
 * 提供此函数以便在质量关键路径上明确意图
 */
void gaussian_iir_rgba8888_quality(
    uint8_t* base,
    int w,
    int h,
    int stride,
    float sigma
);

#endif // GAUSS_IIR_H

