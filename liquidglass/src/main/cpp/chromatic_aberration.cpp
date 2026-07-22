/**
 * chromatic_aberration.cpp - 色差效果算法实现
 * 
 * 核心算法：
 * 1. 对每个像素，从位移贴图读取位移量（dx, dy）
 * 2. 计算三个颜色通道的采样位置（每个通道有不同的偏移）
 * 3. 使用双线性插值从源图像采样
 * 4. 合并三个通道生成最终像素
 * 
 * 双线性插值原理：
 * - 找到采样点周围的 4 个像素
 * - 根据采样点的小数部分计算权重
 * - 对 4 个像素进行加权平均
 * - 消除最近邻采样的马赛克效果
 * 
 * 性能优化：
 * - 内联函数减少函数调用开销
 * - 缓存友好的内存访问模式
 * - 避免重复计算
 * - 使用快速浮点运算
 * 
 * 时间复杂度：O(W×H)
 * 空间复杂度：O(1)（原位处理）
 */

#include "chromatic_aberration.h"
#include <algorithm>
#include <cmath>
#include <android/log.h>

#define LOG_TAG "ChromaticAberration"
// 热路径日志（JNI 入口每帧调用）：默认编译为空操作，避免每帧字符串
// 格式化 + 日志 I/O 的固定开销；排查问题时定义 NATIVEGAUSS_VERBOSE 打开
#ifdef NATIVEGAUSS_VERBOSE
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#else
#define LOGD(...) ((void)0)
#endif
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/**
 * 双线性插值采样单个颜色通道
 * 
 * @param pixels 像素数据
 * @param width 图像宽度
 * @param height 图像高度
 * @param stride 行跨度（字节数）
 * @param x 采样 X 坐标（可以是小数）
 * @param y 采样 Y 坐标（可以是小数）
 * @param channelOffset 通道偏移（0=B, 1=G, 2=R, 3=A）
 * @return 插值后的通道值（0-255）
 */
static inline uint8_t sample_bilinear_channel(
    const uint8_t* pixels,
    int width,
    int height,
    int stride,
    float x,
    float y,
    int channelOffset
) {
    // 边界检查
    if (x < 0.0f || x >= width - 1.0f || y < 0.0f || y >= height - 1.0f) {
        int clampedX = std::max(0, std::min(width - 1, static_cast<int>(x + 0.5f)));
        int clampedY = std::max(0, std::min(height - 1, static_cast<int>(y + 0.5f)));
        return pixels[clampedY * stride + clampedX * 4 + channelOffset];
    }
    
    // 获取整数部分和小数部分
    int x0 = static_cast<int>(x);
    int y0 = static_cast<int>(y);
    int x1 = std::min(x0 + 1, width - 1);
    int y1 = std::min(y0 + 1, height - 1);
    
    float fx = x - x0;  // X 方向的小数部分（权重）
    float fy = y - y0;  // Y 方向的小数部分（权重）
    
    // 获取 4 个角的像素值
    const uint8_t* row0 = pixels + y0 * stride;
    const uint8_t* row1 = pixels + y1 * stride;
    
    float c00 = row0[x0 * 4 + channelOffset];  // 左上
    float c10 = row0[x1 * 4 + channelOffset];  // 右上
    float c01 = row1[x0 * 4 + channelOffset];  // 左下
    float c11 = row1[x1 * 4 + channelOffset];  // 右下
    
    // 双线性插值
    // 先在 X 方向插值
    float c0 = c00 * (1.0f - fx) + c10 * fx;  // 上边插值
    float c1 = c01 * (1.0f - fx) + c11 * fx;  // 下边插值
    
    // 再在 Y 方向插值
    float result = c0 * (1.0f - fy) + c1 * fy;
    
    // 钳位到 [0, 255]
    return static_cast<uint8_t>(std::max(0.0f, std::min(255.0f, result + 0.5f)));
}

