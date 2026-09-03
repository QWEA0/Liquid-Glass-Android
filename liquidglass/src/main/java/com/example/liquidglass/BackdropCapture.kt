/**
 * 背景捕获：把背景来源视图画进给定画布，并把玻璃自身排除掉
 *
 * 直接 `source.draw(canvas)` 只在玻璃是 source 的**直接子级**时安全。
 * source 是更高层级的祖先时，source → 玻璃 这条路径上的中间容器有两个问题：
 *
 * 1. **录制重入**：中间容器的 RenderNode 此刻正在 beginRecording 里（玻璃的
 *    onDraw 就发生在它的录制过程中），而硬件画布上 dispatchDraw → drawChild
 *    走的是 updateDisplayListIfDirty，会对同一个节点再次 beginRecording，
 *    抛 IllegalStateException 直接崩溃。
 * 2. **引用成环**：drawChild 记录的是中间容器 RenderNode 的**引用**而不是像素，
 *    而这个节点又引用着玻璃自己的节点，于是形成
 *    玻璃 → 背景 → 中间容器 → 玻璃 的环，光栅化时无限递归。
 *
 * 所以路径上的每一层都改用公开的 [View.draw]：这条路不碰该 View 自己的
 * RenderNode（既不重入也不成环），dispatchDraw 实时执行，被临时置为 INVISIBLE
 * 的下一层会被跳过。每层的定位取屏幕坐标差，各级滚动与平移自动带上。
 *
 * 已知取舍（只影响 source 是跨层级祖先的情况）：
 * - 路径上的容器带缩放/旋转/透明度时，这些变换不会作用到它的内容上；
 * - 玻璃所在的分支单独补画在最后，同层里排在它之后的兄弟视图会被它盖住。
 */
package com.example.liquidglass

import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup

internal class BackdropCapture {

    // 复用，避免每帧分配
    private val hostLocation = IntArray(2)
    private val childLocation = IntArray(2)

    /**
     * 把 [source] 的内容画进 [canvas]，跳过 [glass]
     *
     * @param canvas 画布原点需已对齐 [source] 左上角
     */
    fun draw(canvas: Canvas, source: View, glass: View) {
        drawLevel(canvas, source, glass)
    }

    private fun drawLevel(canvas: Canvas, host: View, glass: View) {
        // 这一层要跳过的直接子级：玻璃本身，或玻璃所在的那条分支。
        // host 不是玻璃的祖先（同级/跨层级/跨 window 的背景来源）时为 null，
        // 这种情况没有重入风险，整棵照常画。
        val branch = childOnPathTo(host, glass)
        drawContent(canvas, host, branch ?: glass)
        if (branch == null || branch === glass) return

        // 分支刚才被跳过了，这里单独补画（坐标取屏幕差，自带滚动/平移）
        host.getLocationOnScreen(hostLocation)
        branch.getLocationOnScreen(childLocation)
        val dx = (childLocation[0] - hostLocation[0]).toFloat()
        val dy = (childLocation[1] - hostLocation[1]).toFloat()
        val clip = (host as? ViewGroup)?.clipChildren != false

        val save = canvas.save()
        if (clip) canvas.clipRect(0f, 0f, host.width.toFloat(), host.height.toFloat())
        canvas.translate(dx, dy)
        if (clip) canvas.clipRect(0f, 0f, branch.width.toFloat(), branch.height.toFloat())
        drawLevel(canvas, branch, glass)
        canvas.restoreToCount(save)
    }

    /** 画 [host] 自身，期间把 [hidden] 置为 INVISIBLE 让 dispatchDraw 跳过它 */
    private fun drawContent(canvas: Canvas, host: View, hidden: View) {
        val save = canvas.save()
        // 公开的 View.draw(Canvas) 不带滚动偏移（框架是在 updateDisplayListIfDirty
        // 里补的），这里补上，否则滚动容器作为背景来源时内容会整体错位
        canvas.translate(-host.scrollX.toFloat(), -host.scrollY.toFloat())
        // setTransitionVisibility 只改可见性标志、不触发 invalidate
        hidden.setTransitionVisibility(View.INVISIBLE)
        try {
            host.draw(canvas)
        } finally {
            hidden.setTransitionVisibility(View.VISIBLE)
            canvas.restoreToCount(save)
        }
    }

    /**
     * [host] 的直接子级里通往 [descendant] 的那一个；
     * [host] 不是 [descendant] 的祖先时返回 null
     */
    private fun childOnPathTo(host: View, descendant: View): View? {
        var child: View = descendant
        var p = descendant.parent
        while (p is View) {
            if (p === host) return child
            child = p
            p = p.parent
        }
        return null
    }
}
