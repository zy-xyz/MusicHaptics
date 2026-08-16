# Animation & Haptic Improvement Plans

Execution order (dependencies respected):

| # | Plan | Severity | Depends On | Status |
|---|------|----------|------------|--------|
| 001 | Industrial-Grade Haptic Optimization | HIGH | — | TODO |
| 002 | iOS-Level Motion Polish for Compose UI | HIGH | — | TODO |
| 003 | Multimodal Haptic-Visual Sync | MEDIUM | 001, 002 | TODO |

## Execution Notes

- **001 and 002 are independent** — can run in parallel. 001 touches haptic DSP core; 002 touches Compose UI only.
- **003 depends on both 001 and 002** — it syncs the haptic styles from 001 with the visual springs from 002, and uses the ReducedMotion CompositionLocal from 002.
- All plans target `bash gradlew assembleDebug` passing.
- Feel-check on **Xiaomi 17 Pro (ESA1016)** for haptics; **Xiaomi 17 Pro / Pixel 8** for UI motion.

## Verification Checklist (All Plans)

- [ ] `bash gradlew assembleDebug` — BUILD SUCCESSFUL
- [ ] No new dependencies added
- [ ] No C++/native bridge changes
- [ ] Reduced motion respected (Plan 002 + 003)
- [ ] Haptic-visual sync causal & harmonious (Plan 003)
- [ ] Industrial-grade haptic feel: crisp, discrete, zero floor (Plan 001)
- [ ] iOS 17+ system UI feel: direct, responsive, accessible (Plan 002)

## Commit Stamp

Plans written at commit: `(run git rev-parse --short HEAD in repo root)`