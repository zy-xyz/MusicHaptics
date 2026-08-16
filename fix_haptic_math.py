import re

with open('/storage/emulated/0/AndroidIDEProjects/MusicHapticsX/app/src/main/java/com/mouya/musichaptics/HapticTimelineScheduler.kt', 'r') as f:
    content = f.read()

new_q = """fun adaptToActuatorQ(qFactor: Float) {
        when {
            qFactor > 16f -> {
                // 0816 ESA / OnePlus 15
                // High Q needs heavy smoothing to avoid pop-rocks. 
                // Smaller alpha = heavier smoothing (more prev value kept)
                maxSlewPerBin = 40
                smootherAlpha = 0.20f 
            }
            qFactor > 12f -> {
                maxSlewPerBin = 60
                smootherAlpha = 0.35f
            }
            else -> {
                maxSlewPerBin = 85
                smootherAlpha = 0.50f
            }
        }
    }"""

content = re.sub(r'fun adaptToActuatorQ\(qFactor: Float\) \{[\s\S]*?\n    \}', new_q, content, count=1)

new_render = """        val raw = IntArray(bins) { (base[it] * outputGain).roundToInt().coerceIn(0, 255) }
        val finalOutput = IntArray(bins)
        var prev = prevWindowTail.toFloat()
        for (i in 0 until bins) {
            val target = raw[i].toFloat()
            
            // Asymmetric Slew rate limiting
            // Fast rise (for kick), slow decay (anti-pop-rocks)
            val diff = target - prev
            val slewedTarget = if (diff > maxSlewPerBin * 1.5f) { 
                prev + (maxSlewPerBin * 1.5f) // Allow 1.5x speed on attack
            } else if (diff < -maxSlewPerBin) {
                prev - maxSlewPerBin
            } else {
                target
            }
            
            // Asymmetric smoothing
            // Alpha means how much TARGET we accept (smaller = smoother).
            // We want very little smoothing on attack to keep the punch.
            val currentAlpha = if (slewedTarget > prev) {
                smootherAlpha * 2f // Double alpha = 2x faster response on attack
            } else {
                smootherAlpha      // Heavy smoothing on decay
            }.coerceIn(0f, 1f)

            prev = (prev * (1f - currentAlpha)) + (slewedTarget * currentAlpha)
            finalOutput[i] = prev.roundToInt().coerceIn(0, 255)
        }
        prevWindowTail = finalOutput.lastOrNull() ?: 0
        return finalOutput"""

content = re.sub(r'        val raw = IntArray\(bins\) \{ \(base\[it\] \* outputGain\)\.roundToInt\(\)\.coerceIn\(0, 255\) \}[\s\S]*?return finalOutput', new_render, content)

with open('/storage/emulated/0/AndroidIDEProjects/MusicHapticsX/app/src/main/java/com/mouya/musichaptics/HapticTimelineScheduler.kt', 'w') as f:
    f.write(content)
