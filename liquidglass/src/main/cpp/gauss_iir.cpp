/**
 * gauss_iir.cpp - IIR 递归高斯模糊实现
 * 
 * 系数公式来源：Deriche (1993) 递归高斯滤波器
 * 
 * 核心思想：
 * 高斯核 G(x) = exp(-x²/2σ²) 可以近似为 IIR 滤波器：
 *   y[n] = a0*x[n] + a1*x[n-1] - b1*y[n-1] - b2*y[n-2]  (前向)
 *   y[n] = a2*x[n+1] + a3*x[n+2] - b1*y[n+1] - b2*y[n+2] (后向)
 * 
 * 参数化（Deriche 稳定版本）：
 *   alpha = 1.695 / σ
 *   ema = exp(-alpha)
 *   ema2 = ema * ema
 *   b1 = -2 * ema
 *   b2 = ema2
 *   k = (1 - ema)² / (1 + 2*alpha*ema - ema2)
 *   a0 = k
 *   a1 = k * ema * (alpha - 1)
 *   a2 = k * ema * (alpha + 1)
 *   a3 = -k * ema2
 * 
 * 数值稳定性：
 * - 使用双精度计算系数，单精度执行滤波
 * - alpha 范围 [0.034, 1.695]，对应 σ ∈ [1, 50]
 * - 边界条件采用稳态增益补偿，避免振铃
 * 
 * 时间复杂度：O(W×H)，每像素约 12 次乘法 + 8 次加法（单通道）
 * 空间复杂度：O(max(W,H))，仅需一维工作缓冲
 */

#include "gauss_iir.h"
#include <cmath>
#include <cstring>
#include <algorithm>
#include <android/log.h>

#define LOG_TAG "GaussIIR"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// sRGB ↔ Linear 转换（快速近似）
static inline float srgb_to_linear(float srgb) {
    // 精确公式：srgb <= 0.04045 ? srgb/12.92 : pow((srgb+0.055)/1.055, 2.4)
    // 快速近似：pow(srgb, 2.2)，误差 < 2%
    return srgb * srgb * (srgb * 0.2 + 0.8f); // 更快的 2.2 次方近似
}

static inline float linear_to_srgb(float linear) {
    // 精确公式：linear <= 0.0031308 ? linear*12.92 : 1.055*pow(linear,1/2.4)-0.055
    // 快速近似：pow(linear, 1/2.2)
    float x = sqrtf(linear);
    return x * (1.0f - 0.2f * linear); // 快速 1/2.2 次方近似
}

// Deriche 系数结构
struct DericheCoeffs {
    float a0, a1, a2, a3; // 前向/后向系数
    float b1, b2;         // 递归系数
    float coefp, coefn;   // 边界增益补偿
};

/**
 * 计算 Deriche IIR 滤波器系数
 * 
 * @param sigma 高斯标准差
 * @return 滤波器系数
 */
static DericheCoeffs compute_deriche_coeffs(float sigma) {
    DericheCoeffs c;
    
    // Deriche 参数化（双精度计算）
    double alpha = 1.695 / sigma;
    double ema = exp(-alpha);
    double ema2 = ema * ema;
    
    // 递归系数
    c.b1 = static_cast<float>(-2.0 * ema);
    c.b2 = static_cast<float>(ema2);
    
    // 归一化因子
    double k = (1.0 - ema) * (1.0 - ema) / (1.0 + 2.0 * alpha * ema - ema2);
    
    // 前向/后向系数
    c.a0 = static_cast<float>(k);
    c.a1 = static_cast<float>(k * ema * (alpha - 1.0));
    c.a2 = static_cast<float>(k * ema * (alpha + 1.0));
    c.a3 = static_cast<float>(-k * ema2);
    
    // 边界增益补偿（稳态响应）
    c.coefp = static_cast<float>((c.a0 + c.a1) / (1.0 + c.b1 + c.b2));
    c.coefn = static_cast<float>((c.a2 + c.a3) / (1.0 + c.b1 + c.b2));
    
    return c;
}

/**
 * 一维 IIR 递归滤波（单通道）
 * 
 * @param src 源数据
 * @param dst 目标数据
 * @param len 数据长度
 * @param c 滤波器系数
 */
