![frontPhoto](assets/banner.png)
<div align="center">

# LiquidGlass for Android

**iOS 26 Liquid Glass — for the Android View system.**<br>
Real SDF refraction, physical dispersion and sensor-driven specular, in a plain `View`.<br>
Drop it into the View hierarchy you already have. **No Compose migration required.**

**把 iOS 26 的液态玻璃搬到 Android View 体系** —— 真实 SDF 折射 / 物理色散 / 重力感应高光，<br>
一个普通 `View`，直接放进你现有的 View 层级，**不需要迁移到 Compose**。

[![Download demo APK](https://img.shields.io/badge/Demo%20APK-download-success?logo=android&logoColor=white)](https://github.com/QWEA0/Liquid-Glass-Android/releases/latest)
[![JitPack](https://jitpack.io/v/QWEA0/Liquid-Glass-Android.svg)](https://jitpack.io/#QWEA0/Liquid-Glass-Android)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%20View-green.svg)](https://developer.android.com)
[![API](https://img.shields.io/badge/API-24%2B-brightgreen.svg)](https://android-arsenal.com/api?level=24)

<br>

<img src="assets/hero-lens-detail.jpg" alt="Edge compression ring and chromatic dispersion, at 1:1 pixels" width="820">

<sub>1:1 pixels — the icon grid is <b>compressed into the rim</b> by the SDF lens, with per-channel dispersion fringing the edge.</sub>

<br><br>

<img src="assets/hero-refraction.gif" alt="Liquid glass dragged across an iOS-style home screen" width="272">
&nbsp;&nbsp;
<img src="assets/hero-home-screen.jpg" alt="Liquid glass over an iOS-style home screen" width="272">

<sub>Recorded on a physical device (Android 16, API 36) — not a mockup.</sub>

**[⬇ Download the demo APK](https://github.com/QWEA0/Liquid-Glass-Android/releases/latest)** — 5 MB, API 24+, no build required.

<br>

[English](#english) | [中文](#chinese)

</div>

---

<a name="english"></a>

## 🌊 LiquidGlass Android

A high-performance Liquid Glass / glassmorphism component for the Android **View system**, featuring
real-time backdrop blur, SDF refraction, chromatic dispersion and liquid-like interactive effects.

> **Why another one?** The existing Android Liquid Glass libraries target Jetpack Compose.
> Compose adoption is largely *incremental* — most non-greenfield apps run a hybrid tree with
> Compose islands inside an existing View hierarchy — so if the screen you want glass on is still
> a `ViewGroup`, a Compose-only library doesn't help you. `LiquidGlassView` is a `FrameLayout`
> subclass: add it to your layout, put your content inside, done. API 24+, with an AGSL fast path
> on API 33+. (Building greenfield in Compose? Use
> [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) — it's excellent.)

#### Which Liquid Glass library should I use?

These libraries target different view systems — pick by UI toolkit, not by feature list.

| The screen you're adding glass to | Use |
|---|---|
| Android **View / XML** layout, `ViewGroup` hierarchy | **this library** |
| **Jetpack Compose** (`@Composable`) | [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) |
| Must support **API 24–32** | **this library** (C++/NEON fallback; the Compose libraries need AGSL, API 33+) |
| Compose Multiplatform / shared iOS + Android UI | [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) |

If you're Compose-first, use Kyant0's — don't wrap this one in an `AndroidView`.

### ✨ Features

**Liquid Glass 2.0 (API 33+, single-pass AGSL lens pipeline)**

- **🔍 Real SDF Refraction** - True lens optics: edge compression ring driven by a live rounded-rect SDF (follows corner radius & shape in real time)
- **🌈 Physical Dispersion** - Per-channel refraction along the surface normal → spectral fringes at the edge
- **💡 Sensor-Driven Specular** - Normal-based highlights lit by a gravity-sensor light source (highlight moves as you tilt the device)
- **🌓 Adaptive Luminance Tint** - Continuously senses backdrop brightness, adapts glass tint and notifies your foreground content (light/dark)
- **🧪 Regular / Clear Materials** - Apple-style material variants (readability-first vs. media-transparent with dimming layer)
- **🫧 Liquid Merge** - Two glass shapes blend with smin like mercury (`setSecondaryShape`)
- **👆 Liquid Press** - Press boosts refraction and bulges the glass under your finger
- **🌫️ Progressive Blur** - `ScrollEdgeBlurView`: scroll-edge effect fading from sharp to blurred
- **♿ Accessibility Fallback** - Opaque material under high-contrast setting; honors "remove animations" and battery saver

**Classic pipeline (API 24+)**

- **🎨 Real-time Backdrop Blur** - Dynamic background blur with adjustable radius and saturation
- **🌈 Chromatic Aberration** - RGB channel separation effect for a premium glass look
- **✨ Edge Highlights** - Dynamic light reflections based on touch position
- **⚡ High Performance** - Optimized with native C++ (NEON SIMD) and smart caching
- **🎯 Easy Integration** - Simple XML attributes and Kotlin API
- **🔧 Highly Customizable** - Fine-tune every aspect of the glass effect
- **🧩 Ready-made Widgets** - `LiquidGlassButton` / `LiquidGlassFab` / `LiquidGlassTabBar`: pre-wired glass button, FAB and tab bar with click handling and adaptive foreground colors

### 📱 Demo

<div align="center">

#### 🎬 Live drag & parameter tuning

<img src="assets/demo-glass-drag.gif" alt="Dragging the glass over a gradient backdrop" width="252">
<img src="assets/demo-glass-params.gif" alt="Tuning lens parameters live" width="252">

#### 🧪 Regular vs. Clear material

<img src="assets/glass-regular-material.jpg" alt="Regular material — readability first" width="252">
<img src="assets/glass-clear-material.jpg" alt="Clear material — media transparent" width="252">

</div>

Run the sample app for the full playground (4 scenes + a live parameter drawer), or launch the
hero scene used for the screenshots at the top:

```bash
adb shell am start -n com.example.liquidglass/com.example.liquidglass.HeroShowcaseActivity
```

### 📦 Installation (JitPack)

Add JitPack to your `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

Add the dependency:

```kotlin
dependencies {
    implementation("com.github.QWEA0:liquidglass:v2.0.2")
}
```

The library ships as the `:liquidglass` module (AAR with prebuilt native `.so` for arm64-v8a / armeabi-v7a); the `:app` module in this repo is the demo.

### 🚀 Quick Start

#### Complete working example

Everything needed to get one glass pill on screen. `LiquidGlassView` samples whatever is
drawn **behind** it, so the background content must come first in an overlapping parent.

```xml
<!-- res/layout/activity_main.xml -->
<FrameLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <!-- Anything you want refracted. Detail matters: refraction is invisible
         over a flat colour or a smooth gradient. -->
    <ImageView
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:scaleType="centerCrop"
        android:src="@drawable/your_background" />

    <com.example.liquidglass.LiquidGlassView
        android:id="@+id/glass"
        android:layout_width="280dp"
        android:layout_height="96dp"
        android:layout_gravity="center"
        app:cornerRadius="999dp"
        app:glassMaterial="regular"
        app:refractionHeight="66dp"
        app:bevelWidth="14dp"
        app:dispersionStrength="0.12"
        app:sensorHighlight="true"
        app:adaptiveTint="true">

        <!-- Your content goes inside — it's a FrameLayout -->
        <TextView
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:gravity="center"
            android:text="Liquid Glass"
            android:textColor="#FFFFFF"
            android:textSize="20sp" />

    </com.example.liquidglass.LiquidGlassView>
</FrameLayout>
```

```kotlin
import com.example.liquidglass.GlassMaterial
import com.example.liquidglass.LiquidGlassView

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val glass = findViewById<LiquidGlassView>(R.id.glass)

        // REQUIRED whenever the backdrop moves, or the glass itself moves.
        // Defaults to false — forgetting this is the #1 integration mistake:
        // the backdrop is captured once and the glass looks frozen.
        glass.enableDynamicBackground = true
    }
}
```

That is the whole integration. Everything below is optional tuning.

#### Tuning in code

```kotlin
// --- Liquid Glass 2.0 lens pipeline (API 33+, silently no-ops below) ---
glass.material = GlassMaterial.REGULAR      // REGULAR (readability) | CLEAR (over media)
glass.refractionHeight = 200f               // dominant knob: how much lens. px, 0-300
glass.bevelWidth = 40f                      // glass "thickness" band. px, 2-200
glass.dispersionStrength = 0.10f            // rim spectral fringe, 0-1. >0.25 reads as rainbow
glass.enableSensorHighlight = true          // highlight tracks device tilt
glass.enableAdaptiveTint = true             // tint follows backdrop luminance

// Flip your foreground colour when the backdrop goes light/dark
glass.glassAppearanceListener = { isOverLight ->
    label.setTextColor(if (isOverLight) Color.BLACK else Color.WHITE)
}

// Liquid merge — two shapes blend with a smooth-min, like mercury (API 33+)
glass.setPrimaryShape(dockRect, cornerRadiusPx = 36f)
glass.setSecondaryShape(bubbleRect, cornerRadiusPx = 44f, smoothing = 40f)

// --- Classic pipeline (API 24+) ---
glass.blurAmount = 0.0625f
glass.saturation = 140f
glass.aberrationIntensity = 2f
glass.blurMethod = BlurMethod.SMART         // see the enum table below for valid names
```

### 🎛️ Customization Options

All properties live on `LiquidGlassView`. Properties marked **33+** belong to the AGSL lens
pipeline; below API 33 they are accepted and silently ignored — no exception is thrown.

| Property | Type | Default | Range | Notes |
|---|---|---|---|---|
| `enableDynamicBackground` | Boolean | `false` | — | **Set to `true`** if the backdrop or the glass moves. Otherwise the backdrop is captured once. |
| `cornerRadius` | Float | `999f` | px | `999f` = pill. The SDF tracks this, so refraction follows the radius. |
| `material` | `GlassMaterial` | `REGULAR` | — | **33+** `REGULAR` = readability first · `CLEAR` = over media |
| `refractionHeight` | Float | `200f` | 0–300 px | **33+** Dominant knob for lens strength |
| `bevelWidth` | Float | `40f` | 2–200 px | **33+** Width of the edge "thickness" band |
| `dispersionStrength` | Float | `0.10f` | 0–1 | **33+** Rim spectral fringe. Above ~0.25 reads as rainbow |
| `enableSensorHighlight` | Boolean | `false` | — | **33+** Specular follows device tilt (gravity sensor) |
| `enableAdaptiveTint` | Boolean | `false` | — | **33+** Tint adapts to backdrop luminance |
| `useShaderPipeline` | Boolean | `true` | — | `false` forces the classic pipeline even on 33+ |
| `useHardwareBlurWhenPossible` | Boolean | `true` | — | `false` also disables the lens pipeline, not just hardware blur |
| `debugApiLevelCap` | Int | `MAX_VALUE` | API level | Debug only: clamp the pipeline tier to preview older devices (36 AGSL extras / 33 lens / 31 classic GPU / below = CPU) |
| `effectiveApiLevel` | Int | — | read-only | `min(device SDK, debugApiLevelCap)` — the tier actually in use |
| `blurAmount` | Float | `0.0625f` | 0–1 | Blur radius as a fraction of view size |
| `saturation` | Float | `140f` | percent | 100 = unchanged |
| `aberrationIntensity` | Float | `2f` | — | Classic RGB-separation strength |
| `displacementScale` | Float | `70f` | — | Classic edge distortion |
| `elasticity` | Float | `0.15f` | — | Touch spring response |
| `blurMethod` | `BlurMethod` | `SMART` | — | See enum table below |
| `enableBackdropBlur` | Boolean | `true` | — | |
| `enableChromaticAberration` | Boolean | `true` | — | Classic pipeline |
| `enableChromaticDispersion` | Boolean | `false` | — | Physical dispersion (classic pipeline) |
| `enableEdgeHighlight` | Boolean | `true` | — | |
| `enableShadow` | Boolean | `false` | — | |
| `edgeHighlightBorderWidth` | Float | `1.5f` | px | |
| `edgeHighlightOpacity` | Float | `100f` | 0–100 | |
| `accessibilityMode` | `GlassAccessibilityMode` | `AUTO` | — | `AUTO` honours system high-contrast / reduced-motion / battery saver |
| `downsampleScale` | Int | `2` | — | Higher = faster, softer |
| `highQualityBlur` | Boolean | `false` | — | |
| `collectFrameStats` | Boolean | `true` | — | Profiling aid; turn off in production |
| `glassAppearanceListener` | `((Boolean) -> Unit)?` | `null` | — | Fires when backdrop luminance flips light/dark |
| `backdropSource` | `View?` | `null` | — | Backdrop to capture. `null` = the direct parent. Set it to read a view anywhere in the tree — keeps the GPU pipeline |

#### XML attributes

Declared under the `LiquidGlassView` styleable, namespace `app`:

`displacementScale` · `blurAmount` · `saturation` · `aberrationIntensity` · `elasticity` ·
`cornerRadius` (dimension) · `glassMaterial` (`regular` | `clear`) · `bevelWidth` (dimension) ·
`refractionHeight` (dimension) · `dispersionStrength` · `sensorHighlight` · `adaptiveTint` ·
`backdropSourceId` (reference — id of the backdrop view; omit for the direct parent)

#### Enum values

Exact spelling — invented constants are the usual cause of a failed build.

| Enum | Valid values |
|---|---|
| `BlurMethod` | `BOX_BLUR` · `BOX_BLUR_CPP` · `IIR_GAUSSIAN` · `IIR_GAUSSIAN_NEON` · `SMART` · `DOWNSAMPLE` |
| `GlassMaterial` | `REGULAR` · `CLEAR` |
| `GlassAccessibilityMode` | `AUTO` · `FORCE_FULL` · `FORCE_OPAQUE` |
| `ScrollEdgeBlurView.Edge` | `TOP` · `BOTTOM` |

#### Methods

| Signature | Notes |
|---|---|
| `setPrimaryShape(rect: RectF?, cornerRadiusPx: Float = cornerRadius)` | `null` = use the whole view |
| `setSecondaryShape(rect: RectF?, cornerRadiusPx: Float = 999f, smoothing: Float = 48f)` | **33+** Two shapes blend with smooth-min, like mercury |
| `setBackdropSource(source: View?)` | Capture any view instead of the direct parent — free of the hierarchy, **keeps the GPU pipeline** |
| `setCustomBackdropCapture(capture: (RectF) -> Bitmap?)` | Supply your own backdrop bitmap. Forces the CPU pipeline — prefer `setBackdropSource` |
| `refreshAccessibilityState()` | Re-read system accessibility settings |

`ScrollEdgeBlurView` — progressive scroll-edge blur: `edge`, `maxBlurRadius`,
`bindScrollView(view)`.

#### Ready-made widgets

All three are `LiquidGlassView` subclasses — every glass property above still applies, and
`setOnClickListener` works out of the box. With `enableAdaptiveTint = true` their foreground
(text / icon / indicator) automatically flips between light and dark to match the backdrop,
until you set an explicit color.

| Widget | Notes |
|---|---|
| `LiquidGlassButton` | Pill glass button with a centered label. XML: `android:text`, `android:textSize`, `android:textColor`. Kotlin: `text`, `setTextColor(color)`, `textView` |
| `LiquidGlassFab` | Circular 56dp floating button with a centered icon. XML: `android:src`, `app:glassIconTint`. Kotlin: `icon`, `setIconResource(id)`, `setIconTint(color)`, `imageView` |
| `LiquidGlassTabBar` | iOS 26-style tab bar: icon-over-label tabs, selection indicator is a real glass droplet that refracts the content under it, slides with liquid stretch and is finger-draggable. Kotlin: `setTabs(List<TabItem>)` / `setTabs(titles)`, `selectedIndex`, `onTabSelected`, `selectedTintColor`. XML: `app:glassTabEntries` (text-only) |

See [docs/LIQUID_GLASS_V2.md](docs/LIQUID_GLASS_V2.md) for the full lens-pipeline documentation.

> **Using an AI coding agent?** [llms.txt](llms.txt) is a condensed, machine-readable digest of
> this API, and [AGENTS.md](AGENTS.md) has an integration checklist plus the mistakes agents
> most often make with this library.

### ❓ FAQ

**How do I add iOS 26 Liquid Glass to an existing Android XML layout?**
Add the JitPack repo and the `:liquidglass` dependency, then put a
`com.example.liquidglass.LiquidGlassView` in your layout over some background content and
set `enableDynamicBackground = true`. Full snippet in [Quick Start](#-quick-start).

**How do I put glass over a `ScrollView` / `RecyclerView`?**
The glass captures its **direct parent**, so the scrolling content must be in that parent —
put the `ScrollView` and the glass in one `FrameLayout`, glass declared last, and set
`enableDynamicBackground = true` so it keeps up with the scroll. If they can't share a parent,
set `backdropSource` to the scrolling view instead: the glass then reads that view from
anywhere in the tree, the overlap is computed from screen coordinates, and scrolling it
repaints the glass automatically. Note that glass inside a `ScrollView` scrolling *with* the
content sees a fixed backdrop by definition — that's physically correct, not a bug.

**Can I put glass in a `Dialog` / `BottomSheetDialog` / `PopupWindow`?**
Yes — the demo app's **Sheet** scene is a working `BottomSheetDialog`. Three things differ
from the in-activity case:

1. **The backdrop must be `backdropSource`.** A dialog has its own window, so the glass's
   direct parent inside it is transparent and the default capture yields an empty (black)
   panel. Point it at the activity instead —
   `glass.backdropSource = activity.findViewById(android.R.id.content)`. The overlap is
   computed from screen coordinates, so cross-window works and the GPU pipeline is kept.
   Careful with `glass.apply { backdropSource = findViewById(…) }`: inside `apply` that
   resolves to `View.findViewById`, silently returns `null`, and falls back to the
   transparent parent.
2. **Don't animate the window.** A window animation (the default for `BottomSheetDialog`,
   and any `windowAnimationStyle`) is a SurfaceFlinger transform — the view tree never
   redraws, `getLocationOnScreen` never changes, and the refraction freezes on the last
   pre-animation frame while the surface slides. Disable it (`window.setWindowAnimations(0)`)
   and animate a view inside the window instead, calling `glass.invalidate()` each frame so
   the display list is re-recorded. Same for `BottomSheetBehavior` drags — it moves the sheet
   with `offsetTopAndBottom`, which doesn't re-record children, so invalidate from `onSlide`.
3. **Clear the window backgrounds,** or the glass sits on the dialog's opaque background.
   For `BottomSheetDialog` that means the window, `container`, `coordinator` and
   `design_bottom_sheet`; edge-to-edge has to come from the *theme* (`enableEdgeToEdge`),
   since the dialog reads it at construction. Also set `dimAmount` to 0 — the dim is drawn
   between the activity and the dialog, so the glass samples undimmed content and refracts
   brighter than its surroundings.

**Can I use this from Jetpack Compose?**
You can via `AndroidView`, but you shouldn't. Use
[Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) instead — it's
built for Compose and is more mature. This library exists for the View system.

**I added the view but I don't see any effect. Why?**
Three usual causes, in order of likelihood:
1. `enableDynamicBackground` is still `false`, so the backdrop was captured once.
2. Nothing is drawn behind the glass — it samples its parent's backdrop, so it needs a
   sibling painted before it in an overlapping parent.
3. The backdrop is a flat colour or a smooth gradient. **Refraction and dispersion are
   invisible without high-frequency detail.** Test over an icon grid, text, or a photo.

**Does it work below Android 13 (API 33)?**
Yes, down to API 24. Below 33 the AGSL lens pipeline is unavailable, so the library falls
back to a C++/NEON blur + chromatic aberration pipeline. The 2.0 properties are accepted
and ignored rather than throwing, so you don't need `Build.VERSION` guards.

**Is it free for commercial use?**
Yes. MIT — keep the copyright notice, nothing else required.

**What's the performance cost?**
On a 2024-class device the AGSL pipeline runs the lens in well under 1 ms per frame at 60 fps
(the demo app's overlay reports live `FrameStats`). The classic pipeline is heavier; tune
`downsampleScale` and `blurMethod` if you need headroom.

**Why is the artifact `com.github.QWEA0:liquidglass`?**
The `:liquidglass` module publishes under the group `com.github.QWEA0`, so that is the
coordinate JitPack serves. JitPack also aliases the same AAR as
`com.github.QWEA0:Liquid-Glass-Android:v2.0.2` — either resolves to the library. The
multi-module form `com.github.QWEA0.Liquid-Glass-Android:liquidglass` does **not** exist.

### 🏗️ Architecture

**Core Components:**
- `LiquidGlassView` - Main view component with touch interaction
- `GlassLensRenderer` - Single-pass AGSL lens pipeline: SDF refraction, dispersion, normal-lit specular, inner shadow, smin merge (API 33+)
- `GlassRuntimeEffects` - AGSL color filter / blend modes: vibrancy saturation, fused rim highlight, per-pixel adaptive tint (API 36+, auto fallback)
- `LightSourceController` - Gravity-sensor world-fixed light source for specular highlights
- `BackdropLuminanceMeter` - Backdrop brightness sampling for adaptive tint
- `ScrollEdgeBlurView` - Progressive blur overlay (scroll edge effect)
- `LiquidGlassButton` / `LiquidGlassFab` / `LiquidGlassTabBar` - Ready-made widgets built on `LiquidGlassView`
- `GlassAccessibility` - Reduce-transparency / reduce-motion / battery-saver detection
- `EnhancedBlurEffect` - Multi-algorithm blur engine (Box, IIR Gaussian, Downsample)
- `ChromaticAberrationEffect` - RGB channel separation processor
- `EdgeHighlightEffect` - Dynamic light reflection renderer (classic pipeline)
- `AsyncRenderer` - Background thread rendering for smooth performance

**Native Optimization:**
- `gauss_iir_neon.cpp` - ARM NEON SIMD accelerated IIR Gaussian blur
- `chromatic_aberration.cpp` - Hardware-accelerated RGB separation
- `boxblur.cpp` - Fast box blur implementation

### 📊 Performance

- **GPU Lens Pipeline (API 33+)**: single-pass AGSL via RenderEffect — zero bitmap allocation, zero readback; ~0.06 ms CPU record cost per frame, steady 60 fps measured on device
- **Optimized Rendering**: Smart caching with 3-layer strategy (backdrop → blur → final)
- **Native Acceleration**: ARM NEON SIMD for 4-8x performance boost (classic pipeline)
- **Adaptive Quality**: Automatic downsampling for smooth 60fps on mid-range devices
- **Memory Efficient**: Bitmap pooling and automatic resource recycling

### 🔧 Requirements

- **Min SDK**: 24 (Android 7.0) — classic pipeline
- **Liquid Glass 2.0 lens pipeline**: API 33+ (Android 13), automatic fallback below
- **AGSL color filter / blend enhancements**: API 36+ (Android 16), automatic — no API change
- **Target SDK**: 35 (Android 15), compiled against SDK 36
- **Language**: Kotlin
- **NDK**: Required for native blur acceleration

### 📦 Dependencies

```gradle
dependencies {
    implementation "androidx.core:core-ktx:1.12.0"
    implementation "androidx.appcompat:appcompat:1.6.1"
    implementation "com.google.android.material:material:1.11.0"
}
```

### 🛠️ Build

```bash
# Clone the repository
git clone https://github.com/yourusername/liquidglass-android.git

# Open in Android Studio
# Build and run the demo app
./gradlew assembleDebug
```

### 📄 License

Released under the [MIT License](LICENSE) — free for commercial use, no attribution required beyond keeping the copyright notice.

### 🙏 Credits

Inspired by the glassmorphism design trend and liquid-glass-react library.

---

<a name="chinese"></a>

## 🌊 LiquidGlass Android

一个面向 Android **View 体系**的高性能液态玻璃组件，具有实时背景模糊、SDF 折射、物理色散和液态交互特性。

> **为什么还要再造一个？** 现有的 Android Liquid Glass 库都是给 Jetpack Compose 用的。
> 而 Compose 的落地基本都是*渐进式*的——非全新项目多是混合树，Compose 岛屿嵌在既有的 View
> 层级里——所以只要你想加玻璃的那个界面还是 `ViewGroup`，纯 Compose 的库就帮不上忙。
> `LiquidGlassView` 是一个 `FrameLayout` 子类：放进布局、把内容塞进去，就完事了。
> API 24+，API 33+ 走 AGSL 快速路径。
> （全新项目、纯 Compose？用 [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)，那个做得很好。）

#### 该用哪个液态玻璃库？

三个库面向的是不同的视图体系，按 UI 框架选，不要按功能列表选。

| 你要加玻璃的那个界面 | 用哪个 |
|---|---|
| Android **View / XML** 布局，`ViewGroup` 层级 | **本库** |
| **Jetpack Compose**（`@Composable`） | [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) |
| 需要兼容 **API 24–32** | **本库**（C++/NEON 降级管线；Compose 那几个库需要 AGSL，即 API 33+） |
| Compose Multiplatform / iOS + Android 共用 UI | [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) |

如果你的项目是 Compose 优先，直接用 Kyant0 的，别把本库塞进 `AndroidView` 里包一层。

<p align="center">
  <img src="assets/hero-lens-detail.jpg" alt="边缘压缩环与色散彩边（1:1 像素）" width="820">
</p>
### ✨ 特性

**Liquid Glass 2.0（API 33+，单 pass AGSL 透镜管线）**

- **🔍 真实 SDF 折射** - 实时圆角矩形 SDF 驱动的透镜光学：边缘背景压缩环，实时跟随圆角与形状
- **🌈 物理色散** - 三通道沿法线方向不同折射量 → 边缘光谱边纹
- **💡 传感器高光** - 法线光照 + 重力传感器光源，倾斜设备时高光随之移动
- **🌓 亮度自适应** - 持续感知背景明暗，自动调整染色并回调前景内容切换深浅色
- **🧪 Regular / Clear 双材质** - Apple 风格材质变体（重可读性 / 高透+压暗层）
- **🫧 液态融合** - 双玻璃形状 smin 平滑黏连合并（`setSecondaryShape`）
- **👆 按压液态** - 按压增强折射并在手指下方局部凸起
- **🌫️ 渐进模糊** - `ScrollEdgeBlurView`：滚动边缘从清晰渐变到模糊
- **♿ 无障碍降级** - 高对比度设置下退化为不透明材质；尊重「移除动画」与省电模式

**经典管线（API 24+）**

- **🎨 实时背景模糊** - 动态背景模糊，可调节模糊半径和饱和度
- **🌈 色差效果** - RGB 通道分离效果，呈现高级玻璃质感
- **✨ 边缘高光** - 基于触摸位置的动态光线反射
- **⚡ 高性能** - 使用原生 C++ (NEON SIMD) 和智能缓存优化
- **🎯 易于集成** - 简单的 XML 属性和 Kotlin API
- **🔧 高度可定制** - 精细调节玻璃效果的每个方面
- **🧩 现成小部件** - `LiquidGlassButton` / `LiquidGlassFab` / `LiquidGlassTabBar`：预置点击派发与前景自适应配色的玻璃按钮、悬浮按钮和标签条

完整透镜管线文档见 [docs/LIQUID_GLASS_V2.md](docs/LIQUID_GLASS_V2.md)。

> **在用 AI 编程助手？**[llms.txt](llms.txt) 是这套 API 的机器可读精简版，
> [AGENTS.md](AGENTS.md) 里有接入清单和 agent 最常犯的几个错。

### 📱 演示

<div align="center">

#### 🎬 实时拖动与参数调节

<img src="assets/demo-glass-drag.gif" alt="在渐变背景上拖动玻璃" width="252">
<img src="assets/demo-glass-params.gif" alt="实时调节透镜参数" width="252">

#### 🧪 Regular / Clear 双材质

<img src="assets/glass-regular-material.jpg" alt="Regular 材质——可读性优先" width="252">
<img src="assets/glass-clear-material.jpg" alt="Clear 材质——高透" width="252">

</div>

跑 sample app 可以看到完整调参场（4 个场景 + 实时参数抽屉），或者直接启动首屏用的 hero 场景：

```bash
adb shell am start -n com.example.liquidglass/com.example.liquidglass.HeroShowcaseActivity
```

### 📦 安装（JitPack）

在 `settings.gradle.kts` 中添加 JitPack 仓库：

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

添加依赖：

```kotlin
dependencies {
    implementation("com.github.QWEA0:liquidglass:v2.0.2")
}
```

库以 `:liquidglass` 模块发布（AAR，内含 arm64-v8a / armeabi-v7a 预编译 `.so`）；仓库中的 `:app` 为演示工程。

### 🚀 快速开始

#### 完整可运行示例

跑起一颗玻璃药丸所需的全部代码。`LiquidGlassView` 采样的是绘制在它**背后**的内容，
所以背景内容必须在重叠父容器里排在它前面。

```xml
<!-- res/layout/activity_main.xml -->
<FrameLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <!-- 任何你想被折射的东西。细节很重要：纯色和平滑渐变上看不出折射 -->
    <ImageView
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:scaleType="centerCrop"
        android:src="@drawable/your_background" />

    <com.example.liquidglass.LiquidGlassView
        android:id="@+id/glass"
        android:layout_width="280dp"
        android:layout_height="96dp"
        android:layout_gravity="center"
        app:cornerRadius="999dp"
        app:glassMaterial="regular"
        app:refractionHeight="66dp"
        app:bevelWidth="14dp"
        app:dispersionStrength="0.12"
        app:sensorHighlight="true"
        app:adaptiveTint="true">

        <!-- 你的内容直接放在里面，它就是个 FrameLayout -->
        <TextView
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:gravity="center"
            android:text="Liquid Glass"
            android:textColor="#FFFFFF"
            android:textSize="20sp" />

    </com.example.liquidglass.LiquidGlassView>
</FrameLayout>
```

```kotlin
import com.example.liquidglass.GlassMaterial
import com.example.liquidglass.LiquidGlassView

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val glass = findViewById<LiquidGlassView>(R.id.glass)

        // 背景会动、或者玻璃自己会动时【必须】开启。
        // 默认 false —— 漏掉这行是最常见的接入错误：
        // 背景只会被捕获一次，玻璃看起来像是冻住了。
        glass.enableDynamicBackground = true
    }
}
```

接入到此为止，下面全是可选调优。

#### 代码中调参

```kotlin
// --- Liquid Glass 2.0 透镜管线（API 33+，低版本静默降级，不抛异常）---
glass.material = GlassMaterial.REGULAR      // REGULAR 重可读性 | CLEAR 覆盖媒体内容
glass.refractionHeight = 200f               // 主控旋钮：透镜强度。px，0-300
glass.bevelWidth = 40f                      // 玻璃"厚度"带宽度。px，2-200
glass.dispersionStrength = 0.10f            // 边缘色散，0-1。超过 0.25 会像彩虹而不像玻璃
glass.enableSensorHighlight = true          // 高光跟随设备倾斜
glass.enableAdaptiveTint = true             // 染色跟随背景明暗

// 背景明暗翻转时切换前景色
glass.glassAppearanceListener = { isOverLight ->
    label.setTextColor(if (isOverLight) Color.BLACK else Color.WHITE)
}

// 液态融合 —— 双形状 smin 平滑黏连，像水银（API 33+）
glass.setPrimaryShape(dockRect, cornerRadiusPx = 36f)
glass.setSecondaryShape(bubbleRect, cornerRadiusPx = 44f, smoothing = 40f)

// --- 经典管线（API 24+）---
glass.blurAmount = 0.0625f
glass.saturation = 140f
glass.aberrationIntensity = 2f
glass.blurMethod = BlurMethod.SMART         // 合法枚举名见下方表格
```

### 🎛️ 自定义选项

全部属性都在 `LiquidGlassView` 上。标 **33+** 的属于 AGSL 透镜管线；
低于 API 33 时这些属性会被接受并静默忽略，不抛异常。

| 属性 | 类型 | 默认值 | 范围 | 说明 |
|---|---|---|---|---|
| `enableDynamicBackground` | Boolean | `false` | — | 背景或玻璃会动时**必须设为 `true`**，否则背景只捕获一次 |
| `cornerRadius` | Float | `999f` | px | `999f` = 药丸形。SDF 跟随此值，折射随圆角变化 |
| `material` | `GlassMaterial` | `REGULAR` | — | **33+** `REGULAR` 重可读性 · `CLEAR` 覆盖媒体 |
| `refractionHeight` | Float | `200f` | 0–300 px | **33+** 透镜强度主控旋钮 |
| `bevelWidth` | Float | `40f` | 2–200 px | **33+** 边缘"厚度"带宽度 |
| `dispersionStrength` | Float | `0.10f` | 0–1 | **33+** 边缘色散。超过 0.25 会像彩虹 |
| `enableSensorHighlight` | Boolean | `false` | — | **33+** 高光跟随重力传感器 |
| `enableAdaptiveTint` | Boolean | `false` | — | **33+** 染色跟随背景亮度 |
| `useShaderPipeline` | Boolean | `true` | — | 设 `false` 可在 33+ 上强制走经典管线 |
| `useHardwareBlurWhenPossible` | Boolean | `true` | — | 设 `false` 会连透镜管线一起关掉，不只是硬件模糊 |
| `debugApiLevelCap` | Int | `MAX_VALUE` | API 级别 | 仅调试：钳制管线分层，预览低版本效果（36 AGSL 增强 / 33 透镜 / 31 经典 GPU / 以下 CPU） |
| `effectiveApiLevel` | Int | — | 只读 | `min(设备 SDK, debugApiLevelCap)`，即实际生效的分层级别 |
| `blurAmount` | Float | `0.0625f` | 0–1 | 模糊半径（占视图尺寸比例） |
| `saturation` | Float | `140f` | 百分比 | 100 = 不变 |
| `aberrationIntensity` | Float | `2f` | — | 经典 RGB 分离强度 |
| `displacementScale` | Float | `70f` | — | 经典边缘畸变 |
| `elasticity` | Float | `0.15f` | — | 触摸弹性响应 |
| `blurMethod` | `BlurMethod` | `SMART` | — | 合法值见下表 |
| `enableBackdropBlur` | Boolean | `true` | — | |
| `enableChromaticAberration` | Boolean | `true` | — | 经典管线 |
| `enableChromaticDispersion` | Boolean | `false` | — | 物理色散（经典管线） |
| `enableEdgeHighlight` | Boolean | `true` | — | |
| `enableShadow` | Boolean | `false` | — | |
| `edgeHighlightBorderWidth` | Float | `1.5f` | px | |
| `edgeHighlightOpacity` | Float | `100f` | 0–100 | |
| `accessibilityMode` | `GlassAccessibilityMode` | `AUTO` | — | `AUTO` 尊重系统高对比度 / 移除动画 / 省电模式 |
| `downsampleScale` | Int | `2` | — | 越大越快越软 |
| `highQualityBlur` | Boolean | `false` | — | |
| `collectFrameStats` | Boolean | `true` | — | 性能分析用，生产环境关掉 |
| `glassAppearanceListener` | `((Boolean) -> Unit)?` | `null` | — | 背景明暗翻转时回调 |
| `backdropSource` | `View?` | `null` | — | 背景来源视图。`null` = 直接父容器；指定后可捕获树中任意位置的视图，且保留 GPU 管线 |

#### XML 属性

声明在 `LiquidGlassView` styleable 下，命名空间 `app`：

`displacementScale` · `blurAmount` · `saturation` · `aberrationIntensity` · `elasticity` ·
`cornerRadius`（dimension） · `glassMaterial`（`regular` | `clear`） · `bevelWidth`（dimension） ·
`refractionHeight`（dimension） · `dispersionStrength` · `sensorHighlight` · `adaptiveTint` ·
`backdropSourceId`（reference —— 背景来源视图的 id，不填 = 直接父容器）

#### 枚举值

精确拼写 —— 编不过通常就是枚举名写错了。

| 枚举 | 合法值 |
|---|---|
| `BlurMethod` | `BOX_BLUR` · `BOX_BLUR_CPP` · `IIR_GAUSSIAN` · `IIR_GAUSSIAN_NEON` · `SMART` · `DOWNSAMPLE` |
| `GlassMaterial` | `REGULAR` · `CLEAR` |
| `GlassAccessibilityMode` | `AUTO` · `FORCE_FULL` · `FORCE_OPAQUE` |
| `ScrollEdgeBlurView.Edge` | `TOP` · `BOTTOM` |

#### 方法

| 签名 | 说明 |
|---|---|
| `setPrimaryShape(rect: RectF?, cornerRadiusPx: Float = cornerRadius)` | 传 `null` = 使用整个视图 |
| `setSecondaryShape(rect: RectF?, cornerRadiusPx: Float = 999f, smoothing: Float = 48f)` | **33+** 双形状 smin 黏连合并 |
| `setBackdropSource(source: View?)` | 捕获指定视图而非直接父容器，摆脱层级约束，**保留 GPU 管线** |
| `setCustomBackdropCapture(capture: (RectF) -> Bitmap?)` | 自己提供背景位图。会强制回退 CPU 管线，优先用 `setBackdropSource` |
| `refreshAccessibilityState()` | 重新读取系统无障碍设置 |

`ScrollEdgeBlurView` —— 滚动边缘渐进模糊：`edge`、`maxBlurRadius`、`bindScrollView(view)`。

#### 现成小部件

三个都是 `LiquidGlassView` 的子类——上面所有玻璃属性照常可用，`setOnClickListener`
开箱即响。开启 `enableAdaptiveTint = true` 后前景（文字/图标/指示）会跟随背景明暗
自动切换深浅色，显式设置过颜色则不再自动切换。

| 小部件 | 说明 |
|---|---|
| `LiquidGlassButton` | 胶囊玻璃按钮，居中文字。XML：`android:text`、`android:textSize`、`android:textColor`。Kotlin：`text`、`setTextColor(color)`、`textView` |
| `LiquidGlassFab` | 圆形 56dp 悬浮按钮，居中图标。XML：`android:src`、`app:glassIconTint`。Kotlin：`icon`、`setIconResource(id)`、`setIconTint(color)`、`imageView` |
| `LiquidGlassTabBar` | iOS 26 风格标签条：图标+小字标签，选中指示是一颗真实玻璃滴（折射下方内容），切换带液态拉伸动画、可手指拖拽吸附。Kotlin：`setTabs(List<TabItem>)` / `setTabs(titles)`、`selectedIndex`、`onTabSelected`、`selectedTintColor`。XML：`app:glassTabEntries`（纯文字） |

完整透镜管线文档见 [docs/LIQUID_GLASS_V2.md](docs/LIQUID_GLASS_V2.md)。

### ❓ 常见问题

**怎么在已有的 Android XML 布局里加 iOS 26 液态玻璃？**
加上 JitPack 仓库和 `:liquidglass` 依赖，在布局里把
`com.example.liquidglass.LiquidGlassView` 放在某个背景内容之上，然后设
`enableDynamicBackground = true`。完整代码见[快速开始](#-快速开始)。

**怎么在 `ScrollView` / `RecyclerView` 上面加玻璃？**
玻璃捕获的是**直接父容器**，所以滚动内容必须在这个父容器里：把 `ScrollView` 和玻璃放进
同一个 `FrameLayout`、玻璃写在后面，再设 `enableDynamicBackground = true` 让它跟上滚动。
如果两者没法放在同一个父容器里，就把 `backdropSource` 指向那个滚动视图——玻璃可以待在树的
任意位置，覆盖区域按屏幕坐标实时算，滚动时自动重绘。另外，玻璃如果是**跟着内容一起滚动**的，
它看到的背景本来就不变，这是物理正确的结果，不是 bug。

**能在 `Dialog` / `BottomSheetDialog` / `PopupWindow` 里用玻璃吗？**
可以，demo app 的**弹层**场景就是一个跑通的 `BottomSheetDialog`。和 Activity 内相比有三点不同：

1. **背景必须走 `backdropSource`。** 对话框有独立 window，玻璃在里面的直接父容器是透明的，
   默认捕获拍到的是空的（画面全黑）。要显式指到 Activity 上——
   `glass.backdropSource = activity.findViewById(android.R.id.content)`。覆盖区域按屏幕坐标算，
   所以跨 window 成立，且保留 GPU 管线。注意别写成
   `glass.apply { backdropSource = findViewById(…) }`：`apply` 里的 `this` 是玻璃自己，
   解析到的是 `View.findViewById`，静默返回 `null`，于是退回那个透明的父容器。
2. **别用 window 动画。** window 动画（`BottomSheetDialog` 的默认行为，以及任何
   `windowAnimationStyle`）是 SurfaceFlinger 层的变换，window 内的 view 树全程不重绘，
   `getLocationOnScreen` 不变，折射就冻在动画前的最后一帧被 surface 拖着走。
   关掉它（`window.setWindowAnimations(0)`），改成动 window 内部的 view，并逐帧
   `glass.invalidate()` 强制重录 display list。`BottomSheetBehavior` 拖拽同理——它用
   `offsetTopAndBottom` 挪容器，不会重录子视图，需要在 `onSlide` 里重绘。
3. **清掉 window 的各层底色**，否则玻璃是坐在对话框的不透明背景上。`BottomSheetDialog`
   要清 window、`container`、`coordinator` 和 `design_bottom_sheet` 四层；edge-to-edge
   必须由**主题**给（`enableEdgeToEdge`），因为对话框在构造时就读了它。另外把 `dimAmount`
   设为 0——变暗层画在 Activity 和对话框之间，玻璃采到的是没变暗的内容，折射出来会比周围亮一截。

**能在 Jetpack Compose 里用吗？**
用 `AndroidView` 包一层技术上可行，但不建议。请改用
[Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)，
那个是为 Compose 而生的、也更成熟。本库存在的意义就是补 View 体系这一块。

**加上了但看不到任何效果，为什么？**
三个常见原因，按概率排序：
1. `enableDynamicBackground` 还是 `false`，背景只被捕获了一次。
2. 玻璃背后什么都没画。它采样的是父容器的背景，需要在重叠父容器里有个排在它前面的兄弟视图。
3. 背景是纯色或平滑渐变。**没有高频细节就看不出折射和色散**，
   请用图标网格、文字或照片测试。

**Android 13（API 33）以下能用吗？**
能，最低支持到 API 24。低于 33 时没有 AGSL 透镜管线，会降级到 C++/NEON 的
模糊 + 色差管线。2.0 的属性会被接受并忽略而不是抛异常，所以不需要写 `Build.VERSION` 判断。

**可以商用吗？**
可以。MIT 协议，保留版权声明即可，没有其他要求。

**性能开销多大？**
2024 年前后的设备上，AGSL 管线在 60 fps 下每帧透镜耗时远低于 1 ms
（demo app 的悬浮窗会实时显示 `FrameStats`）。经典管线更重一些，
需要余量时调 `downsampleScale` 和 `blurMethod`。

**为什么依赖坐标是 `com.github.QWEA0:liquidglass`？**
`:liquidglass` 模块以 `com.github.QWEA0` 为 group 发布，JitPack 对外提供的就是这个坐标。
同一个 AAR 还有一份别名 `com.github.QWEA0:Liquid-Glass-Android:v2.0.2`，两者等价。
多模块写法 `com.github.QWEA0.Liquid-Glass-Android:liquidglass` **不存在**。

### 🏗️ 架构

**核心组件：**
- `LiquidGlassView` - 主视图组件，支持触摸交互
- `GlassLensRenderer` - 单 pass AGSL 透镜管线：SDF 折射、色散、法线高光、内阴影、smin 融合（API 33+）
- `GlassRuntimeEffects` - AGSL 颜色滤镜/混合模式：vibrancy 饱和度、单 pass 边缘高光、逐像素自适应染色（API 36+，自动回退）
- `LightSourceController` - 重力传感器世界光源（镜面高光方向）
- `BackdropLuminanceMeter` - 背景亮度采样（自适应染色数据源）
- `ScrollEdgeBlurView` - 渐进模糊覆盖层（Scroll Edge Effect）
- `LiquidGlassButton` / `LiquidGlassFab` / `LiquidGlassTabBar` - 基于 `LiquidGlassView` 的现成小部件
- `GlassAccessibility` - 降低透明度/减弱动效/省电模式检测
- `EnhancedBlurEffect` - 多算法模糊引擎（Box、IIR 高斯、降采样）
- `ChromaticAberrationEffect` - RGB 通道分离处理器
- `EdgeHighlightEffect` - 动态光线反射渲染器（经典管线）
- `AsyncRenderer` - 后台线程渲染，保证流畅性能

**原生优化：**
- `gauss_iir_neon.cpp` - ARM NEON SIMD 加速的 IIR 高斯模糊
- `chromatic_aberration.cpp` - 硬件加速的 RGB 分离
- `boxblur.cpp` - 快速盒式模糊实现

### 📊 性能

- **GPU 透镜管线（API 33+）**：单 pass AGSL + RenderEffect——零 Bitmap 分配、零像素回读；真机实测每帧 CPU 录制开销约 0.06ms，稳定 60fps
- **优化渲染**：智能三层缓存策略（背景 → 模糊 → 最终结果）
- **原生加速**：ARM NEON SIMD 提供 4-8 倍性能提升（经典管线）
- **自适应质量**：自动降采样，在中端设备上保持流畅 60fps
- **内存高效**：位图池和自动资源回收

### 🔧 要求

- **最低 SDK**: 24 (Android 7.0) —— 经典管线
- **Liquid Glass 2.0 透镜管线**: API 33+ (Android 13)，以下版本自动回退
- **AGSL 颜色滤镜/混合模式增强**: API 36+ (Android 16)，自动启用，无 API 变化
- **目标 SDK**: 35 (Android 15)，编译 SDK 36
- **语言**: Kotlin
- **NDK**: 需要用于原生模糊加速

### 📦 依赖

```gradle
dependencies {
    implementation "androidx.core:core-ktx:1.12.0"
    implementation "androidx.appcompat:appcompat:1.6.1"
    implementation "com.google.android.material:material:1.11.0"
}
```

### 🛠️ 构建

```bash
# 克隆仓库
git clone https://github.com/yourusername/liquidglass-android.git

# 在 Android Studio 中打开
# 构建并运行演示应用
./gradlew assembleDebug
```

### 📄 许可证

本项目基于 [MIT 许可证](LICENSE) 发布——可商用，除保留版权声明外无其他要求。

### 🙏 致谢

灵感来源于玻璃态设计趋势和 liquid-glass-react 库。

---

<div align="center">

**Made with ❤️ for Android developers**

**为 Android 开发者用心打造**

</div>