/**
 * 最近邻采样单个颜色通道（高性能版本）
 *
 * @param pixels 像素数据
 * @param width 图像宽度
 * @param height 图像高度
 * @param stride 行跨度（字节数）
 * @param x 采样 X 坐标（可以是小数）
 * @param y 采样 Y 坐标（可以是小数）
 * @param channelOffset 通道偏移（0=B, 1=G, 2=R, 3=A）
 * @return 最近邻像素的通道值（0-255）
 */
static inline uint8_t sample_nearest_channel(
    const uint8_t* pixels,
    int width,
    int height,
    int stride,
    float x,
    float y,
    int channelOffset
) {
    // 四舍五入到最近的整数坐标
    int clampedX = std::max(0, std::min(width - 1, static_cast<int>(x + 0.5f)));
    int clampedY = std::max(0, std::min(height - 1, static_cast<int>(y + 0.5f)));

    return pixels[clampedY * stride + clampedX * 4 + channelOffset];
}

/**
 * 双线性插值采样完整像素（ARGB）
 *
 * @param pixels 像素数据
 * @param width 图像宽度
 * @param height 图像高度
 * @param stride 行跨度（字节数）
 * @param x 采样 X 坐标
 * @param y 采样 Y 坐标
 * @param outB 输出蓝色通道
 * @param outG 输出绿色通道
 * @param outR 输出红色通道
 * @param outA 输出 Alpha 通道
 */
static inline void sample_bilinear_pixel(
    const uint8_t* pixels,
    int width,
    int height,
    int stride,
    float x,
    float y,
    uint8_t& outB,
    uint8_t& outG,
    uint8_t& outR,
    uint8_t& outA
) {
    outB = sample_bilinear_channel(pixels, width, height, stride, x, y, 0);
    outG = sample_bilinear_channel(pixels, width, height, stride, x, y, 1);
    outR = sample_bilinear_channel(pixels, width, height, stride, x, y, 2);
    outA = sample_bilinear_channel(pixels, width, height, stride, x, y, 3);
}

