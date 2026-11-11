package com.example.liquidglass

import android.app.Activity
import android.graphics.Color
import android.os.SystemClock
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class LiquidGlassTouchTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun shortPressClicksOnce() = withGlass { fixture ->
        val clicks = AtomicInteger()
        onMain { fixture.glass.setOnClickListener { clicks.incrementAndGet() } }
        touch(fixture, MotionEvent.ACTION_DOWN)
        touch(fixture, MotionEvent.ACTION_UP)
        instrumentation.waitForIdleSync()
        assertEquals(1, clicks.get())
    }

    @Test
    fun tooltipLongPressDoesNotClick() = withGlass { fixture ->
        val clicks = AtomicInteger()
        onMain {
            fixture.glass.tooltipText = TOOLTIP
            fixture.glass.setOnClickListener { clicks.incrementAndGet() }
        }
        touch(fixture, MotionEvent.ACTION_DOWN)
        awaitLongPress()
        assertTrue(tooltipIsShowing())
        touch(fixture, MotionEvent.ACTION_UP)
        instrumentation.waitForIdleSync()
        assertEquals(0, clicks.get())
    }

    @Test
    fun consumedLongClickDoesNotClick() = withGlass { fixture ->
        val clicks = AtomicInteger()
        val longClicks = AtomicInteger()
        onMain {
            fixture.glass.setOnClickListener { clicks.incrementAndGet() }
            fixture.glass.setOnLongClickListener { longClicks.incrementAndGet(); true }
        }
        touch(fixture, MotionEvent.ACTION_DOWN)
        awaitLongPress()
        touch(fixture, MotionEvent.ACTION_UP)
        instrumentation.waitForIdleSync()
        assertEquals(1, longClicks.get())
        assertEquals(0, clicks.get())
    }

    @Test
    fun cancelRemovesPendingTooltipAndClick() = withGlass { fixture ->
        val clicks = AtomicInteger()
        onMain {
            fixture.glass.tooltipText = TOOLTIP
            fixture.glass.setOnClickListener { clicks.incrementAndGet() }
        }
        touch(fixture, MotionEvent.ACTION_DOWN)
        touch(fixture, MotionEvent.ACTION_CANCEL)
        awaitLongPress()
        assertFalse(tooltipIsShowing())
        assertEquals(0, clicks.get())
    }

    @Test
    fun movingOutsideAndBackCancelsClick() = withGlass { fixture ->
        val clicks = AtomicInteger()
        onMain { fixture.glass.setOnClickListener { clicks.incrementAndGet() } }
        touch(fixture, MotionEvent.ACTION_DOWN)
        touch(fixture, MotionEvent.ACTION_MOVE, outside = true)
        touch(fixture, MotionEvent.ACTION_MOVE)
        touch(fixture, MotionEvent.ACTION_UP)
        instrumentation.waitForIdleSync()
        assertEquals(0, clicks.get())
    }

    @Test
    fun disabledGlassDoesNotClickOrScale() = withGlass { fixture ->
        val clicks = AtomicInteger()
        onMain {
            fixture.glass.setOnClickListener { clicks.incrementAndGet() }
            fixture.glass.isEnabled = false
        }
        touch(fixture, MotionEvent.ACTION_DOWN)
        SystemClock.sleep(200)
        onMain { assertEquals(1f, fixture.glass.scaleX, 0.001f) }
        touch(fixture, MotionEvent.ACTION_UP)
        instrumentation.waitForIdleSync()
        assertEquals(0, clicks.get())
    }

    @Test
    fun childTooltipKeepsNativeTouchDispatch() = withGlass { fixture ->
        val clicks = AtomicInteger()
        onMain {
            fixture.glass.setOnClickListener { clicks.incrementAndGet() }
            fixture.glass.addView(TextView(fixture.activity).apply {
                text = "Child tooltip"
                tooltipText = TOOLTIP
            }, FrameLayout.LayoutParams(-1, -1))
        }
        touch(fixture, MotionEvent.ACTION_DOWN)
        awaitLongPress()
        assertTrue(tooltipIsShowing())
        touch(fixture, MotionEvent.ACTION_UP)
        instrumentation.waitForIdleSync()
        assertEquals(0, clicks.get())
    }

    @Test
    fun disablingPressEffectsDoesNotDisableTooltip() = withGlass { fixture ->
        onMain {
            fixture.glass.enablePressEffect = false
            fixture.glass.tooltipText = TOOLTIP
        }
        touch(fixture, MotionEvent.ACTION_DOWN)
        awaitLongPress()
        assertTrue(tooltipIsShowing())
        touch(fixture, MotionEvent.ACTION_UP)
    }

    // 独立窗口避免 Demo 的后台动效和性能监控干扰测试。
    private fun withGlass(check: (Fixture) -> Unit) {
        ActivityScenario.launch(GlassTestActivity::class.java).use { scenario ->
            lateinit var fixture: Fixture
            scenario.onActivity { activity ->
                val root = FrameLayout(activity).apply { setBackgroundColor(Color.WHITE) }
                val glass = LiquidGlassView(activity).apply {
                    enableDynamicBackground = false
                    enableAdaptiveTint = false
                }
                val density = activity.resources.displayMetrics.density
                root.addView(glass, FrameLayout.LayoutParams(
                    (320 * density).toInt(), (120 * density).toInt(), Gravity.CENTER
                ))
                activity.setContentView(root)
                fixture = Fixture(activity, glass)
            }
            instrumentation.waitForIdleSync()
            check(fixture)
        }
    }

    private fun touch(fixture: Fixture, action: Int, outside: Boolean = false) = onMain {
        val point = IntArray(2)
        val origin = IntArray(2)
        fixture.glass.getLocationOnScreen(point)
        fixture.activity.window.decorView.getLocationOnScreen(origin)
        if (action == MotionEvent.ACTION_DOWN) fixture.downTime = SystemClock.uptimeMillis()
        val x = (point[0] - origin[0] + fixture.glass.width / 2 +
            if (outside) fixture.glass.width + 100 else 0).toFloat()
        val y = (point[1] - origin[1] + fixture.glass.height / 2).toFloat()
        val event = MotionEvent.obtain(fixture.downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        fixture.activity.window.decorView.dispatchTouchEvent(event)
        event.recycle()
    }

    private fun awaitLongPress() {
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 250L)
        instrumentation.waitForIdleSync()
    }

    private fun tooltipIsShowing(): Boolean {
        var showing = false
        onMain { showing = WindowInspector.getGlobalWindowViews().any { containsTooltip(it) } }
        return showing
    }

    private fun containsTooltip(view: View): Boolean {
        if (view is TextView && view.isShown && view.text.toString() == TOOLTIP) return true
        if (view is ViewGroup) for (i in 0 until view.childCount) {
            if (containsTooltip(view.getChildAt(i))) return true
        }
        return false
    }

    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private data class Fixture(val activity: Activity, val glass: LiquidGlassView, var downTime: Long = 0)
    private companion object { const val TOOLTIP = "Glass touch regression tooltip" }
}
