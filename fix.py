import re

with open('/storage/emulated/0/AndroidIDEProjects/MusicHapticsX/app/src/main/java/com/mouya/musichaptics/HapticTimelineScheduler.kt', 'r') as f:
    content = f.read()

new_func = """fun adaptToActuatorQ(qFactor: Float) {
        when {
            qFactor > 16f -> {
                maxSlewPerBin = 85
                smootherAlpha = 0.50f
            }
            qFactor > 12f -> {
                maxSlewPerBin = 120
                smootherAlpha = 0.65f
            }
            else -> {
                maxSlewPerBin = 160
                smootherAlpha = 0.80f
            }
        }
    }"""

# Find the function and replace it
content = re.sub(r'fun adaptToActuatorQ\(qFactor: Float\) \{[\s\S]*?\n    \}', new_func, content, count=1)

with open('/storage/emulated/0/AndroidIDEProjects/MusicHapticsX/app/src/main/java/com/mouya/musichaptics/HapticTimelineScheduler.kt', 'w') as f:
    f.write(content)
