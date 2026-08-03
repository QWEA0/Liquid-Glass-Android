package com.example.liquidglass

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * 文字轮廓 → 有符号距离场（SDF）位图
 *
 * 玻璃文字原型的第一环：透镜着色器的全部光学效果（法线、斜面、折射、色散、
 * 高光、内阴影）都只依赖 `d = sceneSDF(p)` 和它的**梯度**，与形状本身无关。
 *
 * 边缘平滑度取决于距离场的梯度是否连续，而不只是零等值线的位置：
 * 法线由 ±1px 差分算出，梯度一抖，边缘高光和折射带就跟着抖成锯齿。
 * 所以这里用抗锯齿欧氏距离变换（Gustavson & Strand 2011）而不是二值 EDT：
 *
 * 1. **边缘像素的亚像素距离**由覆盖率 a 和覆盖率梯度方向共同解出（[edgeDF]）。
 *    只用 `0.5 - a` 在轴对齐边上精确，但在 45° 边上误差可达 0.2px 且随角度跳变，
 *    正是斜笔画锯齿的来源。
 * 2. **向量距离变换只以边缘像素为种子**，每个像素记录到最近边缘像素的整数偏移，
 *    最终距离 = `符号 × |偏移| + 该边缘像素自身的亚像素距离`。
 *    全场的值都由同一组亚像素边界估计推出来，梯度因此是连续的。
 * 3. 再叠 [SUPERSAMPLE] 倍超采样，把栅格化和距离变换都放到高分辨率上做。
 *
 * 编码：`v = 0.5 + 0.5 * sign(s) * sqrt(|s|)`，其中 `s = d / range`。
 * sqrt 压缩把码字集中到近场——决定边缘观感的只有 |d|<2 那一圈。
 * 着色器解码：`s = 2v - 1; d = s * |s| * range`。
 */
object TextSdf {

    /** 超采样倍数：栅格化与距离变换都在 N 倍分辨率上做，代价是 N² 的时间与内存 */
    const val SUPERSAMPLE = 2

    /** 位图边长上限，防止超大字号把内存撑爆 */
    private const val MAX_DIM = 4096

    class Result(
        /** r 通道存压缩编码距离的位图（分辨率为视图像素的 [SUPERSAMPLE] 倍） */
        val bitmap: Bitmap,
        /** 距离场编码范围（视图像素），解码时要乘回去 */
        val range: Float,
        /** 位图纹素 → 视图像素的缩放倍数 */
        val scale: Int,
        /** 文字包围盒宽高（视图像素，含 padding） */
        val viewWidth: Int,
        val viewHeight: Int
    )

