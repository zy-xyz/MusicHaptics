# 001 — Industrial-Grade Haptic Optimization (v3.13.6 → v3.14)

- **Status**: TODO
- **Commit**: (to be filled at write time)
- **Severity**: HIGH
- **Category**: Physicality & purpose / Performance / Cohesion
- **Estimated scope**: 4 files (HapticTimelineScheduler.kt, HapticComposer.kt, HapticSynthesizer.kt, DeviceProfile.kt)

## Problem

The haptic pipeline currently achieves "NETEASE crispness" in v3.13.6 but has remaining industrial-grade gaps:

1. **Cross-window tail persistence eliminated but inter-bin smoothing still heavy** — `HapticTimelineScheduler.kt:220-225` uses `smootherAlpha * 2f` for decay, which on high-Q actuators (Xiaomi 17 Pro Q=18, OnePlus 15 Q=16) still produces audible "pop-rocks" mechanical transients between 10ms bins. The slew-rate limiter at `maxSlewPerBin` is adaptive per Q but the one-pole LPF alpha is still too high on decay.

2. **ADSR envelope still has sustain leakage** — `HapticComposer.kt:558-559` state 3 (sustain) does `adsrEnvelopeValue = adsrEnvelopeValue * 0.95f + targetDrive * ADSR_SUSTAIN_LEVEL * 0.05f`, which means continuous bass creates a non-zero floor. NETEASE "嗨动模式" has true zero floor in silence.

3. **HapticSynthesizer triple-ADSR not fully aligned with Composer** — `HapticSynthesizer.kt` has separate `impactAdsr`, `continuousAdsr`, `textureAdsr` but the continuous ADSR sustain level (0.25) still produces body vibration during sustained bass. Industrial grade needs: impact = crisp transient only; continuous = **true zero** when no transient; texture = independent.

4. **Device profiles lack Xiaomi 17 Pro ESA1016 ultra-wideband tuning** — `DeviceProfile.kt:605-627` has `XIAOMI_17_PRO` but with conservative `boostExponent=0.25`, `subDur1Max=18ms`. ESA1016 130Hz 600mm³ motor can go sharper: shorter pulses, lower boost, tighter gaps.

5. **Thermal model uses fixed Rth/Cth** — `HapticComposer.kt:68-70`, `HapticSynthesizer.kt:31-32` use hardcoded `THERMAL_RTH=25f`, `THERMAL_CTH=1.2f`. Real actuators have device-specific thermal mass (ESA1016 ~1.8x CSA0916). Should pull from `ActuatorProfile`.

6. **LRA drive strategy in Composer still applies gain on beat/transient** — `HapticComposer.kt:702-711` multiplies by 1.35x/1.15x. On high-Q actuators the LRA resonance *already* amplifies transients; extra gain causes overshoot. Should be 1.0x for high-Q profiles.

## Target

### HapticTimelineScheduler.kt
```kotlin
// Line ~21: Adaptive smoothing per Q factor — tighter for high-Q
// Current Q>16: maxSlew=40, alpha=0.20 → Target Q>16: maxSlew=30, alpha=0.12
// Current Q 13-16: maxSlew=60, alpha=0.35 → Target Q 13-16: maxSlew=45, alpha=0.22
// Current Q<13: maxSlew=85, alpha=0.50 → Target Q<13: maxSlew=70, alpha=0.35

// Lines 220-225: Asymmetric smoothing — attack fast, decay FASTER (not slower)
val currentAlpha = if (slewedTarget > prev) {
    (smootherAlpha * 3f).coerceIn(0f, 1f)   // Attack: very fast (was 2.5x)
} else {
    (smootherAlpha * 4f).coerceIn(0f, 1f)   // Decay: even faster (was 2x) — NO TAIL
}

// Line 231: prevWindowTail = 0 (already correct in v3.13.6)
```

### HapticComposer.kt
```kotlin
// Lines 63-66: ADSR constants — lower sustain to true zero floor
const val ADSR_ATTACK_TAU = 0.0015f   // 1.5ms (was 1.8ms) — even faster impact
const val ADSR_DECAY_TAU = 0.008f     // 8ms (was 12ms) — no bass smear
const val ADSR_SUSTAIN_LEVEL = 0.05f  // 0.05 (was 0.25) — near-zero floor
const val ADSR_RELEASE_TAU = 0.025f   // 25ms (was 35ms) — instant stop

// Lines 558-559: State 3 sustain — remove floor contribution
// REPLACE:
adsrEnvelopeValue = adsrEnvelopeValue * 0.95f + targetDrive * ADSR_SUSTAIN_LEVEL * 0.05f
// WITH:
adsrEnvelopeValue = adsrEnvelopeValue * 0.90f  // Pure decay toward zero, no targetDrive feed

// Lines 702-711: applyLraDriveStrategy — remove gain multipliers for high-Q
val actuatorQ = profile.actuator.qFactor
val transientGain = if (actuatorQ > 15f) 1.0f else 1.25f
val beatGain = if (actuatorQ > 15f) 1.0f else 1.1f
// Then use transientGain/beatGain instead of hardcoded 1.35f/1.15f
```

