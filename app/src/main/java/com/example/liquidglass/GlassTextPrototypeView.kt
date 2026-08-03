package com.example.liquidglass

import android.content.Context
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.util.AttributeSet
import android.util.Log
import android.view.View
import kotlin.math.max

/**
 * 玻璃文字原型（demo 专用，尚未进库）
 *
 * 验证的假设：[GlassLensRenderer] 的着色器里只有 `sceneSDF()` 知道形状是什么，
 * 它下游的法线、斜面剖面、折射、色散、高光、内阴影全是形状无关的。把解析式的
 * 圆角矩形 SDF 换成从 [TextSdf] 烘出来的文字距离场，整套光学效果就落到笔画上——
 * 背景会真的透过字形被压缩、边缘出色散彩边。
 *
 * 与库里的透镜管线相同的部分：录制背景到 RenderNode（外扩 margin）→ 模糊 →
 * 单 pass AGSL → 输出，形状覆盖率由着色器给（形状外 alpha=0），不需要裁剪。
 *
 * 已知限制（正是这个原型要拿来判断的）：
 * - 细笔画会被斜面吃掉。折射/斜面宽度必须跟着笔画粗细走，
 *   见 [autoBevel] / [autoRefract]——它们按字号推算半笔画宽。
 * - 复杂背景上可读性差。装饰性大标题合适，正文不合适。
 * - API 33 以下没有 AGSL，这里直接画普通文字兜底。
 */
class GlassTextPrototypeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        private const val TAG = "GlassTextPrototype"

        /**
         * 透镜着色器的文字版：与库里的 LENS_AGSL 逐行一致，
         * 只把 sceneSDF 从「圆角矩形解析式」换成「距离场纹理采样」，
         * 并去掉了双形状融合与按压液态（原型用不到）
         */
        private const val TEXT_LENS_AGSL = """
            uniform shader content;
            uniform shader sdfTex;
            uniform float2 sdfSize;
            uniform float2 sdfOffset;
            uniform float  sdfScale;
            uniform float  sdfRange;
            uniform float  margin;
            uniform float2 viewSize;
            uniform float  bevel;
            uniform float  refractPx;
            uniform float  dispersion;
            uniform float2 lightDir;
            uniform float  specStrength;
            uniform float  innerShadow;
            uniform float4 tintColor;
            uniform float  dimAmount;
            uniform float  satFactor;

            // sqrt 压缩编码的逆变换：近场码字密、边缘才不会台阶化
            float decodeSdf(float2 texel) {
                float s = 2.0 * sdfTex.eval(texel).r - 1.0;
                return s * abs(s) * sdfRange;
            }

            float sceneSDF(float2 p) {
                // 距离场是超采样分辨率，视图像素 → 纹素要乘 sdfScale
                float2 uv = (p - sdfOffset) * sdfScale;
                // 纹理之外一律视为远在形状外部
                if (uv.x < 0.0 || uv.y < 0.0 || uv.x > sdfSize.x || uv.y > sdfSize.y) {
                    return sdfRange;
                }
                // 自己做双线性：插值的必须是解码后的距离。
                // 直接让采样器线性插值编码值在 sqrt 压缩下是有偏的，
                // 且 RenderEffect 里子着色器的 filterMode 是否生效不可依赖
                float2 t = uv - 0.5;
                float2 f = fract(t);
                float2 b = floor(t) + 0.5;
                float d00 = decodeSdf(b);
                float d10 = decodeSdf(b + float2(1.0, 0.0));
                float d01 = decodeSdf(b + float2(0.0, 1.0));
                float d11 = decodeSdf(b + float2(1.0, 1.0));
                return mix(mix(d00, d10, f.x), mix(d01, d11, f.x), f.y);
            }

            half4 main(float2 coord) {
                float2 p = coord - float2(margin, margin);
                float d = sceneSDF(p);

                // 距离场已是亚像素精度，1px 斜坡即可，不必像圆角矩形那样羽化 1.5px
                float cov = clamp(0.5 - d, 0.0, 1.0);
                if (cov <= 0.004) {
                    return half4(0.0);
                }

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

                float t = clamp(-d / max(bevel, 1.0), 0.0, 1.0);
                float edge = 1.0 - t;
                float slope = edge * edge;

                // 只能向内采样：RenderEffect 的子输入只保证输出裁剪区内可采
                float2 offset = -n * (slope * refractPx);

                float2 cR = coord + offset * (1.0 - dispersion * slope);
                float2 cG = coord + offset;
                float2 cB = coord + offset * (1.0 + dispersion * slope);

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

                col = mix(col, tintColor.rgb, tintColor.a);
                col = col * (1.0 - dimAmount);

                float facing = dot(n, -lightDir);
                float facingPos = max(facing, 0.0);
                float facingNeg = max(-facing, 0.0);

                float bandW = clamp(bevel * 0.3, 2.0, 9.0);
                float rim = clamp(1.0 - (-d - 0.5) / bandW, 0.0, 1.0) * cov;
                float lobe = 0.55 * pow(facingPos, 5.0) + 0.18 * pow(facingNeg, 5.0) + 0.05;
                float hair = clamp(1.0 - abs(d + 1.0) / 1.5, 0.0, 1.0);
                float spec = (rim * lobe + hair * (0.22 + 0.35 * facingPos)) * specStrength;
                col += float3(spec);

                float shadowW = clamp(bevel, 4.0, 28.0);
                float shadowBand = pow(clamp(1.0 + d / shadowW, 0.0, 1.0), 1.5);
                float ish = shadowBand * facingNeg * innerShadow;
                col = col * (1.0 - 0.45 * ish);

                col = clamp(col, float3(0.0), float3(1.0));
                return half4(half3(col * cov), half(cov));
            }
        """
    }

    // ==================== 对外参数 ====================

    var text: String = ""
        set(value) {
            if (field != value) {
                field = value
                scheduleRebuild()
            }
        }

    var textSizePx: Float = 120f
        set(value) {
            if (field != value) {
                field = value
                textPaint.textSize = value
                scheduleRebuild()
            }
        }

    /**
     * 斜面宽度系数（× 字号）
     *
     * 默认值是真机调出来的：斜面压到很薄、折射拉到很大，玻璃厚度只集中在
     * 笔画边缘那一窄条，观感比"厚玻璃块"更接近液态玻璃的字
     */
    var bevelFactor = 0.02f
        set(value) {
            if (field != value) {
                field = value
                scheduleRebuild()   // 影响 SDF 的 padding，必须重烘
            }
        }

    /** 折射位移系数（× 字号）。配合极薄的斜面用，边缘压缩带才够明显 */
    var refractFactor = 0.45f
        set(value) {
            if (field != value) {
                field = value
                scheduleRebuild()
            }
        }

    /**
     * 模糊半径。太小则笔画内部几乎等于原背景，玻璃感只剩一圈描边；
     * 太大又会把细笔画糊成一团。20px 是这版原型的平衡点
     */
    var blurRadius = 20f
        set(value) { field = value; effectDirty = true; invalidate() }

    var dispersion = 0.14f
        set(value) { field = value; effectDirty = true; invalidate() }

    var specStrength = 2.49f
        set(value) { field = value; effectDirty = true; invalidate() }

    var innerShadow = 1.47f
        set(value) { field = value; effectDirty = true; invalidate() }

    var saturation = 130f
        set(value) { field = value; effectDirty = true; invalidate() }

    var tint = Color.argb(58, 255, 255, 255)
        set(value) { field = value; effectDirty = true; invalidate() }

    private val autoBevel: Float get() = max(textSizePx * bevelFactor, 3f)

    private val autoRefract: Float get() = max(textSizePx * refractFactor, 4f)

    // ==================== 内部状态 ====================

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = textSizePx
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }

    private val fallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = textSizePx
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }

    private var sdf: TextSdf.Result? = null
    private var sdfShader: BitmapShader? = null

    private val renderNode = RenderNode("GlassText")
    private var shader: RuntimeShader? = null
    private var shaderBroken = false
    private var effectDirty = true

    private val location = IntArray(2)
    private val parentLocation = IntArray(2)

    private val supported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !shaderBroken

    fun setTypefaceAndRebuild(tf: Typeface) {
        textPaint.typeface = tf
        fallbackPaint.typeface = tf
        scheduleRebuild()
    }

    private val rebuildTask = Runnable {
        rebuildSdf()
        requestLayout()
        invalidate()
    }

    /**
     * 合并短时间内的多次重烘：连续打字或拖滑杆时每一步都重算距离场会明显掉帧
     * （"Liquid" @240px 一次约 140ms），延迟 60ms 只算最后一次
     */
    private fun scheduleRebuild() {
        removeCallbacks(rebuildTask)
        postDelayed(rebuildTask, 60)
    }

    /** SDF 只在文字/字号/字体变化时重算，不在绘制路径上 */
    private fun rebuildSdf() {
        sdf?.bitmap?.recycle()
        sdf = null
        sdfShader = null
        if (text.isBlank()) return

        // 距离场半径要盖住斜面和折射的采样距离；上限 72px 是成本妥协——
        // padding 直接决定位图面积，而距离变换是 O(面积)
        val range = (max(autoBevel, autoRefract) * 1.35f + 6f).coerceIn(24f, 72f)
        val t0 = System.nanoTime()
        val result = TextSdf.build(text, textPaint, range) ?: return
        sdf = result
        sdfShader = BitmapShader(result.bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            // 取最近邻：双线性由着色器自己做（插值解码后的距离），避免被插两次
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                filterMode = BitmapShader.FILTER_MODE_NEAREST
            }
        }
        effectDirty = true
        Log.d(
            TAG,
            "SDF \"$text\" @${textPaint.textSize}px → ${result.bitmap.width}x${result.bitmap.height}" +
                " in ${(System.nanoTime() - t0) / 1_000_000f}ms"
        )
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = sdf
        val w = (s?.viewWidth ?: 0) + paddingLeft + paddingRight
        val h = (s?.viewHeight ?: 0) + paddingTop + paddingBottom
        setMeasuredDimension(
            resolveSize(max(w, suggestedMinimumWidth), widthMeasureSpec),
            resolveSize(max(h, suggestedMinimumHeight), heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val s = sdf
        val bmpShader = sdfShader
        if (s == null || bmpShader == null || text.isBlank()) return

        if (!supported || !canvas.isHardwareAccelerated) {
            drawFallback(canvas, s)
            return
        }
        if (!drawGlassText(canvas, s, bmpShader)) {
            drawFallback(canvas, s)
        }
        // 背景是活的（壁纸/列表都可能在动），逐帧重绘
        invalidate()
    }

    /** API 33 以下：没有 AGSL，退回普通文字 */
    private fun drawFallback(canvas: Canvas, s: TextSdf.Result) {
        val fm = fallbackPaint.fontMetrics
        val baseline = height / 2f - (fm.ascent + fm.descent) / 2f
        canvas.drawText(text, (width - fallbackPaint.measureText(text)) / 2f, baseline, fallbackPaint)
    }

    private fun drawGlassText(canvas: Canvas, s: TextSdf.Result, bmpShader: BitmapShader): Boolean {
        val parent = parent as? View ?: return false
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return false

        val sh = shader ?: try {
            RuntimeShader(TEXT_LENS_AGSL).also { shader = it }
        } catch (e: Exception) {
            Log.e(TAG, "text lens AGSL compile failed", e)
            shaderBroken = true
            return false
        }

        val margin = (autoRefract + blurRadius * 3f).toInt().coerceAtLeast(8)
        // 文字位图在视图内居中（视图像素坐标）
        val offX = (w - s.viewWidth) / 2f
        val offY = (h - s.viewHeight) / 2f

        if (effectDirty) {
            sh.setInputShader("sdfTex", bmpShader)
            sh.setFloatUniform("sdfSize", s.bitmap.width.toFloat(), s.bitmap.height.toFloat())
            sh.setFloatUniform("sdfOffset", offX, offY)
            sh.setFloatUniform("sdfScale", s.scale.toFloat())
            sh.setFloatUniform("sdfRange", s.range)
            sh.setFloatUniform("margin", margin.toFloat())
            sh.setFloatUniform("viewSize", w.toFloat(), h.toFloat())
            sh.setFloatUniform("bevel", autoBevel)
            sh.setFloatUniform("refractPx", autoRefract)
            sh.setFloatUniform("dispersion", dispersion)
            sh.setFloatUniform("lightDir", 0.35f, -0.94f)
            sh.setFloatUniform("specStrength", specStrength)
            sh.setFloatUniform("innerShadow", innerShadow)
            sh.setFloatUniform(
                "tintColor",
                Color.red(tint) / 255f,
                Color.green(tint) / 255f,
                Color.blue(tint) / 255f,
                Color.alpha(tint) / 255f
            )
            sh.setFloatUniform("dimAmount", 0f)
            sh.setFloatUniform("satFactor", saturation / 100f)

            val lens = RenderEffect.createRuntimeShaderEffect(sh, "content")
            renderNode.setRenderEffect(
                if (blurRadius > 0.01f) {
                    RenderEffect.createChainEffect(
                        lens,
                        RenderEffect.createBlurEffect(blurRadius, blurRadius, Shader.TileMode.CLAMP)
                    )
                } else {
                    lens
                }
            )
            effectDirty = false
        }

        getLocationOnScreen(location)
        parent.getLocationOnScreen(parentLocation)
        val dx = (location[0] - parentLocation[0]).toFloat()
        val dy = (location[1] - parentLocation[1]).toFloat()

        renderNode.setPosition(-margin, -margin, w + margin, h + margin)
        val rc = renderNode.beginRecording(w + 2 * margin, h + 2 * margin)
        try {
            rc.translate(margin - dx, margin - dy)
            // 硬件画布上父视图画子视图不过 View.draw，靠可见性标志跳过自身，
            // 否则会对正在录制的 RenderNode 重入 beginRecording 直接崩
            setTransitionVisibility(INVISIBLE)
            try {
                parent.draw(rc)
            } finally {
                setTransitionVisibility(VISIBLE)
            }
        } finally {
            renderNode.endRecording()
        }

        canvas.drawRenderNode(renderNode)
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        effectDirty = true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(rebuildTask)
        renderNode.discardDisplayList()
        sdf?.bitmap?.recycle()
        sdf = null
        sdfShader = null
        shader = null
    }
}
