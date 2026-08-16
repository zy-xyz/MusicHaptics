# 002 — iOS-Level Motion Polish for Compose UI (Dashboard + Console)

- **Status**: TODO
- **Commit**: (to be filled at write time)
- **Severity**: HIGH
- **Category**: Easing & duration / Physicality & origin / Interruptibility / Cohesion / Accessibility
- **Estimated scope**: 4 files (HapticDashboardActivity.kt, IOSConsole.kt, PhysicsSpring.kt, IOSComposerPanel.kt + new ReducedMotion.kt)

## Problem

The Compose UI has good iOS-inspired foundations but deviates from Apple's fluid interface principles in several high-leverage ways:

1. **Wrong easing on high-frequency interactions** — `PhysicsSpring.kt` uses custom springs with `dampingRatio=0.32` (bouncy) for **Toggle, SegmentedControl, Slider thumb** — these are hit *tens of times per day*. Per AUDIT.md: tens/day = "Remove or drastically reduce" animation. Bouncy springs on toggles/sliders make them feel sluggish and disconnected.

2. **`scale(0.92)` press compression is too deep** — `PhysicsSpring.kt:104-110` compresses to 0.92 (8% scale). Apple uses 0.97 (3%). 0.92 feels like a "squishy" button, not a responsive tap.

3. **Segmented control lens animation is over-engineered** — `HapticDashboardActivity.kt:203-221` uses `dampingRatio=0.45`, `stiffness=400` for lens scale + offset + glass alpha — three simultaneous springs on a control hit constantly. This drops frames on mid-range devices and feels "floaty" not "direct."

4. **Console expand/collapse uses `FastOutSlowInEasing` (ease-in-out)** — `IOSConsole.kt:103-104` uses `tween(250, easing = FastOutSlowInEasing)` for enter/exit. Per AUDIT.md: entering/exiting should be **`ease-out`** (starts fast). `FastOutSlowInEasing` = `cubic-bezier(0.4, 0, 0.2, 1)` — has ease-in component, delays initial movement.

5. **No `prefers-reduced-motion` handling** — Neither `HapticDashboardActivity.kt` nor `IOSConsole.kt` respects reduced motion. All springs/tweens run regardless.

6. **Hover states ungated** — `IOSSettingSliderRow.kt:382-391` has `detectTapGestures` but no hover handling. On desktop/ChromeOS, touch devices fire hover on tap — need `@media (hover: hover) and (pointer: fine)` gating (in Compose: `LocalConfiguration.current` check).

