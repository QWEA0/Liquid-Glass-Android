/**
 * boxblur.cpp - 三次盒式模糊实现
 * 
 * 算法：积分图优化的盒式模糊
 * 
 * 核心思想：
 * 盒式模糊是均值滤波器，可以通过积分图（Summed Area Table）在 O(1) 时间内计算任意矩形区域的和。
 * 
 * 一维盒式模糊：
 *   dst[i] = sum(src[i-r] ... src[i+r]) / (2*r+1)
 * 
 * 积分图优化：
 *   integral[i] = sum(src[0] ... src[i])
 *   sum(src[i-r] ... src[i+r]) = integral[i+r] - integral[i-r-1]
 * 
 * 二维分离：
 *   先横向模糊，再纵向模糊（可分离卷积）
 * 
 * 三次迭代：
 *   根据中心极限定理，三次盒式模糊的结果趋近于高斯分布
 *   等效 σ ≈ sqrt(radius² * 3 / 12) ≈ radius / 2
 * 
 * 时间复杂度：O(W×H)，每像素约 4 次加法 + 1 次除法（单通道单次）
 * 空间复杂度：O(W×H)，需要一个临时缓冲区
 */

#include "boxblur.h"
#include <cstring>
#include <algorithm>
#include <android/log.h>

#define LOG_TAG "BoxBlur"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/**
 * 一维盒式模糊（横向）
 */
static void box_blur_h(
    const uint8_t* src,
    uint8_t* dst,
    int w, int h, int stride,
    int radius
) {
    int diameter = 2 * radius + 1;
    float inv = 1.0f / diameter;
    
    for (int y = 0; y < h; ++y) {
        const uint8_t* srcRow = src + y * stride;
        uint8_t* dstRow = dst + y * stride;
        
        // 初始化累加器（前 radius+1 个像素）
        int sumR = 0, sumG = 0, sumB = 0, sumA = 0;
        
        // 左边界：复制第一个像素
        for (int i = -radius; i <= radius; ++i) {
            int x = std::max(0, std::min(w - 1, i));
            sumB += srcRow[x * 4 + 0];
            sumG += srcRow[x * 4 + 1];
            sumR += srcRow[x * 4 + 2];
            sumA += srcRow[x * 4 + 3];
        }
        
        // 滑动窗口
        for (int x = 0; x < w; ++x) {
            // 输出当前窗口的平均值
            dstRow[x * 4 + 0] = static_cast<uint8_t>(sumB * inv + 0.5f);
            dstRow[x * 4 + 1] = static_cast<uint8_t>(sumG * inv + 0.5f);
            dstRow[x * 4 + 2] = static_cast<uint8_t>(sumR * inv + 0.5f);
            dstRow[x * 4 + 3] = static_cast<uint8_t>(sumA * inv + 0.5f);
            
            // 移除左边像素，添加右边像素
            int xLeft = std::max(0, x - radius);
            int xRight = std::min(w - 1, x + radius + 1);
            
            sumB += srcRow[xRight * 4 + 0] - srcRow[xLeft * 4 + 0];
            sumG += srcRow[xRight * 4 + 1] - srcRow[xLeft * 4 + 1];
            sumR += srcRow[xRight * 4 + 2] - srcRow[xLeft * 4 + 2];
            sumA += srcRow[xRight * 4 + 3] - srcRow[xLeft * 4 + 3];
        }
    }
}

/**
 * 一维盒式模糊（纵向）
 */
