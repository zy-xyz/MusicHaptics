# 003 — Multimodal Haptic-Visual Sync (Apple Principle #13)

- **Status**: TODO
- **Commit**: (to be filled at write time)
- **Severity**: MEDIUM
- **Category**: Cohesion / Physicality & purpose / Accessibility
- **Estimated scope**: 2 files (HapticEngine.kt, HapticDashboardActivity.kt + PhysicsSpring.kt tokens)

## Problem

Apple's multimodal feedback principle (Designing Audio-Haptic Experiences): **Causality, Harmony, Utility**.

1. **Causality broken** — UI interactions (toggle, button, slider) trigger haptics via `HapticFeedbackEngine` but the haptic style doesn't match the visual spring. E.g., Toggle uses `bouncyDp()` spring (now critically damped per Plan 002) but haptic is `KICK` (sharp impact). A smooth visual transition should pair with a smooth haptic (continuous), not a sharp tap.

2. **Harmony broken (timing)** — Visual spring duration (~300ms critically damped) vs haptic duration (KICK = ~20ms, IMPACT = ~40ms). They fire on same frame but **decay at different rates** — visual still settling while haptic long gone. Feels disconnected.

3. **Utility missing** — Some interactions over-hapticate. Segmented control on *every drag change* fires `SELECTION` haptic (line 268, 283). Dragging across 3 tabs = 3 haptics in 200ms. Should only haptic on **commit** (release), not during drag.

4. **No reduced-motion haptic gating** — When `prefers-reduced-motion` is ON, visual springs become fast tweens but haptics still fire at full intensity. Should reduce haptic amplitude or skip decorative haptics.

5. **Slider drag haptic too frequent** — `IOSSettingSliderRow.kt:376-379` fires `LIGHT_TICK` every 5% value change. On fast drag = machine gun ticks. Should use **velocity-based haptic** (like iOS: tick only at notches or on release).

## Target

### HapticEngine.kt / HapticFeedbackEngine — New haptic styles matched to visual springs
```kotlin
// Add to HapticFeedbackEngine.HapticStyle enum:
enum class HapticStyle {
    // Existing...
    KICK,           // Sharp transient — for decisive commits (button press, toggle commit)
    IMPACT,         // Medium transient — for presses with visual scale
    SELECTION,      // Light tick — for discrete selection (segmented control commit)
    LIGHT_TICK,     // Very light — for slider notches
    CONTINUOUS_HUM, // Low-freq continuous — for slider drag (matches visual continuous drag)
    SOFT_TAP,       // Ultra-light — for hover/enter (reduced motion: NONE)
    NONE            // No haptic — for reduced motion decorative interactions
}

// In HapticFeedbackEngine.perform():
// Map visual spring type → haptic style
// Critically damped spring (300ms) → CONTINUOUS_HUM (low amplitude, matches visual settle)
// Fast spring (150ms) → IMPACT
// Bouncy release spring → KICK
// No spring (reduced motion) → NONE or SOFT_TAP
```

### HapticDashboardActivity.kt — Sync haptic to visual commit points
```kotlin
// IOSSegmentedControl (lines 243-271): REMOVE haptic on drag, ADD on commit only
pointerInput(item) {
    detectDragGestures(
        onDragStart = { pressedItem = item },
        onDragEnd = {
            // ... existing selection logic ...
            if (targetItem != selected) {
                hapticEngine.perform(HapticFeedbackEngine.HapticStyle.SELECTION)  // ONLY here
                onSelect(targetItem)
            }
            pressedItem = null
            dragOffset = 0f
        },
        onDragCancel = { ... },
        // REMOVE onDrag haptic (line 268)
    ) { change, dragAmount ->
        change.consume()
        dragOffset += dragAmount.x
        // NO HAPTIC during drag — visual lens follows 1:1, haptic only on commit
    }
}

// IOSButton (lines 447-450): Sync haptic to press phases
val bouncyPress = rememberBouncyPress()  // now critically damped press + slight bounce release
.clickable(...) {
    // Press phase: start visual spring + haptic
    bouncyPress.press(scale)  // compress to 0.97
    hapticEngine.perform(HapticFeedbackEngine.HapticStyle.IMPACT)  // medium, matches press
    tryAwaitRelease()
    // Release phase: visual spring to 1.0 with slight bounce
    bouncyPress.release(scale)
    hapticEngine.perform(HapticFeedbackEngine.HapticStyle.KICK)  // sharp, matches release bounce
    onClick()
}

// IOSSettingSliderRow (lines 358-380): Velocity-based haptic, not distance-based
detectDragGestures(
    onDragStart = { 
        coroutineScope.launch { thumbScale.animateTo(1.25f, PhysicsSpring.uiFast()) }
        hapticEngine.perform(HapticFeedbackEngine.HapticStyle.CONTINUOUS_HUM)  // Start continuous
    },
    onDragEnd = {
        coroutineScope.launch { thumbScale.animateTo(1f, PhysicsSpring.uiStandard()) }
        hapticEngine.perform(HapticFeedbackEngine.HapticStyle.KICK)  // Commit tick
    },
) { change, _ ->
    change.consume()
    val v = xToValue(change.position.x)
    onValueChange(v)
    // REMOVE per-5% tick haptic
    // Instead: if velocity high, maybe notch tick — but keep simple for now
}
```

