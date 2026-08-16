import re

with open('/storage/emulated/0/AndroidIDEProjects/MusicHapticsX/app/src/main/cpp/haptic/HapticEngine.hpp', 'r') as f:
    content = f.read()

new_code = """        // Add the "Minimum floor of 3" back to prevent full-off gaps, 
        // ONLY if there is actual audio activity (to prevent complete dropout/stuttering)
        // and if it's not totally silent.
        if (blocksSinceAudio_ < 50 && amplitude < 3 && percussionSum + bassChannel + vocalChannel + harmonicChannel > 0.05f) {
            amplitude = 3;
        }

        // v3.11: Inter-frame smoothing — prevents step jumps"""

content = re.sub(r'        // v3\.11: Inter-frame smoothing — prevents step jumps', new_code, content, count=1)

with open('/storage/emulated/0/AndroidIDEProjects/MusicHapticsX/app/src/main/cpp/haptic/HapticEngine.hpp', 'w') as f:
    f.write(content)