static void box_blur_v(
    const uint8_t* src,
    uint8_t* dst,
    int w, int h, int stride,
    int radius
) {
    int diameter = 2 * radius + 1;
    float inv = 1.0f / diameter;
    
    for (int x = 0; x < w; ++x) {
        // 初始化累加器
        int sumR = 0, sumG = 0, sumB = 0, sumA = 0;
        
        // 上边界：复制第一个像素
        for (int i = -radius; i <= radius; ++i) {
            int y = std::max(0, std::min(h - 1, i));
            const uint8_t* pixel = src + y * stride + x * 4;
            sumB += pixel[0];
            sumG += pixel[1];
            sumR += pixel[2];
            sumA += pixel[3];
        }
        
        // 滑动窗口
        for (int y = 0; y < h; ++y) {
            // 输出当前窗口的平均值
            uint8_t* dstPixel = dst + y * stride + x * 4;
            dstPixel[0] = static_cast<uint8_t>(sumB * inv + 0.5f);
            dstPixel[1] = static_cast<uint8_t>(sumG * inv + 0.5f);
            dstPixel[2] = static_cast<uint8_t>(sumR * inv + 0.5f);
            dstPixel[3] = static_cast<uint8_t>(sumA * inv + 0.5f);
            
            // 移除上边像素，添加下边像素
            int yTop = std::max(0, y - radius);
            int yBottom = std::min(h - 1, y + radius + 1);
            
            const uint8_t* topPixel = src + yTop * stride + x * 4;
            const uint8_t* bottomPixel = src + yBottom * stride + x * 4;
            
            sumB += bottomPixel[0] - topPixel[0];
            sumG += bottomPixel[1] - topPixel[1];
            sumR += bottomPixel[2] - topPixel[2];
            sumA += bottomPixel[3] - topPixel[3];
        }
    }
}

/**
 * 单次盒式模糊（横向 + 纵向）
 */
void box_blur_single_pass(
    const uint8_t* src,
    uint8_t* dst,
    int w, int h, int stride,
    int radius
) {
    // 分配临时缓冲区
    uint8_t* temp = new uint8_t[h * stride];
    
    // 横向模糊：src → temp
    box_blur_h(src, temp, w, h, stride, radius);
    
    // 纵向模糊：temp → dst
    box_blur_v(temp, dst, w, h, stride, radius);
    
    delete[] temp;
}

/**
 * 三次盒式模糊（主入口）
 */
void box3_rgba8888_inplace(
    uint8_t* base,
    int w, int h, int stride,
    int radius
) {
    // 参数校验
    if (!base || w <= 0 || h <= 0 || stride < w * 4) {
        LOGE("Invalid parameters: base=%p, w=%d, h=%d, stride=%d", base, w, h, stride);
        return;
    }
    
    // radius 太小，直接返回
    if (radius <= 0) {
        return;
    }
    
    // radius 太大，钳位到安全范围
    if (radius > 50) {
        LOGD("Radius %d too large, clamping to 50", radius);
        radius = 50;
    }
    
    // 分配临时缓冲区（用于乒乓缓冲）
    uint8_t* temp = new uint8_t[h * stride];
    
    // 第一次模糊：base → temp
    box_blur_single_pass(base, temp, w, h, stride, radius);
    
    // 第二次模糊：temp → base
    box_blur_single_pass(temp, base, w, h, stride, radius);
    
    // 第三次模糊：base → temp → base
    box_blur_single_pass(base, temp, w, h, stride, radius);
    memcpy(base, temp, h * stride);
    
    delete[] temp;
}

/**
 * 最近邻插值降采样（快速版本）
 *
 * 优势：
 * - 速度快 5-10 倍（无浮点运算）
 * - 代码简单
 * - 由于后续会模糊，质量损失可接受
 */
static void downsample_nearest(
    const uint8_t* src,
    uint8_t* dst,
    int srcWidth, int srcHeight, int srcStride,
    int dstWidth, int dstHeight, int dstStride
) {
    float scaleX = static_cast<float>(srcWidth) / dstWidth;
    float scaleY = static_cast<float>(srcHeight) / dstHeight;

    for (int y = 0; y < dstHeight; ++y) {
        int srcY = static_cast<int>(y * scaleY);
        srcY = std::min(srcY, srcHeight - 1);

        const uint8_t* srcRow = src + srcY * srcStride;
        uint8_t* dstRow = dst + y * dstStride;

        for (int x = 0; x < dstWidth; ++x) {
            int srcX = static_cast<int>(x * scaleX);
            srcX = std::min(srcX, srcWidth - 1);

            // 直接复制像素（4 字节 RGBA）
            const uint8_t* srcPixel = srcRow + srcX * 4;
            uint8_t* dstPixel = dstRow + x * 4;

            dstPixel[0] = srcPixel[0];
            dstPixel[1] = srcPixel[1];
            dstPixel[2] = srcPixel[2];
            dstPixel[3] = srcPixel[3];
        }
    }
}

