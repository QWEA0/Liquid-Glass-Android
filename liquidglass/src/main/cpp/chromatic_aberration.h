/**
 * chromatic_aberration.h - 色差效果算法（Chromatic Aberration Effect）
 *
 * 功能：
 * 模拟光学色差效果，通过分离 RGB 通道并应用不同程度的位移
 *
 * 算法原理：
 * 1. 分离 RGB 三个颜色通道
 * 2. 对每个通道应用不同程度的位移（基于位移贴图）
 * 3. 支持双线性插值或最近邻采样（可选）
 * 4. 合并三个通道生成最终结果
 *
 * 性能优化：
 * - 使用原位处理，减少内存分配
 * - 支持降采样处理，提升性能
 * - 双线性插值消除马赛克效果（高质量）
 * - 最近邻采样提升性能 2-3 倍（高性能）
 * - 缓存友好的内存访问模式
 *
 * 线程安全：
 * - 函数本身是线程安全的（无全局状态）
 * - 不要对同一块内存并发调用
 * - 不同图像可以在不同线程中并发处理
 *
 * 参考：
 * - 对应 Kotlin 版本：ChromaticAberrationEffect.kt
 * - 算法复杂度：O(W×H)
 *   - 双线性插值：每像素约 30-40 次浮点运算
 *   - 最近邻采样：每像素约 10-15 次浮点运算
 */

#ifndef CHROMATIC_ABERRATION_H
#define CHROMATIC_ABERRATION_H

#include <cstdint>
#include <cstddef>

/**
 * 色差效果处理（RGBA8888 格式）
 *
 * @param source 源图像像素数据（ARGB_8888）
 * @param displacement 位移贴图像素数据（ARGB_8888）
 *                     - R 通道：X 方向位移（0-255，128 为中心）
 *                     - G 通道：Y 方向位移（0-255，128 为中心）
 * @param result 结果图像像素数据（ARGB_8888，可以与 source 相同实现原位处理）
 * @param width 图像宽度
 * @param height 图像高度
 * @param sourceStride 源图像行跨度（字节数）
 * @param displacementStride 位移贴图行跨度（字节数）
 * @param resultStride 结果图像行跨度（字节数）
 * @param intensity 色差强度（0-10，推荐 2.0）
 * @param scale 位移缩放系数（推荐 70.0）
 * @param redOffset 红色通道偏移量（默认 0.0）
 * @param greenOffset 绿色通道偏移量（默认 -0.05）
 * @param blueOffset 蓝色通道偏移量（默认 -0.1）
 * @param useBilinear 是否使用双线性插值（默认 true）
 *                    - true: 双线性插值（高质量，平滑采样，无马赛克）
 *                    - false: 最近邻采样（高性能，速度快 2-3 倍，可能有轻微马赛克）
 *
 * 注意事项：
 * - 所有指针必须有效且指向足够大的内存区域
 * - stride 必须 >= width * 4
 * - 函数会直接修改 result 指向的内存
 * - 双线性插值质量优于最近邻采样，但性能开销更大
 */
void chromatic_aberration_rgba8888(
    const uint8_t* source,
    const uint8_t* displacement,
    uint8_t* result,
    int width,
    int height,
    int sourceStride,
    int displacementStride,
    int resultStride,
    float intensity,
    float scale,
    float redOffset = 0.0f,
    float greenOffset = -0.05f,
    float blueOffset = -0.1f,
    bool useBilinear = true
);

/**
 * 色差效果处理（原位版本，简化参数）
 *
 * 等价于 chromatic_aberration_rgba8888，但假设 source、displacement、result
 * 具有相同的 stride，并且 result 可以与 source 相同
 *
 * @param source 源图像像素数据
 * @param displacement 位移贴图像素数据
 * @param result 结果图像像素数据
 * @param width 图像宽度
 * @param height 图像高度
 * @param stride 行跨度（字节数）
 * @param intensity 色差强度
 * @param scale 位移缩放系数
 * @param redOffset 红色通道偏移量
 * @param greenOffset 绿色通道偏移量
 * @param blueOffset 蓝色通道偏移量
 * @param useBilinear 是否使用双线性插值（默认 true）
 */
