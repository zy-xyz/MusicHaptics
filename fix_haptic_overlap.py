import re

with open('/storage/emulated/0/AndroidIDEProjects/MusicHapticsX/app/src/main/java/com/mouya/musichaptics/HapticEngine.kt', 'r') as f:
    content = f.read()

new_code = """                                    if (currentDur > 0) {
                                        // OVERLAP BUFFER: Add 20ms tail to the final duration.
                                        // Why? Android Vibrator is not gapless. If we send exactly 100ms of waveform,
                                        // and the coroutine loop wakes up even 1ms late (101ms), the motor physically
                                        // stops and restarts, causing a nasty click/stutter.
                                        // By adding a 20ms sustain tail, the motor keeps spinning until the next
                                        // 100ms loop preempts it with the new waveform. True continuous haptics!
                                        cDurations.add(currentDur + 20L)
                                        cAmplitudes.add(currentAmp)
                                    }
                                    vibrateProxy.performWaveform(cDurations.toLongArray(), cAmplitudes.toIntArray())
                                }"""

content = re.sub(r'                                    if \(currentDur > 0\) \{[\s\S]*?vibrateProxy\.performWaveform\(cDurations\.toLongArray\(\), cAmplitudes\.toIntArray\(\)\)\n                                \}', new_code, content, count=1)

with open('/storage/emulated/0/AndroidIDEProjects/MusicHapticsX/app/src/main/java/com/mouya/musichaptics/HapticEngine.kt', 'w') as f:
    f.write(content)