/**
 * 双线性插值降采样（高质量版本）
 *
 * 优势：
 * - 质量好，平滑无锯齿
 * - 适合需要高质量的场景
 *
 * 缺点：
 * - 速度较慢（大量浮点运算）
 */
static void downsample_bilinear(
    const uint8_t* src,
    uint8_t* dst,
    int srcWidth, int srcHeight, int srcStride,
    int dstWidth, int dstHeight, int dstStride
) {
    float scaleX = static_cast<float>(srcWidth) / dstWidth;
    float scaleY = static_cast<float>(srcHeight) / dstHeight;

    for (int y = 0; y < dstHeight; ++y) {
        for (int x = 0; x < dstWidth; ++x) {
            // 计算源图像中的浮点坐标
            float srcX = (x + 0.5f) * scaleX - 0.5f;
            float srcY = (y + 0.5f) * scaleY - 0.5f;

            // 钳位到有效范围
            srcX = std::max(0.0f, std::min(srcX, srcWidth - 1.0f));
            srcY = std::max(0.0f, std::min(srcY, srcHeight - 1.0f));

            // 获取四个邻近像素的坐标
            int x0 = static_cast<int>(srcX);
            int y0 = static_cast<int>(srcY);
            int x1 = std::min(x0 + 1, srcWidth - 1);
            int y1 = std::min(y0 + 1, srcHeight - 1);

            // 计算插值权重
            float wx = srcX - x0;
            float wy = srcY - y0;

            // 获取四个邻近像素
            const uint8_t* p00 = src + y0 * srcStride + x0 * 4;
            const uint8_t* p10 = src + y0 * srcStride + x1 * 4;
            const uint8_t* p01 = src + y1 * srcStride + x0 * 4;
            const uint8_t* p11 = src + y1 * srcStride + x1 * 4;

            // 双线性插值（每个通道）
            uint8_t* dstPixel = dst + y * dstStride + x * 4;
            for (int c = 0; c < 4; ++c) {
                float v0 = p00[c] * (1 - wx) + p10[c] * wx;
                float v1 = p01[c] * (1 - wx) + p11[c] * wx;
                float v = v0 * (1 - wy) + v1 * wy;
                dstPixel[c] = static_cast<uint8_t>(v + 0.5f);
            }
        }
    }
}

/**
 * 最近邻插值上采样（快速版本）
 */
static void upsample_nearest(
    const uint8_t* src,
    uint8_t* dst,
    int srcWidth, int srcHeight, int srcStride,
    int dstWidth, int dstHeight, int dstStride
) {
    // 与降采样相同的逻辑
    downsample_nearest(src, dst, srcWidth, srcHeight, srcStride, dstWidth, dstHeight, dstStride);
}

/**
 * 双线性插值上采样（高质量版本）
 */
static void upsample_bilinear(
    const uint8_t* src,
    uint8_t* dst,
    int srcWidth, int srcHeight, int srcStride,
    int dstWidth, int dstHeight, int dstStride
) {
    // 与降采样相同的逻辑
    downsample_bilinear(src, dst, srcWidth, srcHeight, srcStride, dstWidth, dstHeight, dstStride);
}

/**
 * AdvancedFastBlur 风格的 Box Blur（降采样优化）
 */