    /**
     * @param text 要烘的文字
     * @param paint 字体、字号、字重来源（不使用其颜色）
     * @param range 距离场覆盖半径（视图像素），要能容纳斜面与折射的采样距离；
     *              位图 padding 取同值，保证轮廓外 range 内的距离都是真值
     */
    fun build(text: String, paint: Paint, range: Float): Result? {
        if (text.isEmpty()) return null

        val ss = SUPERSAMPLE
        val padView = ceil(range).toInt().coerceAtLeast(2)

        val path = Path()
        paint.getTextPath(text, 0, text.length, 0f, 0f, path)
        val bounds = RectF()
        path.computeBounds(bounds, true)
        if (bounds.width() < 1f || bounds.height() < 1f) return null

        val viewW = bounds.width().toInt() + 2 + 2 * padView
        val viewH = bounds.height().toInt() + 2 + 2 * padView
        val w = min(viewW * ss, MAX_DIM)
        val h = min(viewH * ss, MAX_DIM)

        // 1. 轮廓在超采样分辨率上抗锯齿栅格化，保留覆盖率
        val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(mask).apply {
            scale(ss.toFloat(), ss.toFloat())
            translate(padView - bounds.left, padView - bounds.top)
            drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        }
        val pixels = IntArray(w * h)
        mask.getPixels(pixels, 0, w, 0, 0, w, h)
        mask.recycle()

        val cov = FloatArray(w * h)
        for (i in pixels.indices) cov[i] = Color.alpha(pixels[i]) / 255f

        // 2. 边缘像素：解出亚像素有符号距离（正 = 像素中心在形状外）
        val edgeDist = FloatArray(w * h)
        val grid = Grid(w, h)
        var hasEdge = false
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val a = cov[i]
                if (a <= 0f || a >= 1f) continue
                edgeDist[i] = edgeDF(cov, w, h, x, y, a)
                grid.seed(i)
                hasEdge = true
            }
        }
        // 极端情况（字号过小导致完全无抗锯齿边）退回二值边界
        if (!hasEdge) {
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val i = y * w + x
                    val inside = cov[i] >= 0.5f
                    val nb = (x > 0 && (cov[i - 1] >= 0.5f) != inside) ||
                        (x < w - 1 && (cov[i + 1] >= 0.5f) != inside) ||
                        (y > 0 && (cov[i - w] >= 0.5f) != inside) ||
                        (y < h - 1 && (cov[i + w] >= 0.5f) != inside)
                    if (nb) {
                        edgeDist[i] = if (inside) -0.5f else 0.5f
                        grid.seed(i)
                        hasEdge = true
                    }
                }
            }
        }
        if (!hasEdge) return null

        // 3. 向量距离变换（种子 = 边缘像素），再叠上最近边缘像素的亚像素距离
        grid.transform()
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val nx = x + grid.dx[i]
                val ny = y + grid.dy[i]
                val sub = if (nx in 0 until w && ny in 0 until h) edgeDist[ny * w + nx] else 0f
                val len = sqrt(grid.distSq(i).toFloat())
                val sgn = if (cov[i] >= 0.5f) -1f else 1f
                // 距离统一换算回视图像素
                val d = (sgn * len + sub) / ss

                val s = (d / range).coerceIn(-1f, 1f)
                val v = 0.5f + 0.5f * sign(s) * sqrt(abs(s))
                val g = (v * 255f + 0.5f).toInt().coerceIn(0, 255)
                out[i] = Color.argb(255, g, g, g)
            }
        }
        val sdf = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        sdf.setPixels(out, 0, w, 0, 0, w, h)
        return Result(sdf, range, ss, w / ss, h / ss)
    }

    /**
     * 由覆盖率与覆盖率梯度解出边缘像素中心到真实边界的有符号距离
     *
     * 直边穿过单位像素时，覆盖率 a 与距离的关系取决于边的朝向：轴对齐时
     * `d = 0.5 - a`，45° 时是二次关系。只用前者会让斜笔画的边界位置随角度跳变，
     * 这正是斜线段上锯齿最明显的原因。这里用 Gustavson & Strand 的闭式解。
     */
    private fun edgeDF(cov: FloatArray, w: Int, h: Int, x: Int, y: Int, a: Float): Float {
        // Sobel 梯度（边界处夹取坐标）
        fun at(px: Int, py: Int): Float =
            cov[py.coerceIn(0, h - 1) * w + px.coerceIn(0, w - 1)]

        val r2 = 1.4142136f
        var gx = -at(x - 1, y - 1) - r2 * at(x - 1, y) - at(x - 1, y + 1) +
            at(x + 1, y - 1) + r2 * at(x + 1, y) + at(x + 1, y + 1)
        var gy = -at(x - 1, y - 1) - r2 * at(x, y - 1) - at(x + 1, y - 1) +
            at(x - 1, y + 1) + r2 * at(x, y + 1) + at(x + 1, y + 1)

        val glen = sqrt(gx * gx + gy * gy)
        if (glen < 1e-6f) return 0.5f - a      // 梯度退化：退回轴对齐近似
        gx /= glen
        gy /= glen

        // 只取朝向的绝对值并排序，闭式解只依赖 |nx| <= |ny|
        var nx = abs(gx)
        var ny = abs(gy)
        if (nx > ny) {
            val t = nx; nx = ny; ny = t
        }
        val a1 = 0.5f * nx / ny
        return when {
            a < a1 -> 0.5f * (nx + ny) - sqrt(2f * nx * ny * a)
            a < 1f - a1 -> (0.5f - a) * ny
            else -> -0.5f * (nx + ny) + sqrt(2f * nx * ny * (1f - a))
        }
    }

    /**
     * 8SSEDT 向量距离变换：每个像素记录到最近种子像素的整数偏移，
     * 前向/后向各扫一遍，用邻居的向量加上自身位移做候选比较。
     */
    private class Grid(val w: Int, val h: Int) {
        companion object {
            private const val FAR = 9999
        }

        val dx = IntArray(w * h) { FAR }
        val dy = IntArray(w * h) { FAR }

        fun seed(i: Int) {
            dx[i] = 0
            dy[i] = 0
        }

        fun distSq(i: Int): Long {
            val x = dx[i].toLong()
            val y = dy[i].toLong()
            return x * x + y * y
        }

        private fun compare(i: Int, x: Int, y: Int, ox: Int, oy: Int) {
            val nx = x + ox
            val ny = y + oy
            if (nx < 0 || ny < 0 || nx >= w || ny >= h) return
            val ni = ny * w + nx
            val cdx = dx[ni] + ox
            val cdy = dy[ni] + oy
            val cand = cdx.toLong() * cdx + cdy.toLong() * cdy
            if (cand < distSq(i)) {
                dx[i] = cdx
                dy[i] = cdy
            }
        }

        fun transform() {
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val i = y * w + x
                    compare(i, x, y, -1, 0)
                    compare(i, x, y, 0, -1)
                    compare(i, x, y, -1, -1)
                    compare(i, x, y, 1, -1)
                }
                for (x in w - 1 downTo 0) {
                    compare(y * w + x, x, y, 1, 0)
                }
            }
            for (y in h - 1 downTo 0) {
                for (x in w - 1 downTo 0) {
                    val i = y * w + x
                    compare(i, x, y, 1, 0)
                    compare(i, x, y, 0, 1)
                    compare(i, x, y, -1, 1)
                    compare(i, x, y, 1, 1)
                }
                for (x in 0 until w) {
                    compare(y * w + x, x, y, -1, 0)
                }
            }
        }
    }
}