static void iir_filter_1d(const float* src, float* dst, int len, const DericheCoeffs& c) {
    if (len <= 0) return;
    
    // 前向递归（causal）
    float yp1 = src[0] * c.coefp; // 稳态初始化
    float yp2 = yp1;
    float xp1 = src[0];

    for (int i = 0; i < len; ++i) {
        float xc = src[i];
        float yc = c.a0 * xc + c.a1 * xp1 - c.b1 * yp1 - c.b2 * yp2;

        dst[i] = yc; // 暂存前向结果

        xp1 = xc;
        yp2 = yp1; yp1 = yc;
    }
    
    // 后向递归（anti-causal）
    float yn1 = src[len - 1] * c.coefn; // 稳态初始化
    float yn2 = yn1;
    float xn1 = src[len - 1];
    float xn2 = xn1;
    
    for (int i = len - 1; i >= 0; --i) {
        float xc = src[i];
        float yc = c.a2 * xn1 + c.a3 * xn2 - c.b1 * yn1 - c.b2 * yn2;
        
        dst[i] += yc; // 累加后向结果
        
        xn2 = xn1; xn1 = xc;
        yn2 = yn1; yn1 = yc;
    }
}

/**
 * 横向模糊（处理所有行）
 */
static void blur_horizontal(
    uint8_t* base,
    int w, int h, int stride,
    const DericheCoeffs& c,
    float* buffer,
    bool doLinear
) {
    for (int y = 0; y < h; ++y) {
        uint8_t* row = base + y * stride;
        
        // 加载到浮点缓冲（分离 RGBA 通道）
        for (int x = 0; x < w; ++x) {
            uint8_t b = row[x * 4 + 0];
            uint8_t g = row[x * 4 + 1];
            uint8_t r = row[x * 4 + 2];
            uint8_t a = row[x * 4 + 3];
            
            float fa = a / 255.0f;
            float fr = r / 255.0f;
            float fg = g / 255.0f;
            float fb = b / 255.0f;
            
            if (doLinear) {
                // 去预乘 + sRGB→Linear
                if (fa > 0.001f) {
                    fr = srgb_to_linear(fr / fa);
                    fg = srgb_to_linear(fg / fa);
                    fb = srgb_to_linear(fb / fa);
                } else {
                    fr = fg = fb = 0.0f;
                }
            }
            
            buffer[x] = fr;
            buffer[w + x] = fg;
            buffer[2 * w + x] = fb;
            buffer[3 * w + x] = fa;
        }
        
        // 对每个通道执行 IIR 滤波
        iir_filter_1d(buffer, buffer, w, c);                  // R
        iir_filter_1d(buffer + w, buffer + w, w, c);          // G
        iir_filter_1d(buffer + 2 * w, buffer + 2 * w, w, c);  // B
        iir_filter_1d(buffer + 3 * w, buffer + 3 * w, w, c);  // A
        
        // 写回像素
        for (int x = 0; x < w; ++x) {
            float fr = buffer[x];
            float fg = buffer[w + x];
            float fb = buffer[2 * w + x];
            float fa = buffer[3 * w + x];
            
            // 钳位
            fa = std::max(0.0f, std::min(1.0f, fa));
            
            if (doLinear) {
                // Linear→sRGB + 再预乘
                fr = linear_to_srgb(fr) * fa;
                fg = linear_to_srgb(fg) * fa;
                fb = linear_to_srgb(fb) * fa;
            }
            
            // 钳位并转换为 uint8
            int r = static_cast<int>(std::max(0.0f, std::min(255.0f, fr * 255.0f + 0.5f)));
            int g = static_cast<int>(std::max(0.0f, std::min(255.0f, fg * 255.0f + 0.5f)));
            int b = static_cast<int>(std::max(0.0f, std::min(255.0f, fb * 255.0f + 0.5f)));
            int a = static_cast<int>(fa * 255.0f + 0.5f);
            
            row[x * 4 + 0] = static_cast<uint8_t>(b);
            row[x * 4 + 1] = static_cast<uint8_t>(g);
            row[x * 4 + 2] = static_cast<uint8_t>(r);
            row[x * 4 + 3] = static_cast<uint8_t>(a);
        }
    }
}

/**
 * 纵向模糊（处理所有列）
 */
