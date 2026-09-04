# Liquid Glass 2.0 —— 统一透镜管线

对标 Apple iOS 26 Liquid Glass 的完整重构。核心变化：**用单个 AGSL 着色器（API 33+）
里的实时 SDF 光学模型，取代"预生成位移贴图 + 多个独立 CPU 效果"的旧架构**。

## 渲染路径优先级

| 条件 | 路径 | 覆盖能力 |
|---|---|---|
| API 33+ 且 `useShaderPipeline` | **透镜管线（2.0）** | 模糊、饱和度（vibrancy 曲线）、SDF 折射、色散、法线高光（传感器光源）、内阴影、自适应染色（逐像素）、Clear 压暗、按压液态、smin 融合 |
| API 31–32，或 `useShaderPipeline=false` | 旧 GPU 管线 | 模糊 + 饱和度（33+ 时另有旧位移贴图色差，供 A/B 对比） |
| API 24–30，或强制 CPU / 自定义背景捕获 | CPU 管线 | 模糊、色差、色散、边缘高光（原有全部能力） |
| 系统开启「高对比度文字」或 `FORCE_OPAQUE` | 不透明降级 | 实底圆角矩形 + 细边框（对应 iOS Reduce Transparency） |

AGSL 编译失败（个别设备驱动）时自动永久回退旧管线，不会崩溃。

## 透镜着色器的光学分段（GlassLensRenderer）

```
backdrop 录制（带 margin 外扩）
  → RenderEffect 高斯模糊（材质缩放后的半径）
  → AGSL 单 pass：
      1. sceneSDF：圆角矩形 SDF（实时跟随 cornerRadius/尺寸；可选第二形状 smin 融合）
      2. 覆盖率：SDF ±0.75px 抗锯齿，形状外 alpha=0（不再需要 clipPath）
      3. 法线：SDF 数值梯度 → 屏幕空间外法线
      4. 厚度剖面：bevelWidth 宽的斜面带，内部平坦
      5. 折射：沿法线向内采样 refractionHeight × slope² → 边缘呈现内侧背景的
         压缩镜像（默认，与 iOS 一致）；refractionOutward = true 时向外采样
         （可选的凸透镜模式：形状外的背景被弯进边缘，还没进到玻璃下面的内容
         先出现在边缘，进来之后沿边缘延展）
      6. 色散：R/G/B 三通道折射量 ×(1∓dispersion·slope) → 边缘光谱边纹
      7. 触摸凸起：手指下方高斯泡状局部放大（press uniform 联动）
      8. 饱和度（提饱和端 vibrancy 曲线：低饱和多提/高饱和少提/高光保护）
         → 自适应染色（enableAdaptiveTint 时按局部亮度逐像素过渡）/Clear 压暗
      9. 镜面高光：dot(N, -L) 角度瓣（pow 2.5 铺开 + pow 8 收紧核）+ 1px 贴边
         亮线，两者都随角度衰减到 0，无方向无关的常亮项
     10. 内阴影：背光侧边缘内部渐暗（厚度感）+ 贴边压暗（与迎光侧亮线对称）
```

关键点：

- **向外折射（可选模式）要绕过子输入的裁剪限制**（真机踩坑）：`RenderEffect.createRuntimeShaderEffect`
  没有暴露 Skia 的 `childSampleRadius`，子输入只保证"输出裁剪区"内可采样；
  带效果的节点直接画到视图画布上时输出区被裁到视图矩形，margin 里录下的内容
  对着色器不可见，向外采样读到透明黑 → 轮廓黑边。解法：把带效果的节点画进一个
  自带合成层（`setUseCompositingLayer`）、范围等于整个录制区的外层节点，层内裁剪区
  就是录制区，着色器采得到 margin，外层节点再画到视图画布上才被裁到视图矩形。
  录制 `margin` 随之外扩到折射距离；采样坐标另按"录制区 ∩ 背景来源矩形"钳制
  （来源之外没有内容），该矩形随滚动变化时量化到 4px 再重建 effect。
  默认的向内采样（`refractionOutward = false`）不需要这一层，仍是单节点路径。
- **高光和折射共享同一法线场**：形状怎么变（圆角、融合、副形状移动），
  高光和折射自动跟着变——这是旧"描边渐变"方案做不到的。
