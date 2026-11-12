package com.example.liquidglass

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ScrollView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 31)
class WebViewBackdropTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun defaultBackdropCachesWebViewAndStopsStaticRedraws() = withBackdrop { fixture ->
        await("WebView hardware cache") { fixture.web.layerType == View.LAYER_TYPE_HARDWARE }
        assertStatic(fixture.web)
    }

    @Test
    fun tallWebViewCachesViewportContainer() = withBackdrop(scroll = true) { fixture ->
        await("Bounded scroll cache") {
            fixture.web.height > fixture.root.height * 5 &&
                fixture.scroll?.layerType == View.LAYER_TYPE_HARDWARE
        }
        onMain { assertEquals(View.LAYER_TYPE_NONE, fixture.web.layerType) }
        assertStatic(fixture.web)
    }

    @Test
    fun sharedCacheIsRestoredAfterLastGlassDetaches() = withBackdrop(glassCount = 2) { fixture ->
        await("Both glass views sampled") {
            fixture.glasses.all { it.lastFrameStats?.effectName?.startsWith("GPU") == true }
        }
        onMain {
            fixture.root.removeView(fixture.glasses[0])
            assertEquals(View.LAYER_TYPE_HARDWARE, fixture.web.layerType)
            fixture.root.removeView(fixture.glasses[1])
            assertEquals(View.LAYER_TYPE_NONE, fixture.web.layerType)
        }
    }

    @Test
    fun changingBackdropSourceRestoresPreviousLayer() = withBackdrop { fixture ->
        await("Initial cache") { fixture.web.layerType == View.LAYER_TYPE_HARDWARE }
        onMain {
            val replacement = FrameLayout(fixture.root.context)
            fixture.root.addView(replacement, 0, FrameLayout.LayoutParams(-1, -1))
            fixture.glasses.single().backdropSource = replacement
        }
        await("Previous source released") { fixture.web.layerType == View.LAYER_TYPE_NONE }
    }

    @Test
    fun explicitSoftwareLayerIsPreserved() = withBackdrop(layerType = View.LAYER_TYPE_SOFTWARE) { fixture ->
        await("GPU backdrop sampled") { fixture.glasses.single().lastFrameStats != null }
        onMain {
            assertEquals(View.LAYER_TYPE_SOFTWARE, fixture.web.layerType)
            fixture.root.removeView(fixture.glasses.single())
            assertEquals(View.LAYER_TYPE_SOFTWARE, fixture.web.layerType)
        }
    }

    @Test
    fun explicitHardwareLayerIsPreserved() = withBackdrop(layerType = View.LAYER_TYPE_HARDWARE) { fixture ->
        await("GPU backdrop sampled") { fixture.glasses.single().lastFrameStats != null }
        onMain {
            fixture.root.removeView(fixture.glasses.single())
            assertEquals(View.LAYER_TYPE_HARDWARE, fixture.web.layerType)
        }
    }

    @Test
    fun applicationCanRemoveManagedLayer() = withBackdrop { fixture ->
        await("Initial cache") { fixture.web.layerType == View.LAYER_TYPE_HARDWARE }
        onMain {
            fixture.web.setLayerType(View.LAYER_TYPE_NONE, null)
            fixture.root.requestLayout()
        }
        SystemClock.sleep(250)
        onMain { fixture.root.requestLayout() }
        SystemClock.sleep(250)
        onMain {
            assertEquals(View.LAYER_TYPE_NONE, fixture.web.layerType)
            fixture.root.removeView(fixture.glasses.single())
            assertEquals(View.LAYER_TYPE_NONE, fixture.web.layerType)
        }
    }

    @Test
    fun applicationLayerChangeIsPreserved() = withBackdrop { fixture ->
        await("Initial cache") { fixture.web.layerType == View.LAYER_TYPE_HARDWARE }
        onMain {
            fixture.web.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            fixture.root.requestLayout()
        }
        SystemClock.sleep(250)
        onMain {
            fixture.root.removeView(fixture.glasses.single())
            assertEquals(View.LAYER_TYPE_SOFTWARE, fixture.web.layerType)
        }
    }

    @Test
    fun webViewAddedAfterFirstCaptureIsCached() = withBackdrop(late = true) { fixture ->
        await("Initial capture without WebView") { fixture.glasses.single().lastFrameStats != null }
        onMain { fixture.root.addView(fixture.web, 0, FrameLayout.LayoutParams(-1, -1)) }
        await("Late WebView cached") { fixture.web.layerType == View.LAYER_TYPE_HARDWARE }
        assertStatic(fixture.web)
    }

    @Test
    fun sharedAncestorContainingGlassIsNotCached() = withBackdrop(nested = true) { fixture ->
        await("WebView cache below shared ancestor") { fixture.web.layerType == View.LAYER_TYPE_HARDWARE }
        onMain {
            assertEquals(View.LAYER_TYPE_NONE, fixture.panel?.layerType)
            assertEquals(View.LAYER_TYPE_NONE, fixture.root.layerType)
        }
    }

    @Test
    fun switchingToCpuReleasesGpuBackdropCache() = withBackdrop { fixture ->
        await("Initial cache") { fixture.web.layerType == View.LAYER_TYPE_HARDWARE }
        onMain {
            fixture.glasses.single().apply {
                enableChromaticAberration = false
                useHardwareBlurWhenPossible = false
            }
        }
        await("CPU path released cache") { fixture.web.layerType == View.LAYER_TYPE_NONE }
    }

    @Test
    fun dialogGpuBackdropBlursContentAndReleasesCache() = withBackdrop { fixture ->
        lateinit var glass: LiquidGlassView
        lateinit var dialog: Dialog
        onMain {
            fixture.root.removeView(fixture.glasses.single())
            fixture.web.loaded.set(false)
            // 黑白细条纹用于区分真实模糊、空白取样和透出原始背景。
            fixture.web.loadDataWithBaseURL(null,
                "<meta name='viewport' content='width=device-width,initial-scale=1'>" +
                    "<style>body{margin:0;height:100vh;background:" +
                    "repeating-linear-gradient(#000 0px,#000 4px,#fff 4px,#fff 8px)}</style>",
                "text/html", "utf-8", null)
            val activity = fixture.root.context as Activity
            val content = FrameLayout(activity)
            glass = LiquidGlassView(activity).apply {
                backdropSource = fixture.root
                useShaderPipeline = false
                enableChromaticAberration = false
                enableChromaticDispersion = false
                enableAdaptiveTint = false
                enableDynamicBackground = true
                enableShadow = false
                enableEdgeHighlight = false
                glassTint = Color.TRANSPARENT
                saturation = 1f
                blurAmount = 1f
            }
            val density = activity.resources.displayMetrics.density
            content.addView(glass, FrameLayout.LayoutParams(
                (260 * density).toInt(), (64 * density).toInt(), Gravity.CENTER
            ))
            dialog = Dialog(activity).apply {
                setContentView(content)
                window?.apply {
                    setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                    setDimAmount(0f)
                    setWindowAnimations(0)
                }
                show()
                window?.setLayout(-1, -1)
            }
        }
        try {
            await("Cross-window GPU blur") { glass.lastFrameStats?.effectName == "GPU Blur" }
            assertStatic(fixture.web, glass)
            val position = IntArray(2)
            var width = 0
            var height = 0
            onMain {
                glass.getLocationOnScreen(position)
                width = glass.width
                height = glass.height
            }
            val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {
                val patchSize = minOf(100, width / 2, height / 2)
                val x = position[0] + width / 2 - patchSize / 2
                val reference = patchStats(screenshot, x, position[1] - patchSize - 20, patchSize)
                val blurred = patchStats(screenshot, x, position[1] + height / 2 - patchSize / 2, patchSize)
                if (InstrumentationRegistry.getArguments().getString("captureScreenshots") == "true") {
                    val directory = instrumentation.targetContext.filesDir
                    File(directory, "gpu-cross-window.png").outputStream().use {
                        screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    File(directory, "gpu-cross-window.json").writeText(JSONObject().apply {
                        put("api", Build.VERSION.SDK_INT)
                        put("effect", glass.lastFrameStats?.effectName)
                        put("patchSize", patchSize)
                        put("referenceMean", reference[0])
                        put("referenceVariance", reference[1])
                        put("blurredMean", blurred[0])
                        put("blurredVariance", blurred[1])
                    }.toString(2))
                }
                assertTrue("Reference stripes must be visible", reference[1] > 2000.0)
                assertTrue("GPU backdrop must not be black or white", blurred[0] in 60.0..220.0)
                assertTrue("GPU backdrop must blur the stripes", blurred[1] < reference[1] / 4.0)
            } finally {
                screenshot.recycle()
            }
        } finally {
            onMain { dialog.dismiss() }
        }
        onMain { assertEquals(View.LAYER_TYPE_NONE, fixture.web.layerType) }
    }

    private fun patchStats(bitmap: Bitmap, x: Int, y: Int, size: Int): DoubleArray {
        assertTrue("Screenshot patch must be on screen",
            size > 0 && x >= 0 && y >= 0 && x + size <= bitmap.width && y + size <= bitmap.height)
        var sum = 0.0
        var squares = 0.0
        for (dy in 0 until size) for (dx in 0 until size) {
            val value = Color.red(bitmap.getPixel(x + dx, y + dy)).toDouble()
            sum += value
            squares += value * value
        }
        val count = (size * size).toDouble()
        val mean = sum / count
        return doubleArrayOf(mean, maxOf(0.0, squares / count - mean * mean))
    }

    private fun assertStatic(web: CountingWebView, focusedView: View = web) {
        await("Page visually committed") { web.loaded.get() }
        // 页面提交后仍可能有栅格化尾帧，要求可见窗口连续两秒不再重绘。
        val deadline = SystemClock.uptimeMillis() + 12000L
        var count = web.draws.get()
        var quietSince = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(100)
            val current = web.draws.get()
            if (current != count) {
                count = current
                quietSince = SystemClock.uptimeMillis()
            } else if (SystemClock.uptimeMillis() - quietSince >= 2000L) {
                onMain {
                    assertTrue("WebView must remain visible", web.isShown)
                    assertTrue("Window must remain focused", focusedView.hasWindowFocus())
                }
                return
            }
        }
        org.junit.Assert.fail("Static WebView did not settle: ${web.draws.get()} hardware draws")
    }

    private fun withBackdrop(
        scroll: Boolean = false,
        nested: Boolean = false,
        late: Boolean = false,
        layerType: Int = View.LAYER_TYPE_NONE,
        glassCount: Int = 1,
        check: (Fixture) -> Unit
    ) {
        ActivityScenario.launch(GlassTestActivity::class.java).use { scenario ->
            lateinit var fixture: Fixture
            scenario.onActivity { activity ->
                val root = FrameLayout(activity)
                val web = CountingWebView(activity)
                if (layerType != View.LAYER_TYPE_NONE) web.setLayerType(layerType, null)
                val panel = if (nested) FrameLayout(activity).also {
                    root.addView(it, FrameLayout.LayoutParams(-1, -1))
                } else null
                val scroller = if (scroll) ScrollView(activity).also {
                    it.addView(web, FrameLayout.LayoutParams(-1, -2))
                    root.addView(it, FrameLayout.LayoutParams(-1, -1))
                } else null
                if (!scroll && !late) (panel ?: root).addView(web, FrameLayout.LayoutParams(-1, -1))
                val density = activity.resources.displayMetrics.density
                val glasses = List(glassCount) { index ->
                    LiquidGlassView(activity).apply {
                        enableAdaptiveTint = false
                        enableDynamicBackground = false
                        // API 31–32 的 GPU 管线不支持色差，关闭后才能覆盖真实硬件取样。
                        if (Build.VERSION.SDK_INT < 33) enableChromaticAberration = false
                        if (nested) backdropSource = root
                    }.also { glass ->
                        (panel ?: root).addView(glass, FrameLayout.LayoutParams(
                            (260 * density).toInt(), (64 * density).toInt(), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                        ).apply { bottomMargin = ((40 + 80 * index) * density).toInt() })
                    }
                }
                activity.setContentView(root)
                // 无脚本、网络和动画的长页面，排除页面自身重绘。
                val rows = if (scroll) 200 else 24
                val body = (0 until rows).joinToString("") { "<div style='height:72px;border-bottom:1px solid #ccc'>Static row $it ABC 123</div>" }
                web.loadDataWithBaseURL(null, "<meta name='viewport' content='width=device-width,initial-scale=1'><body>$body</body>", "text/html", "utf-8", null)
                fixture = Fixture(root, web, scroller, panel, glasses)
            }
            try {
                check(fixture)
            } finally {
                scenario.onActivity {
                    fixture.root.removeAllViews()
                    fixture.web.destroy()
                }
            }
        }
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10000L
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            onMain { ready = condition() }
            if (ready) return
            SystemClock.sleep(50)
        }
        onMain { assertTrue(message, condition()) }
    }

    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private class CountingWebView(context: Context) : WebView(context) {
        val draws = AtomicInteger()
        val loaded = AtomicBoolean()
        init {
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    view.postVisualStateCallback(1L, object : WebView.VisualStateCallback() {
                        override fun onComplete(requestId: Long) { loaded.set(true) }
                    })
                }
            }
        }
        override fun onDraw(canvas: Canvas) {
            if (canvas.isHardwareAccelerated) draws.incrementAndGet()
            super.onDraw(canvas)
        }
    }

    private data class Fixture(
        val root: FrameLayout,
        val web: CountingWebView,
        val scroll: ScrollView?,
        val panel: FrameLayout?,
        val glasses: List<LiquidGlassView>
    )
}
