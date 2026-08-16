import re

with open('/storage/emulated/0/AndroidIDEProjects/MusicHapticsX/app/src/main/java/com/mouya/musichaptics/HapticTimelineScheduler.kt', 'r') as f:
    content = f.read()

new_render = """        // Apply slewing and smoothing
        val smoothed = IntArray(bins)
        var lastVal = lastOutputValue.toFloat()

        for (i in 0 until bins) {
            val target = (base[i] * outputGain).roundToInt().coerceIn(0, 255).toFloat()
            
            // Slew rate limiting
            val diff = target - lastVal
            val maxDelta = maxSlewPerBin.toFloat()
            val slewedTarget = if (diff > maxDelta) {
                lastVal + maxDelta
            } else if (diff < -maxDelta) {
                lastVal - maxDelta
            } else {
                target
            }
            
            // One-pole LPF smoothing
            lastVal = (smootherAlpha * lastVal) + ((1f - smootherAlpha) * slewedTarget)
            smoothed[i] = lastVal.roundToInt().coerceIn(0, 255)
        }

        lastOutputValue = smoothed.lastOrNull() ?: 0
        return smoothed"""

# Replace the end of render function
content = re.sub(r'val raw = IntArray\(bins\) \{ \(base\[it\] \* outputGain\)\.roundToInt\(\)\.coerceIn\(0, 255\) \}[\s\S]*?return raw', new_render, content, count=1)

with open('/storage/emulated/0/AndroidIDEProjects/MusicHapticsX/app/src/main/java/com/mouya/musichaptics/HapticTimelineScheduler.kt', 'w') as f:
    f.write(content)
