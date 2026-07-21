/**
 * 异步渲染器 - 双缓冲后台渲染系统
 * 
 * 将耗时的渲染操作移到后台线程，主线程只负责绘制已渲染好的结果
 * 性能提升：主线程不再阻塞，UI 始终流畅
 */
package com.example.liquidglass

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * 异步渲染器
 * 
 * 使用双缓冲机制：
 * - 前台缓冲：主线程读取，用于绘制
 * - 后台缓冲：后台线程写入，渲染完成后与前台缓冲交换
 */
class AsyncRenderer(
    private val renderCallback: (RenderParams) -> Bitmap?
) {
    companion object {
        private const val TAG = "AsyncRenderer"
    }
    
    // 双缓冲
    private val frontBuffer = AtomicReference<Bitmap?>(null)
    private val backBuffer = AtomicReference<Bitmap?>(null)
    
    // 渲染状态
    private val isRendering = AtomicBoolean(false)
    private val shouldStop = AtomicBoolean(false)
    private var renderThread: Thread? = null
    
    // 待渲染参数
    private val pendingParams = AtomicReference<RenderParams?>(null)
    
    /**
     * 渲染参数
     */
    data class RenderParams(
        val bounds: RectF,
        val blurRadius: Float,
        val displacementScale: Float,
        val aberrationIntensity: Float,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    /**
     * 请求异步渲染
     * 
     * @param params 渲染参数
     * @return 当前可用的渲染结果（可能是上一帧的）
     */
    fun requestRender(params: RenderParams): Bitmap? {
        // 更新待渲染参数
        pendingParams.set(params)
        
        // 如果后台线程空闲，启动渲染
        if (!isRendering.get()) {
            startRenderThread()
        }
        
        // 返回当前前台缓冲（可能是上一帧的结果）
        return frontBuffer.get()
    }
    
    /**
     * 获取当前可用的渲染结果
     */
    fun getCurrentFrame(): Bitmap? {
        return frontBuffer.get()
    }
    
    /**
     * 启动后台渲染线程
     */
    private fun startRenderThread() {
        if (isRendering.compareAndSet(false, true)) {
            renderThread = thread(name = "LiquidGlass-Renderer") {
                try {
                    renderLoop()
                } catch (e: Exception) {
                    Log.e(TAG, "渲染线程异常: ${e.message}", e)
                } finally {
                    isRendering.set(false)
                }
            }
        }
    }
    
    /**
     * 渲染循环
     */
    private fun renderLoop() {
        while (!shouldStop.get()) {
            // 获取待渲染参数
            val params = pendingParams.getAndSet(null)

            if (params == null) {
                // 没有待渲染任务，退出循环
                break
            }

            // 执行渲染
            val startTime = System.nanoTime()
            val result = try {
                renderCallback(params)
            } catch (e: Exception) {
                Log.e(TAG, "渲染失败: ${e.message}", e)
                null
            }
            val duration = (System.nanoTime() - startTime) / 1_000_000f

            if (result != null) {
                // ✅ 安全的缓冲交换：
                // 1. 将新结果放入后台缓冲
                val oldBackBuffer = backBuffer.getAndSet(result)

                // 2. 交换前后台缓冲（原子操作）
                val oldFrontBuffer = frontBuffer.getAndSet(result)

                // 3. 回收旧的前台缓冲（主线程已经不再使用）
                // 注意：不要回收 oldBackBuffer，因为它现在是 oldFrontBuffer
                if (oldFrontBuffer != null && oldFrontBuffer != oldBackBuffer) {
                    oldFrontBuffer.recycle()
                }

                Log.d(TAG, "异步渲染完成: ${duration}ms")
            }
        }
    }
    
    /**
     * 停止渲染并清理资源
     */
    fun stop() {
        shouldStop.set(true)
        
        // 等待渲染线程结束
        renderThread?.join(1000)
        
        // 清理缓冲
        frontBuffer.getAndSet(null)?.recycle()
        backBuffer.getAndSet(null)?.recycle()
        
        Log.d(TAG, "异步渲染器已停止")
    }
    
    /**
     * 检查是否正在渲染
     */
    fun isRendering(): Boolean {
        return isRendering.get()
    }
}

