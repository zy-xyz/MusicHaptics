import re

with open('/storage/emulated/0/AndroidIDEProjects/MusicHapticsX/app/src/main/java/com/mouya/musichaptics/HapticDashboardActivity.kt', 'r') as f:
    content = f.read()

new_code = """        // Jelly spring: high bounciness for the \"QQ弹弹\" feel.
        val baseOffset = if (selected == DashboardTab.CONSOLE) 0f else computedTabWidthPx
        val lensOffsetPxState = animateFloatAsState(
            targetValue = baseOffset + dragOffset,
            animationSpec = spring(dampingRatio = 0.45f, stiffness = 300f),
            label = \"LensOffset\"
        )
        
        // Glass alpha animates from 0 (flat) to ~1 (full glass) on press.
        val isInteracting = pressedTab != null || dragOffset != 0f
        val glassAlphaState = animateFloatAsState(
            targetValue = if (isInteracting) 1f else 0.35f,
            animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f),
            label = \"GlassAlpha\"
        )
        // Volume preservation morphing: pressed tab indicator squishes vertically and expands horizontally.
        val lensScaleState = animateFloatAsState(
            targetValue = if (isInteracting) 1.15f else 1f,
            animationSpec = spring(dampingRatio = 0.45f, stiffness = 400f),
            label = \"LensScale\"
        )
        
        Box(
            Modifier
                .width(computedTabWidth)
                .fillMaxHeight()
                // GPU accelerated transformations (bypasses recomposition/layout)
                .graphicsLayer { 
                    val s = lensScaleState.value
                    scaleX = s
                    scaleY = s
                    translationX = lensOffsetPxState.value
                }
                .shadow(if (isInteracting) 8.dp else 0.dp, lensShape, ambientColor = IOSColors.blue.copy(alpha=0.4f), spotColor = IOSColors.blue.copy(alpha=0.3f))
                .clip(lensShape)
                // Only the selection indicator gets the glass treatment.
                .hazeEffect(backdrop) {
                    blurRadius = 24.dp
                    // Read state directly inside the lambda to avoid outer recomposition
                    alpha = glassAlphaState.value
                    noiseFactor = 0.03f
                    backgroundColor = Color.Transparent
                }
                // Instead of using State in Modifiers which causes recomposition,
                // we use drawBehind to draw the background and border dynamically in the render phase.
                .drawBehind {
                    val ga = glassAlphaState.value
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.4f + ga * 0.4f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(22.dp.toPx(), 22.dp.toPx())
                    )
                    // Simple border
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.5f + ga * 0.5f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(22.dp.toPx(), 22.dp.toPx()),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 0.6.dp.toPx())
                    )
                }
        )"""

# Find the pattern to replace
pattern = r'        // Jelly spring: high bounciness for the "QQ弹弹" feel\.[\s\S]*?\.border\(0\.6\.dp, Color\.White\.copy\(alpha = 0\.5f \+ glassAlpha \* 0\.5f\), lensShape\)\n        \)'

content = re.sub(pattern, new_code, content, count=1)

# Also we need to add the import for drawBehind
if 'import androidx.compose.ui.draw.drawBehind' not in content:
    content = content.replace('import androidx.compose.ui.draw.clip', 'import androidx.compose.ui.draw.clip\nimport androidx.compose.ui.draw.drawBehind')

with open('/storage/emulated/0/AndroidIDEProjects/MusicHapticsX/app/src/main/java/com/mouya/musichaptics/HapticDashboardActivity.kt', 'w') as f:
    f.write(content)
