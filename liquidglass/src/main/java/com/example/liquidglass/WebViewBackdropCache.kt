package com.example.liquidglass

import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.webkit.WebView
import java.util.WeakHashMap

/** 隔离 WebView 的绘制 functor，避免窗口与玻璃取样使用不同约束而持续互相触发重绘。 */
internal class WebViewBackdropCache {
    private class Lease {
        val owners = WeakHashMap<WebViewBackdropCache, Unit>()
    }

    private companion object {
        // 多块玻璃共享同一缓存层，最后一个使用者释放时才恢复。
        val leases = WeakHashMap<View, Lease>()
    }

    private var source: View? = null
    private var glass: View? = null
    private var observer: ViewTreeObserver? = null
    private var maxTextureWidth = 0
    private var maxTextureHeight = 0
    private val owned = ArrayList<View>()
    private val desired = ArrayList<View>()
    private val changedByApp = WeakHashMap<View, Unit>()
    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { refresh() }

    fun prepare(canvas: Canvas, source: View, glass: View) {
        if (!canvas.isHardwareAccelerated) {
            release()
            return
        }
        val currentObserver = source.viewTreeObserver
        if (this.source === source && this.glass === glass && observer === currentObserver &&
            maxTextureWidth == canvas.maximumBitmapWidth && maxTextureHeight == canvas.maximumBitmapHeight
        ) return

        release()
        this.source = source
        this.glass = glass
        maxTextureWidth = canvas.maximumBitmapWidth
        maxTextureHeight = canvas.maximumBitmapHeight
        observer = currentObserver
        currentObserver.addOnGlobalLayoutListener(layoutListener)
        refresh()
    }

    private fun refresh() {
        val currentSource = source ?: return
        desired.clear()
        collect(currentSource)
        for (view in owned) if (!desired.contains(view)) releaseLayer(view)
        owned.clear()
        owned.addAll(desired)
    }

    private fun collect(view: View) {
        if (view === glass || view.visibility != View.VISIBLE || view.layerType == View.LAYER_TYPE_SOFTWARE) return
        if (view is WebView) {
            val target = findCacheTarget(view) ?: return
            if (!desired.contains(target) && acquireLayer(target)) desired.add(target)
            return
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) collect(view.getChildAt(i))
    }

    private fun findCacheTarget(webView: WebView): View? {
        val currentSource = source ?: return null
        val currentGlass = glass ?: return null
        var candidate: View? = webView
        var bounded: View? = null
        while (candidate != null && candidate !== currentSource) {
            // 缓存中不能包含正在录制的玻璃，否则会形成 RenderNode 引用环。
            if (containsGlass(candidate, currentGlass)) break
            if (changedByApp.containsKey(candidate)) return null
            if (fits(candidate)) {
                when (candidate.layerType) {
                    View.LAYER_TYPE_SOFTWARE -> return null
                    View.LAYER_TYPE_HARDWARE -> return candidate
                    // 优先只缓存 WebView；超长页面才向上选择有界容器。
                    View.LAYER_TYPE_NONE -> if (bounded == null) bounded = candidate
                }
            }
            candidate = candidate.parent as? View
        }
        return bounded
    }

    private fun fits(view: View): Boolean {
        // 硬件层会裁剪到自身边界，不能改变允许子项越界绘制的容器。
        if (view is ViewGroup && !view.clipChildren) return false
        val root = source?.rootView ?: return false
        return view.width > 0 && view.height > 0 &&
            view.width <= minOf(maxTextureWidth, root.width) &&
            view.height <= minOf(maxTextureHeight, root.height)
    }

    private fun containsGlass(candidate: View, glass: View): Boolean {
        var view: View? = glass
        while (view != null) {
            if (view === candidate) return true
            view = view.parent as? View
        }
        return false
    }

    private fun acquireLayer(view: View): Boolean {
        val existing = leases[view]
        if (existing != null) {
            if (view.layerType != View.LAYER_TYPE_HARDWARE) {
                // 使用期间应用修改了图层设置，后续不再覆盖该选择。
                changedByApp[view] = Unit
                return false
            }
            existing.owners[this] = Unit
            return true
        }
        if (view.layerType != View.LAYER_TYPE_NONE) return false
        view.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        leases[view] = Lease().also { it.owners[this] = Unit }
        return true
    }

    private fun releaseLayer(view: View) {
        val lease = leases[view] ?: return
        lease.owners.remove(this)
        if (lease.owners.isEmpty()) {
            leases.remove(view)
            if (view.layerType == View.LAYER_TYPE_HARDWARE) {
                view.setLayerType(View.LAYER_TYPE_NONE, null)
            }
        }
    }

    fun release() {
        val registered = observer
        val active = registered?.takeIf { it.isAlive } ?: source?.viewTreeObserver
        active?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(layoutListener)
        observer = null
        for (view in owned) releaseLayer(view)
        owned.clear()
        desired.clear()
        changedByApp.clear()
        source = null
        glass = null
    }
}