- **uniform 全部量化**（光源 0.005、模糊 0.5px、按压 0.01），静止时不重建
  RenderEffect，每帧只有一次父视图录制。

## 新增公开 API（LiquidGlassView）

| 属性 / 方法 | 默认 | 说明 |
|---|---|---|
| `useShaderPipeline` | true | 透镜管线总开关（false = 旧 GPU 管线，A/B 对比用） |
| `material` | REGULAR | `GlassMaterial.REGULAR`（自适应重可读性）/ `CLEAR`（高透 + 压暗层） |
| `bevelWidth` | 64px | 边缘斜面带宽度（玻璃"厚度"，2-200） |
| `refractionHeight` | 32px | 边缘最大折射位移（0-300；refractionNoFold 开着时钳在斜面宽度的一半以内） |
| `refractionNoFold` | true | 折射单调不翻折：贴边放大率最高、往内降到 1，边缘只放大延展（与 iOS 一致）；false 允许折返成压缩镜像环 |
| `refractionOutward` | false | 可选的凸透镜模式：true 向外采样（形状外的背景弯进边缘）；默认向内压缩镜像，与 iOS 一致 |
| `adaptiveLensScale` | true | 斜面 / 折射 / 高光带 / 内阴影带按形状短边钳，小控件不再整块都是边缘带 |
| `dispersionStrength` | 0.10 | 色散强度（与色差/色散开关及其滑杆联动） |
| `enableSensorHighlight` | false | 高光跟随重力传感器（光源固定在世界坐标）；关闭时用固定的左上光源 |
| `enableAdaptiveTint` | false | 背景亮度自适应染色（透镜管线逐像素；亮度计仍供 `glassAppearanceListener` 使用） |
| `glassAppearanceListener` | null | `(isOverLight) -> Unit`，背景明暗翻转回调（联动前景文字色） |
| `isOverLightBackground` | — | 当前明暗判定（只读） |
| `accessibilityMode` | AUTO | `AUTO` / `FORCE_FULL` / `FORCE_OPAQUE` |
| `refreshAccessibilityState()` | — | 重新查询系统无障碍/省电状态 |
| `setPrimaryShape(rect, corner)` | 整个视图 | 自定义主玻璃几何（融合场景） |
| `setSecondaryShape(rect, corner, smoothing)` | null | 第二玻璃体；smin 平滑融合宽度 `smoothing` |

全部 XML 属性见 `res/values/attrs.xml`（`app:glassMaterial`、`app:bevelWidth`、
`app:refractionHeight`、`app:dispersionStrength`、`app:sensorHighlight`、
`app:adaptiveTint` + 经典六项——本次同时补上了 README 一直承诺但从未接线的
`obtainStyledAttributes` 解析）。

## 配套组件

- **`ScrollEdgeBlurView`** —— 渐进模糊（Scroll Edge Effect）。双层模糊
  （弱模糊宽带 + 强模糊窄带）经线性渐变 DST_IN 遮罩叠加，近似"半径从 0
  渐变到最大"。`bindScrollView()` 绑定滚动自动重绘；API < 31 回退渐变 scrim。
- **`LightSourceController`** —— 重力传感器 → 屏幕空间光源方向。单例引用计数、
  低通滤波、按屏幕旋转重映射、平放时渐变回默认光源；变化超阈值才触发重绘。
- **`BackdropLuminanceMeter`** —— 背景亮度采样。GPU 管线每 350ms（省电 1000ms）
  把父视图缩绘到 24×24 估算亮度；CPU 管线直接复用已捕获的背景位图（零额外开销）。
  EMA 平滑 + 0.60/0.45 滞回判定明暗。
- **`GlassAccessibility`** —— 「高对比度文字」→ 不透明降级；「移除动画」→
  关闭按压/传感器动效；省电模式 → 降采样频率、关传感器。

## 按压液态

按下时：`press` uniform 0→1（180ms），折射增强 60%、高光收敛 35%、手指下方
出现高斯泡状局部放大；松开 320ms 回弹。系统「移除动画」开启时直接跳变。
（原有的整体弹性缩放保留，两者叠加。）

## 与 Apple 实现的差距对照（更新后）

