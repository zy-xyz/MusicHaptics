import re

with open('/storage/emulated/0/AndroidIDEProjects/MusicHapticsX/app/src/main/java/com/mouya/musichaptics/HapticEngine.kt', 'r') as f:
    content = f.read()

new_code = """                                // One 100ms timeline window is the only semantic rendering path.
                                // In normal native mode, nativeSamples contains the full 5-layer mix.
                                val calibratedAmplitudes = hapticTimeline.render(
                                    nativeSamples = frameBuffer,
                                    sampleCount = usableSampleCount,
                                    windowStartMs = frameStartTime,
                                    structure = currentMusicStructure,
                                    outputGain = outputGainForPackage(targetPackage)
                                )
                                val finalMax = calibratedAmplitudes.maxOrNull() ?: 0
                                if (finalMax > 0) {
                                    // v3.12 Dual-Track: Compress the waveform to prevent 10Hz stutter.
                                    // Android's HAL hates 10ms fragmented arrays. It causes 'pop rocks' (10 stutters/sec).
                                    // We merge adjacent amplitudes if they are close enough (within 15/255).
                                    val cDurations = mutableListOf<Long>()
                                    val cAmplitudes = mutableListOf<Int>()
                                    var currentDur = 0L
                                    var currentAmp = -1

                                    for (amp in calibratedAmplitudes) {
                                        if (currentAmp == -1) {
                                            currentAmp = amp
                                            currentDur = sampleDurationMs
                                        } else if (Math.abs(amp - currentAmp) < 15) {
                                            currentDur += sampleDurationMs
                                            // Slowly bias towards the new amp to drift smoothly
                                            currentAmp = (currentAmp * 0.7f + amp * 0.3f).toInt()
                                        } else {
                                            cDurations.add(currentDur)
                                            cAmplitudes.add(currentAmp)
                                            currentAmp = amp
                                            currentDur = sampleDurationMs
                                        }
                                    }
                                    if (currentDur > 0) {
                                        cDurations.add(currentDur)
                                        cAmplitudes.add(currentAmp)
                                    }
                                    vibrateProxy.performWaveform(cDurations.toLongArray(), cAmplitudes.toIntArray())
                                }"""

content = re.sub(r'                                // One 100ms timeline window is the only semantic rendering path\.[\s\S]*?if \(finalMax > 0\) vibrateProxy\.performWaveform\(timings, calibratedAmplitudes\)', new_code, content)

with open('/storage/emulated/0/AndroidIDEProjects/MusicHapticsX/app/src/main/java/com/mouya/musichaptics/HapticEngine.kt', 'w') as f:
    f.write(content)