static void blur_vertical(
    uint8_t* base,
    int w, int h, int stride,
    const DericheCoeffs& c,
    float* buffer,
    bool doLinear
) {
    for (int x = 0; x < w; ++x) {
        // 加载列到浮点缓冲
        for (int y = 0; y < h; ++y) {
            uint8_t* pixel = base + y * stride + x * 4;
            uint8_t b = pixel[0];
            uint8_t g = pixel[1];
            uint8_t r = pixel[2];
            uint8_t a = pixel[3];
            
            float fa = a / 255.0f;
            float fr = r / 255.0f;
            float fg = g / 255.0f;
            float fb = b / 255.0f;
            
            if (doLinear) {
                if (fa > 0.001f) {
                    fr = srgb_to_linear(fr / fa);
                    fg = srgb_to_linear(fg / fa);
                    fb = srgb_to_linear(fb / fa);
                } else {
                    fr = fg = fb = 0.0f;
                }
            }
            
            buffer[y] = fr;
            buffer[h + y] = fg;
            buffer[2 * h + y] = fb;
            buffer[3 * h + y] = fa;
        }
        
        // 对每个通道执行 IIR 滤波
        iir_filter_1d(buffer, buffer, h, c);
        iir_filter_1d(buffer + h, buffer + h, h, c);
        iir_filter_1d(buffer + 2 * h, buffer + 2 * h, h, c);
        iir_filter_1d(buffer + 3 * h, buffer + 3 * h, h, c);
        
        // 写回列
        for (int y = 0; y < h; ++y) {
            float fr = buffer[y];
            float fg = buffer[h + y];
            float fb = buffer[2 * h + y];
            float fa = buffer[3 * h + y];
            
            fa = std::max(0.0f, std::min(1.0f, fa));
            
            if (doLinear) {
                fr = linear_to_srgb(fr) * fa;
                fg = linear_to_srgb(fg) * fa;
                fb = linear_to_srgb(fb) * fa;
            }
            
            int r = static_cast<int>(std::max(0.0f, std::min(255.0f, fr * 255.0f + 0.5f)));
            int g = static_cast<int>(std::max(0.0f, std::min(255.0f, fg * 255.0f + 0.5f)));
            int b = static_cast<int>(std::max(0.0f, std::min(255.0f, fb * 255.0f + 0.5f)));
            int a = static_cast<int>(fa * 255.0f + 0.5f);
            
            uint8_t* pixel = base + y * stride + x * 4;
            pixel[0] = static_cast<uint8_t>(b);
            pixel[1] = static_cast<uint8_t>(g);
            pixel[2] = static_cast<uint8_t>(r);
            pixel[3] = static_cast<uint8_t>(a);
        }
    }
}

// 主入口函数
void gaussian_iir_rgba8888_inplace(
    uint8_t* base,
    int w, int h, int stride,
    float sigma,
    bool doLinear
) {
    // 参数校验
    if (!base || w <= 0 || h <= 0 || stride < w * 4) {
        LOGE("Invalid parameters: base=%p, w=%d, h=%d, stride=%d", base, w, h, stride);
        return;
    }
    
    // sigma 太小，直接返回
    if (sigma <= 0.1f) {
        return;
    }
    
    // sigma 太大，钳位到安全范围
    if (sigma > 50.0f) {
        LOGD("Sigma %.2f too large, clamping to 50.0", sigma);
        sigma = 50.0f;
    }
    
    // 计算滤波器系数
    DericheCoeffs c = compute_deriche_coeffs(sigma);
    
    // 分配工作缓冲（4 通道 × 最大维度）
    int maxDim = std::max(w, h);
    float* buffer = new float[maxDim * 4];
    
    // 横向模糊
    blur_horizontal(base, w, h, stride, c, buffer, doLinear);
    
    // 纵向模糊
    blur_vertical(base, w, h, stride, c, buffer, doLinear);
    
    // 释放缓冲
    delete[] buffer;
}

// 便捷函数
void gaussian_iir_rgba8888_fast(uint8_t* base, int w, int h, int stride, float sigma) {
    gaussian_iir_rgba8888_inplace(base, w, h, stride, sigma, false);
}

void gaussian_iir_rgba8888_quality(uint8_t* base, int w, int h, int stride, float sigma) {
    gaussian_iir_rgba8888_inplace(base, w, h, stride, sigma, true);
}

