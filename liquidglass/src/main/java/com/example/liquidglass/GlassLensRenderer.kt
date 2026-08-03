/**
 * API 33+ 统一透镜着色器渲染器（Liquid Glass 2.0 核心）
 *
 * 用单个 AGSL RuntimeShader 完成 Apple Liquid Glass 的完整光学模型，
 * 取代"位移贴图 + 多个独立效果"的旧架构：
 *
 *   实时圆角矩形 SDF（随 cornerRadius/尺寸实时变化，支持双形状 smin 液态融合）
 *     → 斜面厚度剖面（bevelWidth 可调的"玻璃厚度"）
 *     → 屏幕空间法线（SDF 数值梯度）
 *     → 折射（沿法线向外采样 → 边缘出现背景压缩带，透镜感的来源）
 *     → 色散（三通道折射率不同 → 边缘光谱边纹）
 *     → 镜面高光（dot(N, L)，光源方向由重力传感器驱动）
 *     → 内阴影（背光侧，制造厚度）
 *     → 自适应染色 / Clear 压暗层 / 饱和度
 *
 * 管线：backdrop 录制（带外扩边距）→ RenderEffect 模糊 → 本着色器 → 输出。
 * 形状覆盖率由 SDF 抗锯齿输出（形状外 alpha=0），无需 canvas 裁剪。
 *
 * 相比 CPU 管线：零 Bitmap 分配、零像素回读、背景是"活"的。
 * 相比旧 GPU 管线（HardwareBackdropBlur）：折射几何与真实形状一致、
 * 色散沿法线方向（旧实现是标量广播导致的 45° 对角偏移）、多出高光/内阴影/融合。
 */
