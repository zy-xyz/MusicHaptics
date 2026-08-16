import re

with open('/storage/emulated/0/AndroidIDEProjects/MusicHapticsX/app/src/main/java/com/mouya/musichaptics/HapticDashboardActivity.kt', 'r') as f:
    content = f.read()

new_tab_bar = """@Composable
private fun LiquidGlassTabBar(
    selected: DashboardTab,
    onSelected: (DashboardTab) -> Unit,
    backdrop: HazeState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val haptic = remember { HapticFeedbackEngine.create(context) }
    
    var pressedTab by remember { mutableStateOf<DashboardTab?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val barShape = RoundedCornerShape(28.dp)
    val lensShape = RoundedCornerShape(22.dp)

    BoxWithConstraints(
        modifier
            .width(160.dp)
            .height(58.dp)
            .shadow(16.dp, barShape, ambientColor = Color.Black.copy(alpha = 0.08f), spotColor = Color.Black.copy(alpha = 0.12f))
            .clip(barShape)
            // The main bar gets the heavy haze blur. Since it doesn't move,
            // it won't trigger 120fps recalculations (unless background logs scroll).
            .hazeEffect(backdrop) {
                blurRadius = 32.dp
                noiseFactor = 0.04f
                backgroundColor = Color.Transparent
            }
            .background(Color(0xFFF7F7F9).copy(alpha = 0.65f))
            .border(0.5.dp, Color.White.copy(alpha = 0.4f), barShape)
            .padding(5.dp)
    ) {
        val computedTabWidth = maxWidth / 2
        val computedTabWidthPx = with(LocalDensity.current) { computedTabWidth.toPx() }
        
        val baseOffset = if (selected == DashboardTab.CONSOLE) 0f else computedTabWidthPx
        val lensOffsetPxState = animateFloatAsState(
            targetValue = baseOffset + dragOffset,
            animationSpec = spring(dampingRatio = 0.6f, stiffness = 500f),
            label = "LensOffset"
        )
        
        val isInteracting = pressedTab != null || dragOffset != 0f
        val scaleState = animateFloatAsState(
            targetValue = if (isInteracting) 0.92f else 1f,
            animationSpec = spring(dampingRatio = 0.5f, stiffness = 600f),
            label = "LensScale"
        )
        
        // The moving indicator itself is just a simple drawn shape.
        // NO HAZE on the moving part! This makes the animation 120fps buttery smooth.
        Box(
            Modifier
                .width(computedTabWidth)
                .fillMaxHeight()
                .graphicsLayer { 
                    translationX = lensOffsetPxState.value
                    scaleX = scaleState.value
                    scaleY = scaleState.value
                }
                .shadow(if (isInteracting) 6.dp else 2.dp, lensShape, spotColor = Color.Black.copy(alpha = 0.1f))
                .clip(lensShape)
                .background(Color.White)
        )
        Row(Modifier.fillMaxSize()) {
            listOf(DashboardTab.CONSOLE to "控制台", DashboardTab.APPS to "应用").forEach { (tab, title) ->
                val active = selected == tab
                Column(
                    Modifier.weight(1f).fillMaxHeight()
                        .draggable("""

# replace the LiquidGlassTabBar beginning part
content = re.sub(r'@Composable\nprivate fun LiquidGlassTabBar\([\s\S]*?\.draggable\(', new_tab_bar, content, count=1)

with open('/storage/emulated/0/AndroidIDEProjects/MusicHapticsX/app/src/main/java/com/mouya/musichaptics/HapticDashboardActivity.kt', 'w') as f:
    f.write(content)