### HapticSynthesizer.kt
```kotlin
// Lines 22-27: ADSR matched to Composer's new ultra-crisp values
const val ATTACK_TAU_IMPACT = 0.0015f     // 1.5ms
const val DECAY_TAU_IMPACT = 0.008f       // 8ms
const val ATTACK_TAU_CONTINUOUS = 0.008f  // 8ms (was 12ms) — fast continuous onset
const val DECAY_TAU_CONTINUOUS = 0.025f   // 25ms (was 35ms)
const val RELEASE_TAU = 0.025f            // 25ms
const val SUSTAIN_LEVEL = 0.05f           // 0.05 (was 0.25) — true zero floor

// Lines 160-163: Actuator-aware ADSR scaling — use riseScale/fallScale from profile
// (already correct, just ensure config values match above)

// Lines 313-314: State 3 continuous sustain — remove targetDrive feed
adsr.value = adsr.value * 0.90f  // Pure decay, no floor
```

### DeviceProfile.kt
```kotlin
// Lines 605-627: XIAOMI_17_PRO — ESA1016 ultra-wideband tuning
val XIAOMI_17_PRO = DeviceProfile(
    name = "Xiaomi 17 Pro · ESA1016 Ultra",
    description = "Xiaomi 17 Pro ESA1016 130Hz, 600mm³+, 10-500Hz超宽频, HyperOS 2.0, 旗舰级低频纹理",
    minGuaranteedAmplitude = 6,          // Was 8 — ESA1016 can go lower
    maxAmplitude = 255,
    boostExponent = 0.18f,               // Was 0.25 — ultra-low boost, motor is sensitive
    subDur1Min = 4, subDur1Max = 12,     // Was 6-18 — razor-short pulses
    subGap1Min = 1,  subGap1Max = 5,     // Was 2-7
    subDur2Min = 2,  subDur2Max = 6,     // Was 3-10
    subGap2Min = 1,  subGap2Max = 3,     // Was 1-5
    subDur3Min = 1,  subDur3Max = 4,     // Was 2-6
    subAmpDecay2 = 0.28f,                // Was 0.35 — faster decay
    subAmpDecay3 = 0.08f,                // Was 0.12
    minIntervalMs = 1L,                  // Was 2
    maxIntervalMs = 20L,                 // Was 25
    silenceThreshold = 0.0008f,          // Was 0.0012 — more sensitive
    energyThreshold = 0.025f,            // Was 0.04
    fillerFrameThreshold = 4,            // Was 5
    fillerDurationMs = 1L,               // Was 2
    fillerAmplitude = 1,
    bassBoost = 1.05f,                   // Was 1.1
    actuator = ActuatorProfile.ESA1016_CYBER,  // Needs thermal params updated too
)
```

### ActuatorProfile.kt (new thermal params for ESA1016)
```kotlin
// Add to ESA1016_CYBER profile:
val ESA1016_CYBER = ActuatorProfile(
    // ... existing ...
    thermalRth = 18f,   // Was 25 — ESA1016 larger mass, better heat dissipation
    thermalCth = 2.0f,  // Was 1.2 — higher thermal capacitance
)
```

## Repo conventions to follow

- ADSR constants live in both `HapticComposer` companion and `HapticSynthesizer` companion — **must stay in sync**.
- Device profiles in `DeviceProfile.kt` companion object, actuator params in `ActuatorProfile.kt`.
- Smoothing parameters adapted via `HapticTimelineScheduler.adaptToActuatorQ()` called from `HapticEngine.kt:119-121`.
- Thermal model uses `THERMAL_RTH`/`THERMAL_CTH` from each class's companion — **update both**.

## Steps

1. **HapticTimelineScheduler.kt** — Update `adaptToActuatorQ()` thresholds (lines 32-50) and asymmetric smoothing alphas (lines 220-225).
2. **HapticComposer.kt** — Update ADSR constants (lines 63-66), state 3 sustain logic (lines 558-559), `applyLraDriveStrategy` gain logic (lines 702-711).
3. **HapticSynthesizer.kt** — Update ADSR constants (lines 22-27), continuous ADSR state 3 (lines 313-314).
4. **DeviceProfile.kt** — Replace `XIAOMI_17_PRO` profile with ultra-tuned values; update `ActuatorProfile.ESA1016_CYBER` thermal params.
5. **HapticEngine.kt** — Verify `adaptToActuatorQ()` is called with correct Q factor (line 120).

## Boundaries

- Do NOT change `HapticEventGenerator.kt`, `SemanticEvent.kt`, `HapticPrimitive.kt` — semantic detection is correct.
- Do NOT change native bridge or C++ layer — this is Kotlin-side tuning only.
- Do NOT add new dependencies.

## Verification

- **Mechanical**: `bash gradlew assembleDebug` — BUILD SUCCESSFUL.
- **Feel check on Xiaomi 17 Pro (ESA1016)**:
  - Play "海阔天空" (Beyond) — kick drum at 0:17, 0:45: each hit is a **single, discrete tap** with zero post-ring. No "buzz" tail.
  - Play "BAD GUY" (Billie Eilish) — sub-bass at 0:30: **physical pressure sensation** without continuous vibration. Silence between notes = zero vibration.
  - Play silent track — **absolutely no vibration** (true zero floor).
  - Logcat `HapticTimelineScheduler` tag: `dynGain` should hit 0.000 in silence, peak >1.2 on transients.
  - Logcat `HapticComposer` tag: `adsrEnvelope` should show attack→decay→near-zero (≤0.05) within 30ms of transient end.
- **Reduced motion**: `prefers-reduced-motion` not applicable (haptics), but thermal throttling should engage smoothly at 70°C.
- **Done when**: All 4 files edited, compile passes, Xiaomi 17 Pro feels like iOS Taptic Engine — crisp, discrete, zero floor.