void advanced_box_blur_rgba8888(
    const uint8_t* src,
    uint8_t* dst,
    int width,
    int height,
    int stride,
    float radius,
    float downscale
) {
    // 参数校验
    if (!src || !dst || width <= 0 || height <= 0 || stride < width * 4) {
        LOGE("Invalid parameters: src=%p, dst=%p, w=%d, h=%d, stride=%d", src, dst, width, height, stride);
        return;
    }

    // 钳位参数
    downscale = std::max(0.01f, std::min(1.0f, downscale));
    radius = std::max(0.0f, std::min(25.0f, radius));

    // 如果半径太小，直接复制
    if (radius < 0.5f) {
        if (src != dst) {
            for (int y = 0; y < height; ++y) {
                memcpy(dst + y * stride, src + y * stride, width * 4);
            }
        }
        return;
    }

    // 计算降采样后的尺寸
    int smallWidth = std::max(1, static_cast<int>(width * downscale + 0.5f));
    int smallHeight = std::max(1, static_cast<int>(height * downscale + 0.5f));
    int smallStride = smallWidth * 4;

    LOGD("AdvancedBoxBlur: %dx%d -> %dx%d (scale=%.2f), radius=%.1f",
         width, height, smallWidth, smallHeight, downscale, radius);

    // 分配临时缓冲区
    uint8_t* smallImage = new uint8_t[smallHeight * smallStride];
    uint8_t* blurredSmall = new uint8_t[smallHeight * smallStride];

    // 1. 降采样（使用最近邻插值 - 快速版本）
    // 注意：由于后续会模糊，最近邻插值的质量损失可以接受
    downsample_nearest(src, smallImage, width, height, stride, smallWidth, smallHeight, smallStride);

    // 2. 在小图上模糊（调整半径）
    float scaledRadius = radius * downscale;
    int intRadius = std::max(1, static_cast<int>(scaledRadius + 0.5f));

    // 使用单次 Box Blur（而不是三次，以匹配 AdvancedFastBlur 的行为）
    box_blur_single_pass(smallImage, blurredSmall, smallWidth, smallHeight, smallStride, intRadius);

    // 3. 上采样回原尺寸（使用最近邻插值 - 快速版本）
    upsample_nearest(blurredSmall, dst, smallWidth, smallHeight, smallStride, width, height, stride);

    // 释放临时缓冲区
    delete[] smallImage;
    delete[] blurredSmall;
}

/**
 * AdvancedFastBlur 风格的 Box Blur（降采样优化 - 高质量版本）
 */
void advanced_box_blur_rgba8888_hq(
    const uint8_t* src,
    uint8_t* dst,
    int width,
    int height,
    int stride,
    float radius,
    float downscale
) {
    // 参数校验
    if (!src || !dst || width <= 0 || height <= 0 || stride < width * 4) {
        LOGE("Invalid parameters: src=%p, dst=%p, w=%d, h=%d, stride=%d", src, dst, width, height, stride);
        return;
    }

    // 钳位参数
    downscale = std::max(0.01f, std::min(1.0f, downscale));
    radius = std::max(0.0f, std::min(25.0f, radius));

    // 如果半径太小，直接复制
    if (radius < 0.5f) {
        if (src != dst) {
            for (int y = 0; y < height; ++y) {
                memcpy(dst + y * stride, src + y * stride, width * 4);
            }
        }
        return;
    }

    // 计算降采样后的尺寸
    int smallWidth = std::max(1, static_cast<int>(width * downscale + 0.5f));
    int smallHeight = std::max(1, static_cast<int>(height * downscale + 0.5f));
    int smallStride = smallWidth * 4;

    LOGD("AdvancedBoxBlur HQ: %dx%d -> %dx%d (scale=%.2f), radius=%.1f",
         width, height, smallWidth, smallHeight, downscale, radius);

    // 分配临时缓冲区
    uint8_t* smallImage = new uint8_t[smallHeight * smallStride];
    uint8_t* blurredSmall = new uint8_t[smallHeight * smallStride];

    // 1. 降采样（使用双线性插值 - 高质量）
    downsample_bilinear(src, smallImage, width, height, stride, smallWidth, smallHeight, smallStride);

    // 2. 在小图上模糊（调整半径）
    float scaledRadius = radius * downscale;
    int intRadius = std::max(1, static_cast<int>(scaledRadius + 0.5f));

    // 使用单次 Box Blur（而不是三次，以匹配 AdvancedFastBlur 的行为）
    box_blur_single_pass(smallImage, blurredSmall, smallWidth, smallHeight, smallStride, intRadius);

    // 3. 上采样回原尺寸（使用双线性插值 - 高质量）
    upsample_bilinear(blurredSmall, dst, smallWidth, smallHeight, smallStride, width, height, stride);

    // 释放临时缓冲区
    delete[] smallImage;
    delete[] blurredSmall;
}