void chromatic_aberration_rgba8888_inplace(
    const uint8_t* source,
    const uint8_t* displacement,
    uint8_t* result,
    int width,
    int height,
    int stride,
    float intensity,
    float scale,
    float redOffset = 0.0f,
    float greenOffset = -0.05f,
    float blueOffset = -0.1f,
    bool useBilinear = true
);

/**
 * 色散效果处理（Chromatic Dispersion Effect）- 基于物理光学原理
 *
 * 使用 Snell 定律模拟真实的玻璃色散效果，不同波长的光有不同的折射率
 *
 * @param source 源图像像素数据（ARGB_8888）
 * @param edgeDistance 边缘距离贴图（ARGB_8888）
 *                     - R/G/B 通道：到边缘的距离（0=边缘，255=中心）
 * @param normalMap 法线贴图（ARGB_8888，可选，传 nullptr 使用径向法线）
 *                  - R 通道：法线 X 分量（0-255，128 为 0）
 *                  - G 通道：法线 Y 分量（0-255，128 为 0）
 * @param result 结果图像像素数据（ARGB_8888）
 * @param width 图像宽度
 * @param height 图像高度
 * @param sourceStride 源图像行跨度（字节数）
 * @param edgeDistanceStride 边缘距离贴图行跨度（字节数）
 * @param normalMapStride 法线贴图行跨度（字节数，如果 normalMap 为 nullptr 则忽略）
 * @param resultStride 结果图像行跨度（字节数）
 * @param refThickness 折射厚度（像素，推荐 50-200）
 * @param refFactor 折射系数（推荐 1.2-2.0，玻璃约 1.5）
 * @param refDispersion 色散增益（推荐 0-20，玻璃约 7）
 * @param dpr 设备像素比（默认 1.0）
 * @param useBilinear 是否使用双线性插值（默认 true）
 *
 * 物理原理：
 * - 基于 Snell 定律：n₁ sin(θ₁) = n₂ sin(θ₂)
 * - 不同波长的折射率：N_R = 0.98, N_G = 1.0, N_B = 1.02
 * - 边缘距离越近，折射效果越强
 * - 沿法线方向应用折射偏移
 */
void chromatic_dispersion_rgba8888(
    const uint8_t* source,
    const uint8_t* edgeDistance,
    const uint8_t* normalMap,
    uint8_t* result,
    int width,
    int height,
    int sourceStride,
    int edgeDistanceStride,
    int normalMapStride,
    int resultStride,
    float refThickness,
    float refFactor,
    float refDispersion,
    float dpr = 1.0f,
    bool useBilinear = true
);

/**
 * 色散效果处理（原位版本，简化参数）
 *
 * @param source 源图像像素数据
 * @param edgeDistance 边缘距离贴图
 * @param normalMap 法线贴图（可选，传 nullptr 使用径向法线）
 * @param result 结果图像像素数据
 * @param width 图像宽度
 * @param height 图像高度
 * @param stride 行跨度（字节数）
 * @param refThickness 折射厚度
 * @param refFactor 折射系数
 * @param refDispersion 色散增益
 * @param dpr 设备像素比
 * @param useBilinear 是否使用双线性插值
 */
void chromatic_dispersion_rgba8888_inplace(
    const uint8_t* source,
    const uint8_t* edgeDistance,
    const uint8_t* normalMap,
    uint8_t* result,
    int width,
    int height,
    int stride,
    float refThickness,
    float refFactor,
    float refDispersion,
    float dpr = 1.0f,
    bool useBilinear = true
);

#endif // CHROMATIC_ABERRATION_H