// 主处理函数
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
    float redOffset,
    float greenOffset,
    float blueOffset,
    bool useBilinear
) {
    // 参数校验
    if (!source || !displacement || !result) {
        LOGE("Invalid parameters: null pointer");
        return;
    }

    if (width <= 0 || height <= 0) {
        LOGE("Invalid dimensions: %dx%d", width, height);
        return;
    }

    if (sourceStride < width * 4 || displacementStride < width * 4 || resultStride < width * 4) {
        LOGE("Invalid stride: source=%d, displacement=%d, result=%d, min=%d",
             sourceStride, displacementStride, resultStride, width * 4);
        return;
    }

    // 位移缩放因子（与 Kotlin 实现一致）
    const float scaleFactor = scale / 255.0f;

    // ✅ 修复：Kotlin 层已经将 offset * intensity * downscale，所以这里直接使用传入的值
    // 不需要再乘以 intensity
    const float actualRedOffset = redOffset;
    const float actualGreenOffset = greenOffset;
    const float actualBlueOffset = blueOffset;

    LOGD("Processing %dx%d, intensity=%.2f, scale=%.2f, offsets=(%.3f, %.3f, %.3f), useBilinear=%d",
         width, height, intensity, scale, actualRedOffset, actualGreenOffset, actualBlueOffset, useBilinear);

    // 处理每个像素
    for (int y = 0; y < height; ++y) {
        const uint8_t* displacementRow = displacement + y * displacementStride;
        uint8_t* resultRow = result + y * resultStride;

        for (int x = 0; x < width; ++x) {
            // ✅ 修复：位移贴图实际上是 RGBA 格式，不是 BGRA！
            const uint8_t* mapPixel = displacementRow + x * 4;
            uint8_t mapR = mapPixel[0];  // R 通道 = X 方向位移
            uint8_t mapG = mapPixel[1];  // G 通道 = Y 方向位移
            uint8_t mapB = mapPixel[2];  // B 通道
            uint8_t mapA = mapPixel[3];  // A 通道

            // 计算基础位移（128 为中心点，表示无位移）
            float baseDx = (static_cast<float>(mapR) - 128.0f) * scaleFactor;
            float baseDy = (static_cast<float>(mapG) - 128.0f) * scaleFactor;

            // ✅ 通道偏移沿位移方向应用
            // （旧实现把标量同时加到 x/y 上，等于固定沿 45° 对角线偏移）
            float baseLen = std::sqrt(baseDx * baseDx + baseDy * baseDy);
            float dirX = 0.0f;
            float dirY = 0.0f;
            if (baseLen > 0.001f) {
                dirX = baseDx / baseLen;
                dirY = baseDy / baseLen;
            }

            // 调试：打印中心像素的信息和位移贴图值
            if (x == width / 2 && y == height / 2) {
                LOGD("C++ center pixel: BGRA=(%d,%d,%d,%d), baseDx=%.3f, baseDy=%.3f, offsets=(%.3f, %.3f, %.3f)",
                     mapB, mapG, mapR, mapA, baseDx, baseDy, actualRedOffset, actualGreenOffset, actualBlueOffset);
            }

            // 打印几个边缘像素的位移贴图值
            if ((x == 10 && y == 10) || (x == width - 10 && y == 10) ||
                (x == 10 && y == height - 10) || (x == width - 10 && y == height - 10)) {
                LOGD("C++ edge pixel (%d,%d): BGRA=(%d,%d,%d,%d), baseDx=%.3f, baseDy=%.3f",
                     x, y, mapB, mapG, mapR, mapA, baseDx, baseDy);
            }

            // 计算三个通道的采样位置（每个通道沿位移方向有不同的偏移量）
            // 注意：与 Kotlin 实现完全一致
            float rSrcX = x + baseDx + dirX * actualRedOffset;
            float rSrcY = y + baseDy + dirY * actualRedOffset;
            float gSrcX = x + baseDx + dirX * actualGreenOffset;
            float gSrcY = y + baseDy + dirY * actualGreenOffset;
            float bSrcX = x + baseDx + dirX * actualBlueOffset;
            float bSrcY = y + baseDy + dirY * actualBlueOffset;

            // ✅ 根据设置选择采样方法
            uint8_t r, g, b;
            if (useBilinear) {
                // 双线性插值：高质量，平滑采样，无马赛克
                r = sample_bilinear_channel(source, width, height, sourceStride, rSrcX, rSrcY, 2);  // R 通道
                g = sample_bilinear_channel(source, width, height, sourceStride, gSrcX, gSrcY, 1);  // G 通道
                b = sample_bilinear_channel(source, width, height, sourceStride, bSrcX, bSrcY, 0);  // B 通道
            } else {
                // 最近邻采样：高性能，速度快 2-3 倍
                r = sample_nearest_channel(source, width, height, sourceStride, rSrcX, rSrcY, 2);  // R 通道
                g = sample_nearest_channel(source, width, height, sourceStride, gSrcX, gSrcY, 1);  // G 通道
                b = sample_nearest_channel(source, width, height, sourceStride, bSrcX, bSrcY, 0);  // B 通道
            }

            // Alpha 通道取自原始像素
            const uint8_t* sourcePixel = source + y * sourceStride + x * 4;
            uint8_t alpha = sourcePixel[3];

            // 写入结果（BGRA 格式）
            uint8_t* outPixel = resultRow + x * 4;
            outPixel[0] = b;      // B 通道（从蓝色采样位置的 B 通道）
            outPixel[1] = g;      // G 通道（从绿色采样位置的 G 通道）
            outPixel[2] = r;      // R 通道（从红色采样位置的 R 通道）
            outPixel[3] = alpha;  // A 通道（保持原始 Alpha）
        }
    }
}