### PhysicsSpring.kt — Add haptic-spring mapping token
```kotlin
// New companion object or top-level:
object HapticSpringMap {
    // Visual spring spec → Haptic style + parameters
    fun hapticForSpring(spring: SpringSpec<*>): HapticStyle = when {
        spring == PhysicsSpring.uiFast() -> HapticStyle.IMPACT      // 150ms press
        spring == PhysicsSpring.uiStandard() -> HapticStyle.CONTINUOUS_HUM  // 300ms toggle/slider
        spring == PhysicsSpring.elasticSelect() -> HapticStyle.SELECTION  // segmented commit
        spring == PhysicsSpring.bouncyRelease() -> HapticStyle.KICK  // button release bounce
        else -> HapticStyle.NONE
    }
    
    // Reduced motion: all decorative → NONE, functional → SOFT_TAP
    fun hapticForReducedMotion(original: HapticStyle): HapticStyle = when (original) {
        HapticStyle.KICK, HapticStyle.IMPACT -> HapticStyle.SOFT_TAP
        HapticStyle.SELECTION, HapticStyle.LIGHT_TICK -> HapticStyle.NONE
        HapticStyle.CONTINUOUS_HUM -> HapticStyle.NONE
        else -> HapticStyle.NONE
    }
}
```

### ReducedMotion integration
```kotlin
// In HapticDashboardActivity.kt root:
val prefersReducedMotion = LocalPrefersReducedMotion.current
val hapticEngine = remember { HapticFeedbackEngine.create(context) }

// Wrapper that respects reduced motion
val performHaptic: (HapticStyle) -> Unit = { style ->
    val actualStyle = if (prefersReducedMotion) 
        HapticSpringMap.hapticForReducedMotion(style) 
    else 
        style
    if (actualStyle != HapticStyle.NONE) hapticEngine.perform(actualStyle)
}

// Use performHaptic everywhere instead of hapticEngine.perform()
```

## Repo conventions to follow

- `HapticFeedbackEngine` in `HapticDashboardActivity.kt` (lines 147-177) — already creates haptic engine per-component.
- `PhysicsSpring` tokens for all visual springs — haptic mapping follows same tokens.
- `rememberBouncyPress()` for button press/release — already returns controller with press/release separation.
- CompositionLocal for reduced motion (Plan 002) — haptic wrapper reads from it.

## Steps

1. **HapticFeedbackEngine.kt** (or inline in Dashboard) — Add `CONTINUOUS_HUM`, `SOFT_TAP`, `NONE` styles; implement `perform()` with amplitude/duration per style.
2. **HapticDashboardActivity.kt** — `IOSSegmentedControl`: remove drag haptic, keep only commit haptic. `IOSButton`: split press/release haptics matching visual phases. `IOSSettingSliderRow`: replace per-5% ticks with drag-start continuous + drag-end commit.
3. **PhysicsSpring.kt** — Add `HapticSpringMap` object with spring→haptic mapping and reduced-motion translation.
4. **HapticDashboardActivity.kt** — Wire `performHaptic` wrapper using `LocalPrefersReducedMotion`; replace all `hapticEngine.perform()` calls.

## Boundaries

- Do NOT change `HapticEngine.kt` (DSP core) — this is UI feedback haptics only.
- Do NOT add new vibration primitives — use existing `VibrateProxy` / `VibrationEffect` primitives.
- Do NOT change `HapticComposer.kt` / `HapticSynthesizer.kt`.

## Verification

- **Mechanical**: `bash gradlew assembleDebug` — BUILD SUCCESSFUL.
- **Feel check**:
  - Toggle switch: **visual critically damped (300ms) + continuous low hum** — feels like one physical action.
  - Button press: **press → IMPACT (medium), release → KICK (sharp with bounce)** — two distinct phases matching visual.
  - Segmented control drag: **zero haptics during drag**, single `SELECTION` tick on commit — clean.
  - Slider drag: **gentle continuous hum while dragging**, crisp `KICK` on release — matches thumb scale animation.
  - Reduced motion ON: **all decorative haptics (slider hum, segmented drag) gone**; only functional commits (button KICK → SOFT_TAP) remain at reduced amplitude.
  - Console expand: **no haptic** (it's a disclosure, not an action).
- **Done when**: Haptic-visual sync feels causal and harmonious; reduced motion respected; no over-haptication.