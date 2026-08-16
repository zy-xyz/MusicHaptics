import re

with open('/storage/emulated/0/AndroidIDEProjects/MusicHapticsX/app/src/main/cpp/haptic/HapticEngine.hpp', 'r') as f:
    content = f.read()

new_code = """        // STRICT SILENCE POLICY (v3.13):
        // Removed the artificial floor of 3. If there is no clear percussion, bass, 
        // vocal, or harmonic event above the noise floor, the vibration must drop to ZERO.
        // This fixes the 'buzzing like a mosquito during quiet intros' issue (e.g. 李香兰).
        if (amplitude < 4) {
            amplitude = 0;
        }
        // v3.11: Inter-frame smoothing — prevents step jumps"""

# Replace the chunk including the old floor logic
content = re.sub(r'        // Add the "Minimum floor of 3" back to prevent full-off gaps,[\s\S]*?        // v3\.11: Inter-frame smoothing — prevents step jumps', new_code, content, count=1)

with open('/storage/emulated/0/AndroidIDEProjects/MusicHapticsX/app/src/main/cpp/haptic/HapticEngine.hpp', 'w') as f:
    f.write(content)