// 原位处理版本（简化参数）
void chromatic_aberration_rgba8888_inplace(
    const uint8_t* source,
    const uint8_t* displacement,
    uint8_t* result,
    int width,
    int height,
    int stride,
    float intensity,
    float scale,
    float redOffset,
    float greenOffset,
    float blueOffset,
    bool useBilinear
) {
    chromatic_aberration_rgba8888(
        source, displacement, result,
        width, height,
        stride, stride, stride,
        intensity, scale,
        redOffset, greenOffset, blueOffset,
        useBilinear
    );
}

// ============================================================================
// 色散效果实现（Chromatic Dispersion）
// ============================================================================

/**
 * 色散效果处理 - 基于物理光学原理
 *
 * 核心算法：
 * 1. 读取边缘距离（distanceToEdge）
 * 2. 应用 Snell 定律计算折射强度（edgeFactor）
 * 3. 读取或计算法线方向（normalX, normalY）
 * 4. 计算基础偏移（沿法线方向）
 * 5. 应用色散系数（不同波长的折射率）
 * 6. 分别采样 RGB 三个通道
 *
 * 物理原理：
 * - Snell 定律：n₁ sin(θ₁) = n₂ sin(θ₂)
 * - 不同波长的折射率：N_R = 0.98, N_G = 1.0, N_B = 1.02
 * - 边缘距离越近，折射效果越强
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
    float dpr,
    bool useBilinear
) {
    // 参数校验
    if (!source || !edgeDistance || !result) {
        LOGE("Dispersion: Invalid parameters: null pointer");
        return;
    }

    if (width <= 0 || height <= 0) {
        LOGE("Dispersion: Invalid dimensions: %dx%d", width, height);
        return;
    }

    if (sourceStride < width * 4 || edgeDistanceStride < width * 4 || resultStride < width * 4) {
        LOGE("Dispersion: Invalid stride");
        return;
    }

    // 折射率常数（对应不同波长的光）
    const float N_R = 1.0f - 0.02f;  // 0.98 - 红光
    const float N_G = 1.0f;           // 1.0  - 绿光
    const float N_B = 1.0f + 0.02f;  // 1.02 - 蓝光

    // 中心点坐标（用于计算径向法线）
    const float centerX = width * 0.5f;
    const float centerY = height * 0.5f;

    LOGD("Dispersion: Processing %dx%d, refThickness=%.2f, refFactor=%.2f, refDispersion=%.2f, dpr=%.2f",
         width, height, refThickness, refFactor, refDispersion, dpr);

    // 调试：采样几个关键像素
    bool debugSamples = true;

    // 处理每个像素
    for (int y = 0; y < height; ++y) {
        const uint8_t* edgeRow = edgeDistance + y * edgeDistanceStride;
        uint8_t* resultRow = result + y * resultStride;

        for (int x = 0; x < width; ++x) {
            // 1. 读取边缘距离（归一化到 0-500 范围）
            // 注意：贴图中现在直接存储"到边缘的距离"（边缘=0，中心=255），无需反转
            const uint8_t* edgePixel = edgeRow + x * 4;
            float distanceToEdge = edgePixel[2] / 255.0f * 500.0f;  // 使用 R 通道（BGRA 格式中 index=2）
            float nmerged = distanceToEdge;

            // 2. 计算折射强度（Snell 定律）
            float edgeFactor = 0.0f;
            if (nmerged < refThickness) {
                float x_R_ratio = 1.0f - nmerged / refThickness;
                float thetaI = asinf(powf(x_R_ratio, 2.0f));
                float thetaT = asinf(1.0f / refFactor * sinf(thetaI));
                edgeFactor = -tanf(thetaT - thetaI);

                // 边界检查
                if (edgeFactor < 0.0f) edgeFactor = 0.0f;
            }

            // 3. 读取或计算法线方向
            float normalX, normalY;
            if (normalMap != nullptr) {
                // 从法线贴图读取
                const uint8_t* normalPixel = normalMap + y * normalMapStride + x * 4;
                normalX = (normalPixel[2] / 255.0f) * 2.0f - 1.0f;  // R 通道
                normalY = (normalPixel[1] / 255.0f) * 2.0f - 1.0f;  // G 通道
            } else {
                // 使用径向法线（从中心指向边缘）
                float dx = x - centerX;
                float dy = y - centerY;
                float len = sqrtf(dx * dx + dy * dy);
                if (len > 0.0f) {
                    normalX = dx / len;
                    normalY = dy / len;
                } else {
                    normalX = 0.0f;
                    normalY = 0.0f;
                }
            }

            // 4. 计算基础偏移（沿法线方向）
            // 增大偏移系数，使效果更明显（从 0.05 增加到 5.0，增大 100 倍）
            float aspectRatio = static_cast<float>(height) / static_cast<float>(width);
            float baseOffsetX = -normalX * edgeFactor * 5.0f * dpr * aspectRatio;
            float baseOffsetY = -normalY * edgeFactor * 5.0f * dpr;

            // 5. 应用色散（不同折射率）
            float offsetR_x = baseOffsetX * (1.0f - (N_R - 1.0f) * refDispersion);
            float offsetR_y = baseOffsetY * (1.0f - (N_R - 1.0f) * refDispersion);

            float offsetG_x = baseOffsetX * (1.0f - (N_G - 1.0f) * refDispersion);
            float offsetG_y = baseOffsetY * (1.0f - (N_G - 1.0f) * refDispersion);

            float offsetB_x = baseOffsetX * (1.0f - (N_B - 1.0f) * refDispersion);
            float offsetB_y = baseOffsetY * (1.0f - (N_B - 1.0f) * refDispersion);

            // 6. 采样三个通道
            uint8_t r, g, b;
            if (useBilinear) {
                r = sample_bilinear_channel(source, width, height, sourceStride, x + offsetR_x, y + offsetR_y, 2);
                g = sample_bilinear_channel(source, width, height, sourceStride, x + offsetG_x, y + offsetG_y, 1);
                b = sample_bilinear_channel(source, width, height, sourceStride, x + offsetB_x, y + offsetB_y, 0);
            } else {
                r = sample_nearest_channel(source, width, height, sourceStride, x + offsetR_x, y + offsetR_y, 2);
                g = sample_nearest_channel(source, width, height, sourceStride, x + offsetG_x, y + offsetG_y, 1);
                b = sample_nearest_channel(source, width, height, sourceStride, x + offsetB_x, y + offsetB_y, 0);
            }

            // Alpha 通道取自原始像素
            const uint8_t* sourcePixel = source + y * sourceStride + x * 4;
            uint8_t alpha = sourcePixel[3];

            // 写入结果（BGRA 格式）
            uint8_t* outPixel = resultRow + x * 4;
            outPixel[0] = b;
            outPixel[1] = g;
            outPixel[2] = r;
            outPixel[3] = alpha;

            // 调试：采样边缘、中心和几个关键点
            if (debugSamples) {
                if ((x == 10 && y == 10) || (x == width - 10 && y == 10) ||
                    (x == width / 2 && y == height / 2) ||
                    (x == 10 && y == height - 10) || (x == width - 10 && y == height - 10)) {
                    LOGD("Dispersion pixel (%d,%d): edgeDist=%.2f, edgeFactor=%.2f, offset=(%.2f,%.2f), RGB=(%d,%d,%d)",
                         x, y, distanceToEdge, edgeFactor, baseOffsetX, baseOffsetY, r, g, b);
                }
            }
        }
    }

    debugSamples = false;  // 只打印一次
}

// 原位处理版本（简化参数）
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
    float dpr,
    bool useBilinear
) {
    chromatic_dispersion_rgba8888(
        source, edgeDistance, normalMap, result,
        width, height,
        stride, stride, stride, stride,
        refThickness, refFactor, refDispersion, dpr,
        useBilinear
    );
}