package com.example.liquidglass

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.Log
import android.view.View
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class GlassLensRenderer {

    companion object {
        private const val TAG = "GlassLensRenderer"

        fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

        /**
         * 透镜 AGSL 着色器
         *
         * 坐标约定：
         * - coord 为录制内容（含 margin 外扩）的像素坐标
         * - p = coord - margin 为视图局部坐标，形状参数均在此空间
         */
        private const val LENS_AGSL = """
            uniform shader content;
            uniform float  margin;
            uniform float2 viewSize;
            uniform float4 shape1;
            uniform float  radius1;
            uniform float4 shape2;
            uniform float  radius2;
            uniform float  blendK;
            uniform float  bevel;
            uniform float  refractPx;
            uniform float  dispersion;
            uniform float2 lightDir;
            uniform float  specStrength;
            uniform float  innerShadow;
            uniform float4 tintColor;
            uniform float  adaptiveTint;
            uniform float  dimAmount;
            uniform float  satFactor;
            uniform float  press;
            uniform float2 touchPos;
            uniform float  touchAmp;

            float sdRoundedBox(float2 p, float2 b, float r) {
                float2 q = abs(p) - b + r;
                return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - r;
            }

            float sminPoly(float a, float b, float k) {
                float h = clamp(0.5 + 0.5 * (b - a) / k, 0.0, 1.0);
                return mix(b, a, h) - k * h * (1.0 - h);
            }

            float sceneSDF(float2 p) {
                float d = sdRoundedBox(p - shape1.xy, shape1.zw, radius1);
                if (shape2.z > 0.5) {
                    float d2 = sdRoundedBox(p - shape2.xy, shape2.zw, radius2);
                    if (blendK > 0.5) {
                        d = sminPoly(d, d2, blendK);
                    } else {
                        d = min(d, d2);
                    }
                }
                return d;
            }

            half4 main(float2 coord) {
                float2 p = coord - float2(margin, margin);
                float d = sceneSDF(p);

                // 覆盖率：1.5px 抗锯齿羽化，形状外完全透明
                float cov = clamp(0.5 - d / 1.5, 0.0, 1.0);
                if (cov <= 0.004) {
                    return half4(0.0);
                }

                // SDF 数值梯度 → 屏幕空间外法线
                float2 n = float2(
                    sceneSDF(p + float2(1.0, 0.0)) - sceneSDF(p - float2(1.0, 0.0)),
                    sceneSDF(p + float2(0.0, 1.0)) - sceneSDF(p - float2(0.0, 1.0))
                );
                float nLen = length(n);
                if (nLen > 0.0001) {
                    n = n / nLen;
                } else {
                    n = float2(0.0, -1.0);
                }

                // 厚度剖面：t=1 平坦内部，t=0 边缘；slope 为斜面陡峭度
                float t = clamp(-d / max(bevel, 1.0), 0.0, 1.0);
                float edge = 1.0 - t;
                float slope = edge * edge;

                // 折射：沿法线向内采样（透镜放大感，边缘为内侧背景的压缩带）
                // ⚠️ 必须向内：RenderEffect 的 RuntimeShader 子输入只保证"输出裁剪区"
                // 内可采样（Android 未暴露 Skia 的 childSampleRadius），向外采样会
                // 读到透明黑，在轮廓处形成黑边
                float refr = refractPx * (1.0 + 0.6 * press);
                float2 offset = -n * (slope * refr);

                // 触摸局部液态凸起（手指下方的局部放大：采样向触点收缩）
                if (touchAmp > 0.001) {
                    float2 tp = p - touchPos;
                    float tr = length(tp);
                    float sigma = max(max(shape1.z, shape1.w), 1.0);
                    float bump = touchAmp * exp(-(tr * tr) / (sigma * sigma * 0.30));
                    if (tr > 1.0) {
                        offset -= (tp / tr) * (bump * refr * 0.5);
                    }
                }

                // 色散：三通道折射量不同（蓝光弯折最多），边缘出现光谱边纹
                float2 cR = coord + offset * (1.0 - dispersion * slope);
                float2 cG = coord + offset;
                float2 cB = coord + offset * (1.0 + dispersion * slope);

                // 安全钳制到视图内容区（子输入可保证的采样范围），杜绝透明黑
                float2 lo = float2(margin + 1.0, margin + 1.0);
                float2 hi = lo + viewSize - float2(2.0, 2.0);
                cR = clamp(cR, lo, hi);
                cG = clamp(cG, lo, hi);
                cB = clamp(cB, lo, hi);
                float3 col = float3(
                    content.eval(cR).r,
                    content.eval(cG).g,
                    content.eval(cB).b
                );

                // 饱和度（合并进同一 pass）；提饱和端走 vibrancy 曲线：低饱和
                // 像素多提、高饱和像素少提、极亮像素保护，避免线性提饱和把浓色
                // 推过曝（曲线与 GlassRuntimeEffects 的 API 36 滤镜一致）
                float lum = dot(col, float3(0.2126, 0.7152, 0.0722));
                if (satFactor <= 1.0) {
                    col = mix(float3(lum), col, satFactor);
                } else {
                    float satNow = max(col.r, max(col.g, col.b)) - min(col.r, min(col.g, col.b));
                    float room = 1.0 - smoothstep(0.2, 0.85, satNow);
                    float hl = 1.0 - smoothstep(0.75, 0.98, lum);
                    float amount = 1.0 + (satFactor - 1.0) * mix(0.3, 1.0, room * hl);
                    col = clamp(mix(float3(lum), col, amount), float3(0.0), float3(1.0));
                }

                // 自适应染色（Regular）/ 压暗层（Clear）
                if (adaptiveTint > 0.5) {
                    // 逐像素自适应：按局部（模糊后）亮度在提亮/压暗之间平滑过渡，
                    // 玻璃跨明暗背景时不再整体翻转；曲线与全局版本
                    // （LiquidGlassView.onLuminanceSample）一致
                    float lumT = dot(col, float3(0.2126, 0.7152, 0.0722));
                    float e = smoothstep(0.35, 0.75, lumT);
                    col = mix(col, float3(1.0 - e), 0.14 + 0.08 * e);
                } else {
                    col = mix(col, tintColor.rgb, tintColor.a);
                }
                col = col * (1.0 - dimAmount);

                // 光照：同一法线场驱动。高光集中在贴边窄带（宽度与斜面弱相关，
                // 上限 9px），角度瓣收紧（pow 5）——避免大斜面时出现大面积泛白光斑
                float facing = dot(n, -lightDir);
                float facingPos = max(facing, 0.0);
                float facingNeg = max(-facing, 0.0);

                float bandW = clamp(bevel * 0.3, 2.0, 9.0);
                float rim = clamp(1.0 - (-d - 0.5) / bandW, 0.0, 1.0) * cov;
                float lobe = 0.55 * pow(facingPos, 5.0) + 0.18 * pow(facingNeg, 5.0) + 0.05;
                float hair = clamp(1.0 - abs(d + 1.0) / 1.5, 0.0, 1.0);
                float spec = (rim * lobe + hair * (0.22 + 0.35 * facingPos))
                             * specStrength * (1.0 - 0.35 * press);
                col += float3(spec);

                // 内阴影：背光侧边缘内部微暗（宽度独立于斜面，上限 28px）
                float shadowW = clamp(bevel, 4.0, 28.0);
                float shadowBand = pow(clamp(1.0 + d / shadowW, 0.0, 1.0), 1.5);
                float ish = shadowBand * facingNeg * innerShadow;
                col = col * (1.0 - 0.45 * ish);

                col = clamp(col, float3(0.0), float3(1.0));
                return half4(half3(col * cov), half(cov));
            }
        """
    }

    /**
     * 透镜渲染参数（值语义；与上一帧不等时才重建 RenderEffect）
     *
     * 形状坐标均为视图局部像素；shape2HW <= 0 表示副形状禁用。
     */
    data class LensParams(
        val blurRadius: Float,
        val shape1CX: Float, val shape1CY: Float,
        val shape1HW: Float, val shape1HH: Float, val radius1: Float,
        val shape2CX: Float, val shape2CY: Float,
        val shape2HW: Float, val shape2HH: Float, val radius2: Float,
        val blendK: Float,
        val bevel: Float,
        val refract: Float,
        val dispersion: Float,
        val lightX: Float, val lightY: Float,
        val spec: Float,
        val innerShadow: Float,
        val tint: Int,          // straight-alpha ARGB（adaptiveTint 时被忽略）
        val adaptiveTint: Boolean, // 逐像素自适应染色（Regular + enableAdaptiveTint）
        val dim: Float,
        val saturation: Float,  // 100 = 原始
        val press: Float,
        val touchX: Float, val touchY: Float, val touchAmp: Float
    )

    private val renderNode = RenderNode("LiquidGlassLens")

    private var shader: RuntimeShader? = null
    private var shaderBroken = false

    private var lastParams: LensParams? = null
    private var lastWidth = 0
    private var lastHeight = 0
    private var lastMargin = -1

    private val location = IntArray(2)
    private val parentLocation = IntArray(2)

    /** AGSL 编译失败（个别设备驱动问题）时为 false，调用方应回退旧管线 */
    val isAvailable: Boolean
        get() = !shaderBroken

    /**
     * 绘制透镜玻璃到目标画布
     *
     * @param canvas 目标画布（必须硬件加速）
     * @param glassView 玻璃视图（定位 + 录制时跳过自身）
     * @param params 本帧渲染参数
     * @param margin 背景录制外扩边距（px，覆盖折射/模糊采样越界）
     * @return 是否成功绘制；false 时调用方应回退
     */
    fun draw(
        canvas: Canvas,
        glassView: LiquidGlassView,
        params: LensParams,
        margin: Int
    ): Boolean {
        if (shaderBroken) return false
        if (!canvas.isHardwareAccelerated) return false
        // 背景来源：默认直接父容器，setBackdropSource 后为指定视图（可跨层级）
        val parent = glassView.backdropView ?: return false
        val width = glassView.width
        val height = glassView.height
        if (width <= 0 || height <= 0) return false

        val sh = shader ?: try {
            RuntimeShader(LENS_AGSL).also { shader = it }
        } catch (e: Exception) {
            // AGSL 编译失败：标记不可用，让调用方永久回退旧管线
            Log.e(TAG, "Lens AGSL compile failed, falling back", e)
            shaderBroken = true
            return false
        }

        if (params != lastParams || width != lastWidth || height != lastHeight || margin != lastMargin) {
            try {
                renderNode.setRenderEffect(buildEffect(sh, params, margin, width, height))
            } catch (e: Exception) {
                Log.e(TAG, "Lens effect build failed, falling back", e)
                shaderBroken = true
                return false
            }
            lastParams = params
            lastWidth = width
            lastHeight = height
            lastMargin = margin
        }

        // 计算相对背景视图的偏移（屏幕坐标差：兼容滚动容器，且背景视图在
        // 另一个 window（Dialog/PopupWindow）时也成立）
        glassView.getLocationOnScreen(location)
        parent.getLocationOnScreen(parentLocation)
        val offsetX = (location[0] - parentLocation[0]).toFloat()
        val offsetY = (location[1] - parentLocation[1]).toFloat()

        // 录制区域向四周外扩 margin：折射/模糊在边缘处能采到真实内容而非透明黑
        renderNode.setPosition(-margin, -margin, width + margin, height + margin)
        val recordingCanvas = renderNode.beginRecording(width + 2 * margin, height + 2 * margin)
        try {
            recordingCanvas.translate(margin - offsetX, margin - offsetY)
            glassView.isCapturingBackdrop = true
            // 硬件画布上父视图绘制子视图不走 View.draw，isCapturingBackdrop 拦不住自己；
            // setTransitionVisibility 只改可见性标志、不触发 invalidate（同旧 GPU 管线）
            glassView.setTransitionVisibility(View.INVISIBLE)
            try {
                parent.draw(recordingCanvas)
            } finally {
                glassView.setTransitionVisibility(View.VISIBLE)
                glassView.isCapturingBackdrop = false
            }
        } finally {
            renderNode.endRecording()
        }

        // 形状覆盖率由着色器输出（形状外 alpha=0），无需 clipPath
        canvas.drawRenderNode(renderNode)
        return true
    }

    /**
     * 构建效果链：模糊（内层）→ 透镜着色器（外层）
     */
    private fun buildEffect(
        sh: RuntimeShader,
        p: LensParams,
        margin: Int,
        width: Int,
        height: Int
    ): RenderEffect {
        sh.setFloatUniform("margin", margin.toFloat())
        sh.setFloatUniform("viewSize", width.toFloat(), height.toFloat())
        sh.setFloatUniform("shape1", p.shape1CX, p.shape1CY, p.shape1HW, p.shape1HH)
        sh.setFloatUniform("radius1", p.radius1)
        sh.setFloatUniform("shape2", p.shape2CX, p.shape2CY, p.shape2HW, p.shape2HH)
        sh.setFloatUniform("radius2", p.radius2)
        sh.setFloatUniform("blendK", p.blendK)
        sh.setFloatUniform("bevel", p.bevel)
        sh.setFloatUniform("refractPx", p.refract)
        sh.setFloatUniform("dispersion", p.dispersion)
        sh.setFloatUniform("lightDir", p.lightX, p.lightY)
        sh.setFloatUniform("specStrength", p.spec)
        sh.setFloatUniform("innerShadow", p.innerShadow)
        sh.setFloatUniform(
            "tintColor",
            Color.red(p.tint) / 255f,
            Color.green(p.tint) / 255f,
            Color.blue(p.tint) / 255f,
            Color.alpha(p.tint) / 255f
        )
        sh.setFloatUniform("adaptiveTint", if (p.adaptiveTint) 1f else 0f)
        sh.setFloatUniform("dimAmount", p.dim)
        sh.setFloatUniform("satFactor", p.saturation / 100f)
        sh.setFloatUniform("press", p.press)
        sh.setFloatUniform("touchPos", p.touchX, p.touchY)
        sh.setFloatUniform("touchAmp", p.touchAmp)

        val lens = RenderEffect.createRuntimeShaderEffect(sh, "content")
        return if (p.blurRadius > 0.01f) {
            RenderEffect.createChainEffect(
                lens,
                RenderEffect.createBlurEffect(p.blurRadius, p.blurRadius, Shader.TileMode.CLAMP)
            )
        } else {
            lens
        }
    }

    fun release() {
        renderNode.discardDisplayList()
        shader = null
        lastParams = null
    }
}