7. **Tab bar drag uses `dampingRatio=0.6`, `stiffness=500`** — `HapticDashboardActivity.kt:865-868` for lens offset spring. On drag release, should use **velocity handoff** (Apple principle #5) — project momentum, don't just spring to target.

8. **Toast/snackbar-style primitive badge has no enter animation** — `IOSComposerPanel.kt:1400-1412` shows primitive badge with `animateFloatAsState(targetValue=1f, tween(150))` on alpha. Should use `@starting-style` equivalent: enter from `scale(0.95) + opacity(0)` with `ease-out`.

9. **Waveform display idle animation runs infinitely** — `IOSWaveformDisplay.kt:1180-1187` runs two infinite transitions (6s + 9s) even when app is backgrounded. Wasteful. Should only run when `isActive && amp < threshold`.

10. **Inconsistent spring tokens** — `PhysicsSpring.kt` defines 7 spring presets; `HapticDashboardActivity.kt` uses inline `spring(dampingRatio=..., stiffness=...)` in 8+ places. Should consolidate to tokens.

## Target

### PhysicsSpring.kt — Replace bouncy presets with critically damped for high-frequency UI
```kotlin
// REPLACE bouncy() — used on Toggle, SegmentedControl, Slider (HIGH FREQUENCY)
fun bouncy(): SpringSpec<Float> = spring(
    dampingRatio = 1.0f,      // CRITICALLY DAMPED — no overshoot
    stiffness = Spring.StiffnessMedium  // ~300ms settle
)

// REPLACE bouncyDp() — same
fun bouncyDp(): SpringSpec<Dp> = spring(
    dampingRatio = 1.0f,
    stiffness = Spring.StiffnessMedium
)

// REPLACE softBounce() — used on IOSButton press
fun softBounce(): SpringSpec<Float> = spring(
    dampingRatio = 1.0f,      // Critically damped
    stiffness = Spring.StiffnessHigh    // ~150ms — fast press feedback
)

// KEEP elasticSelect() for SegmentedControl lens (momentum-driven, OK to have slight bounce)
fun elasticSelect(): SpringSpec<Float> = spring(
    dampingRatio = 0.85f,     // Near-critical, tiny overshoot only on flick
    stiffness = 350f
)

// KEEP elegantExpand() for console expand (occasional)
fun elegantExpand(): SpringSpec<Float> = spring(
    dampingRatio = 0.9f,
    stiffness = 200f
)

// ADD standard UI tokens
fun uiStandard(): SpringSpec<Float> = spring(dampingRatio = 1.0f, stiffness = Spring.StiffnessMedium)   // 300ms
fun uiFast(): SpringSpec<Float> = spring(dampingRatio = 1.0f, stiffness = Spring.StiffnessHigh)       // 150ms
fun uiSlow(): SpringSpec<Float> = spring(dampingRatio = 1.0f, stiffness = Spring.StiffnessLow)        // 500ms

// BouncyPressController — reduce press compression to 0.97 (Apple standard)
class BouncyPressController(...) {
    fun pressAndRelease(scale: Animatable<Float, *>) {
        scope.launch {
            scale.animateTo(0.97f, spring(dampingRatio = 1f, stiffness = Spring.StiffnessHigh))
            scale.animateTo(1f, spring(dampingRatio = 0.85f, stiffness = 280f))  // slight bounce only on release
        }
    }
}
```

### HapticDashboardActivity.kt — Fix SegmentedControl, TabBar, IOSButton
```kotlin
// Lines 203-221: SegmentedControl lens — simplify to single spring on offset, remove scale/alpha springs
val lensOffsetPx by animateFloatAsState(
    targetValue = baseOffset + dragOffset,
    animationSpec = PhysicsSpring.elasticSelect(),  // token
    label = "LensOffset"
)
// REMOVE lensScale, glassAlpha springs entirely — they're decorative on high-frequency control

// Lines 426-427: IOSButton press scale — use 0.97, uiFast spring
val scale = remember { Animatable(1f) }
val bouncyPress = rememberBouncyPress()  // now uses 0.97 + critically damped press

// Lines 865-868: TabBar lensOffset — add velocity handoff on drag stop
onDragStopped = {
    val velocity = ... // calculate from dragAmount/time
    val projectedOffset = lensOffsetPxState.value + projectMomentum(velocity)
    val targetTab = nearestTab(projectedOffset)
    onSelected(targetTab)
    // spring to targetTab with velocity handoff
    lensOffsetPxState.animateTo(targetOffset, spring(dampingRatio = 0.8f, stiffness = 400f, initialVelocity = velocity))
    dragOffset = 0f
}
```

### IOSConsole.kt — Fix expand/collapse easing, add reduced-motion
```kotlin
// Lines 101-105: AnimatedVisibility — use ease-out, not FastOutSlowInEasing
AnimatedVisibility(
    visible = isExpanded,
    enter = expandVertically(
        animationSpec = tween(200, easing = { t -> 1f - (1f - t) * (1f - t) })  // ease-out: cubic-bezier(0,0,0.2,1) approx
    ) + fadeIn(tween(150, easing = { t -> 1f - (1f - t) * (1f - t) })),
    exit = shrinkVertically(
        animationSpec = tween(160, easing = { t -> 1f - (1f - t) * (1f - t) })
    ) + fadeOut(tween(120, easing = { t -> 1f - (1f - t) * (1f - t) }))
)

// ADD reduced-motion support
val reducedMotion = LocalConfiguration.current.fontScale > 1.3f // proxy; real impl needs CompositionLocal
// Or better: create CompositionLocal for prefersReducedMotion
```

### IOSComposerPanel.kt — Primitive badge enter animation
```kotlin
// Lines 1400-1412: Replace animateFloatAsState with enter transition
AnimatedVisibility(
    visible = primitiveType.isNotEmpty(),
    enter = fadeIn(tween(150, easing = EaseOut)) + 
            expandVertically(tween(150, easing = EaseOut)) +
            scaleIn(animationSpec = tween(150, easing = EaseOut), initialScale = 0.95f),
    exit = fadeOut(tween(100, easing = EaseOut)) + shrinkVertically(...)
) { ... }
```

### IOSWaveformDisplay.kt — Pause idle animation when inactive
```kotlin
// Lines 1180-1187: Only run idle phases when !isActive
val idlePhase by rememberInfiniteTransition(label = "IdlePhase", 
    initialValue = 0f, 
    targetValue = (2f * PI).toFloat(),
    animationSpec = infiniteRepeatable(
        tween(if (isActive && amp < 0.05f) 6000 else Int.MAX_VALUE, easing = LinearEasing), 
        RepeatMode.Restart
    )
)
// Actually better: conditionally create the infiniteTransition
val idleTransition = if (isActive && amp < 0.05f) rememberInfiniteTransition(...) else null
```

### Add ReducedMotionCompositionLocal
```kotlin
// New file: ReducedMotion.kt
val LocalPrefersReducedMotion = staticCompositionLocalOf { false }

// In HapticDashboardActivity.kt, read from system:
val prefersReducedMotion = remember { 
    // Android: Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    // Or use AccessibilityManager.isTouchExplorationEnabled() as proxy
    false // TODO: implement
}
CompositionLocalProvider(LocalPrefersReducedMotion provides prefersReducedMotion) {
    // ... dashboard content
}

// In each spring/tween: check LocalPrefersReducedMotion.current
animationSpec = if (LocalPrefersReducedMotion.current) 
    tween(100) 
else 
    PhysicsSpring.uiStandard()
```

## Repo conventions to follow

- Spring tokens live in `PhysicsSpring.kt` object — **all springs must use these tokens**.
- `PhysicsSpring.rememberBouncyPress()` used by `IOSButton`, `IOSSettingSliderRow`, `IOSSegmentedControl` — single source of truth for press feel.
- `HazeState`/`hazeEffect` for liquid glass backdrop — keep blur on static bars, **never on moving parts** (already correct in TabBar).
- `AnimatedVisibility` with `expandVertically`/`slideInHorizontally` for enter/exit — standard pattern.
- `LaunchedEffect` for 25ms telemetry polling — keep, but ensure coroutine cancelled on leave.

## Steps

1. **PhysicsSpring.kt** — Replace `bouncy()`, `bouncyDp()`, `softBounce()` with critically damped variants; adjust `BouncyPressController` press target to 0.97; add `uiStandard`, `uiFast`, `uiSlow` tokens.
2. **HapticDashboardActivity.kt** — `IOSSegmentedControl`: remove `lensScale` and `glassAlpha` springs, keep only `lensOffset` with `PhysicsSpring.elasticSelect()`. `LiquidGlassTabBar`: add velocity handoff on drag stop. `IOSButton`: already uses `rememberBouncyPress()` — will inherit fix from step 1.
3. **IOSConsole.kt** — Replace `FastOutSlowInEasing` with ease-out cubic-bezier on `AnimatedVisibility` enter/exit. Add reduced-motion check (CompositionLocal).
4. **IOSComposerPanel.kt** — Replace primitive badge `animateFloatAsState` with `AnimatedVisibility` enter/exit from `scale(0.95)+opacity(0)`.
5. **IOSWaveformDisplay.kt** — Gate idle infinite transitions behind `isActive && amp < threshold`.
6. **Create ReducedMotion.kt** — CompositionLocal for `prefers-reduced-motion`; wire into dashboard root; gate all springs/tweens behind it.

## Boundaries

- Do NOT change `HapticEngine.kt`, `HapticComposer.kt`, `HapticSynthesizer.kt` — haptic DSP is separate.
- Do NOT change `ConsoleLogState.kt`, `TelemetryHub.kt` — data layer untouched.
- Do NOT add new dependencies — use Compose built-ins only.

## Verification

- **Mechanical**: `bash gradlew assembleDebug` — BUILD SUCCESSFUL.
- **Feel check on Xiaomi 17 Pro / Pixel 8**:
  - Toggle switches (Persona, Power Amplify, Crossover): **instant, no bounce, no lag**. Feels like iOS Settings toggles.
  - Segmented control (Preset, Style Preset): **lens follows finger 1:1**, on release snaps to nearest tab with velocity — no floaty overshoot.
  - Sliders (Amplitude, Bass Boost, Gamma, all synth params): **thumb scales to 1.25 on drag start, 1.0 on end** — crisp, no bouncy tail.
  - Buttons (Coil Amplify, Active Crossover, Restart Apps): **press to 0.97, release with tiny bounce** — responsive, not squishy.
  - Console expand/collapse: **opens fast (ease-out), closes faster** — no ease-in delay.
  - Primitive badge (Composer panel): **pops in from scale(0.95)+opacity(0)** — not a fade-in.
  - Tab bar (Console ↔ Apps): **drag with velocity projection** — flick switches tabs, slow drag snaps to nearest.
  - Reduced motion (Settings → Accessibility → Remove animations ON): **all springs become 100ms tweens, no position movement, opacity/color remain**.
  - Waveform idle: **stops animating when music plays**; only subtle drift when truly idle.
- **Done when**: All 4 files + new file edited, compile passes, feels like iOS 17+ system UI — direct, responsive, respectful of accessibility.