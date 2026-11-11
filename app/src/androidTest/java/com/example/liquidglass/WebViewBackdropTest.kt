package com.example.liquidglass

import android.content.Context
import android.graphics.Canvas
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 33)
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

    private fun assertStatic(web: CountingWebView) {
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
                    assertTrue("Window must remain focused", web.hasWindowFocus())
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
