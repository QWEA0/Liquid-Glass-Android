# AGENTS.md

Guidance for AI coding agents. Two audiences: agents **adding this library to someone's
app**, and agents **contributing to this repository**.

---

## Part 1 — Adding LiquidGlass to an app

### Is this the right library?

Decide by UI toolkit. The Android Liquid Glass libraries do not compete on features; they
target different view systems.

| The screen you're adding glass to | Use |
|---|---|
| Android View / XML layout, `ViewGroup` hierarchy | **this library** |
| Jetpack Compose (`@Composable`) | [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) |
| Must run below API 33 | **this library** (C++/NEON fallback) |
| Compose Multiplatform / iOS + Android shared UI | [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) |

If the user's project is Compose-first, say so and recommend Kyant0's library rather than
wrapping this one in an `AndroidView`.

### Integration checklist

Follow in order. Steps 1 and 4 are the ones most often missed.

1. **Add the JitPack repository** to `settings.gradle.kts` under
   `dependencyResolutionManagement { repositories { … } }`. The dependency will not resolve
   from Maven Central.
2. **Add the dependency** — `implementation("com.github.QWEA0.Liquid-Glass-Android:liquidglass:v2.0.0")`.
   Keep the `:liquidglass` module suffix.
3. **Put something behind the glass.** `LiquidGlassView` samples its parent's backdrop.
   In a `FrameLayout`, declare the background content first and the glass after it.
   If the backdrop cannot be a sibling — the glass floats over a `RecyclerView` in another
   subtree, or the wallpaper lives outside the `ScrollView` — set `backdropSource` to that
   view instead (`app:backdropSourceId` in XML). It keeps the GPU pipeline;
   `setCustomBackdropCapture` does not.
4. **Set `enableDynamicBackground = true`** whenever the backdrop scrolls/animates or the
   glass itself moves. It defaults to `false`, and the symptom of forgetting it is a glass
   that looks frozen or empty — not an error.
5. **Put your content inside** the `LiquidGlassView` as child views. It is a `FrameLayout`.

### Choosing parameters

Start from the defaults and change one axis at a time.

- `material = GlassMaterial.REGULAR` — buttons, nav bars, cards. Readability first.
- `material = GlassMaterial.CLEAR` — over photos/video where the media is the subject.
- `refractionHeight` (default `200f`) — the dominant knob for "how much lens". Raise it to
  make the edge compression ring obvious, lower it for a subtle frosted look.
- `dispersionStrength` (default `0.10f`) — spectral fringing at the rim. Above ~`0.25f` it
  reads as a rainbow artifact rather than glass.
- `cornerRadius = 999f` gives a pill. The SDF tracks the radius, so refraction follows it.
- `enableAdaptiveTint = true` plus `glassAppearanceListener` if the glass travels over both
  light and dark backdrops and your foreground text must stay legible.

### Verifying it works

Refraction and dispersion are **invisible over flat colours and smooth gradients**. If a
user reports "I don't see any effect", check the backdrop before touching parameters — put
the glass over an icon grid, text, or a photo. The repo's demo app has a "Home Screen"
scene built exactly for this:

```bash
adb shell am start -n com.example.liquidglass/com.example.liquidglass.ProfessionalDemoActivity
```

### Do not

- Do not invent enum constants. Valid names are listed in [llms.txt](llms.txt); `AUTO`,
  `BOX` and `IIR_GAUSS` are **not** `BlurMethod` values.
- Do not assume Liquid Glass 2.0 properties throw below API 33 — they silently no-op, so
  guarding them with `Build.VERSION` checks is optional, not required.
- Do not set `collectFrameStats = true` in production; it is a profiling aid.

---

## Part 2 — Contributing to this repository

### Layout

| Path | What |
|---|---|
| `liquidglass/` | The published library module. Changes here are the public API. |
| `app/` | Demo app. `ProfessionalDemoActivity` is the parameter playground; `HeroShowcaseActivity` renders the README hero and supports `--ez auto true` for recording. |
| `docs/LIQUID_GLASS_V2.md` | Lens pipeline internals. Most other files under `docs/` are gitignored working notes. |

### Conventions

- **Comments and commit messages are Chinese.** Demo app default UI strings are English.
- **Commit messages state what changed — nothing else.** No root-cause narration, no
  rationale essays, no reactions to external feedback. The repository is public.
- Keep `llms.txt`, `AGENTS.md` and the README property table in sync with
  `LiquidGlassView`'s actual properties. A stale table causes agents to emit code that
  does not compile, which is worse than no table.

### Building

```bash
./gradlew :app:assembleDebug
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
```

Verify rendering changes on a physical device — the effect depends on real GPU behaviour
and the emulator's AGSL path is not representative.