| 能力 | 状态 |
|---|---|
| 实时 SDF 折射 / 边缘压缩带 | ✅ 透镜管线 |
| 色散（沿法线的光谱边纹） | ✅ 透镜管线（同时修复了 CPU/旧 GPU 实现的 45° 对角偏移 bug） |
| 法线驱动镜面高光 + 传感器光源 | ✅ |
| 内阴影 / 厚度感 | ✅ |
| Regular / Clear 双材质 | ✅ |
| 背景亮度自适应 + 前景外观联动 | ✅（采样式，非逐帧） |
| 液态融合（smin） | ✅ 单视图内双形状；跨视图融合未做 |
| 渐进模糊（scroll edge） | ✅ 双层近似，非真实半径渐变 |
| 无障碍降级 | ✅ 高对比度/减弱动效/省电 |
| 按压液态 | ✅ 折射/高光/局部凸起；无整体形变网格 |
| 形态过渡（tab bar → 搜索框等容器变形动画） | ❌ 需要宿主动画系统配合，未在组件层实现 |
| 连续曲率圆角（superellipse） | ❌ 目前为圆弧圆角 SDF |

## 本次一并修复的历史缺陷

1. **色差 45° 对角偏移**：三处实现（旧 AGSL / Kotlin / C++）都把标量偏移同时加到
   x、y 上，等于固定沿对角线偏移。现改为沿位移方向偏移。
2. **动态背景哈希漏检**：`enableDynamicBackground` 时不再依赖 8×8 抽样哈希，
   直接视为每帧变化，消除动画背景上的偶发模糊滞留。
3. **NDK 从未接入构建**：`app/build.gradle.kts` 缺少 `externalNativeBuild`，
   `libnativegauss.so` 从未进过 APK，所有 C++/NEON 路径一直在静默回退 Kotlin。
   现已接入（arm64-v8a / armeabi-v7a），并确认 so 已打包。
4. **XML 属性从未解析**：README 演示的 `app:blurAmount` 等属性此前没有
   `declare-styleable` 也没有解析代码，现已补全。
5. **死代码**：删除无任何引用的 `EdgeDistortionEffect.kt`（318 行）。

## Demo

- 场景条新增 **「融合」**：拖动圆形玻璃靠近胶囊 dock，边缘 smin 黏连合并。
- 「滚动」场景顶部加入渐进模糊条。
- 调试面板「渲染路径」改为三档：**透镜 2.0 / 经典 GPU / 强制 CPU**；
  透镜档展开材质、传感器高光、亮度自适应、斜面/折射/色散滑杆与无障碍降级开关。
- 主玻璃按钮文字颜色随 `glassAppearanceListener` 自动明暗翻转。

## API 36 运行时效果（Android 16）

Android 16 把 AGSL 扩展到了 `RuntimeColorFilter`（自定义颜色滤镜）和
`RuntimeXfermode`（自定义混合模式）。库在 API 36+ 自动启用三个增强
（`GlassRuntimeEffects`，无新增公开 API，低版本静默走原路径）：

| 效果 | 落点 | 低版本回退 |
|---|---|---|
| vibrancy 非线性饱和度（低饱和多提、高饱和少提、高光保护） | 旧 GPU 管线的 RenderEffect 链、CPU 管线最终绘制 | 线性 `ColorMatrixColorFilter` |
| 边缘高光单 pass 融合（screen+overlay 合一，并按边框底下像素亮度逐像素调整，亮背景处转为轻微压暗描边） | `EdgeHighlightEffect`（旧 GPU / CPU 管线的描边） | SCREEN + OVERLAY 双 pass |
| 逐像素自适应染色 | CPU 管线的染色覆盖层 | 全局染色色值 |

透镜管线（API 33+）不依赖这两个类：vibrancy 曲线和逐像素自适应染色直接写进
LENS_AGSL，也就是说 **API 33 起透镜路径就有逐像素染色**，API 36 只是把同等
质量补齐到旧 GPU / CPU 路径。三段 AGSL 编译失败时置 broken 标志永久回退，
与透镜管线的 `shaderBroken` 同一约定。

预乘注意：RuntimeXfermode/RuntimeColorFilter 的输入输出都是预乘 alpha，
AGSL 内先除 alpha 转直通色、返回前乘回（见 GlassRuntimeEffects 注释）。
