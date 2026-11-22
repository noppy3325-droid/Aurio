package jp.noppy.lumamusic.ui

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kyant.backdrop.shadow.Shadow
import jp.noppy.lumamusic.playback.PlaybackUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable fun FloatingGlass(modifier: Modifier = Modifier, radius: Int = 28, padding: PaddingValues = PaddingValues(12.dp), content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.liquidSurface(radius).padding(padding), content = content)
}

/** UI-only component: transport events are supplied by the playback owner. */
@Composable internal fun LumaGlassMiniPlayer(
    playback: PlaybackUi,
    modifier: Modifier,
    trackGesture: Modifier,
    open: () -> Unit,
    toggle: () -> Unit,
    next: () -> Boolean,
    previous: () -> Boolean,
    compact: Float,
    surfaceOpacity: Float,
    tracks: @Composable BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val reduced = LocalReducedMotion.current
    val lift by animateFloatAsState(if (pressed && !reduced) .65f else 0f, label = "mini player lens lift")
    Box(modifier.fillMaxWidth()) {
        DockChrome(Modifier.matchParentSize(), LumaDock.NavigationRadius, surfaceOpacity) { lift }
        Column(Modifier.fillMaxSize().padding(horizontal = lerp(10.dp, 4.dp, compact))) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.weight(1f).fillMaxHeight().clipToBounds().then(trackGesture)
                    .clickable(interactionSource = interaction, indication = LocalIndication.current, role = Role.Button, onClickLabel = "プレイヤーを開く", onClick = open)
                    .semantics {
                        customActions = buildList {
                            if (playback.previousItem != null) add(CustomAccessibilityAction("前の曲", previous))
                            if (playback.nextItem != null) add(CustomAccessibilityAction("次の曲", next))
                        }
                    },
                content = tracks,
            )
            IconButton(toggle) { Icon(if (playback.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playback.playing) "一時停止" else "再生", Modifier.size(27.dp)) }
            if (compact < LumaDock.MiniNextHideAt) {
                IconButton({ next() }, enabled = playback.nextItem != null) { Icon(Icons.Rounded.SkipNext, "次の曲", Modifier.size(27.dp)) }
            }
        }
        if (playback.duration > 0) LinearProgressIndicator(progress = { (playback.position.toFloat() / playback.duration).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(1.dp), color = LocalContentColor.current.copy(.5f), trackColor = Color.Transparent, drawStopIndicator = {})
        }
    }
}

internal data class LumaTab(val key: String, val label: String, val icon: ImageVector)

internal val LumaTabs = listOf(
    LumaTab("home", "ホーム", AurioTabIcons.Home),
    LumaTab("library", "ライブラリ", AurioTabIcons.Library),
    LumaTab("search", "検索", AurioTabIcons.Search),
    LumaTab("playlists", "プレイリスト", AurioTabIcons.Playlists),
)

private val NavigationShadow = Shadow(radius = 14.dp, color = Color.Black.copy(alpha = .08f))

@Composable
internal fun DockChrome(modifier: Modifier, radius: Int, opacity: Float, lift: () -> Float = { 0f }) {
    if (opacity > .01f) {
        val layer = if (opacity >= 1f) modifier else modifier.graphicsLayer { alpha = opacity }
        Box(layer.liquidSurface(radius, highlight = LumaDock.GlassHighlight, shadow = NavigationShadow, lensLift = lift))
    }
}

private enum class LensPhase { Idle, PickedUp, Dragging, Settling }

/** Ephemeral touch geometry. The pager remains the owner of the selected tab. */
private class NavigationLensMotion(private val scope: CoroutineScope, tabCount: Int) {
    val bounds = mutableStateMapOf<Int, Rect>()
    val anchors by derivedStateOf {
        val ordered = (0 until tabCount).map { bounds[it]?.center?.x ?: return@derivedStateOf null }
        LensAnchors(ordered)
    }
    private var previewTabPosition by mutableStateOf<Float?>(null)
    private var springJob: Job? = null
    private var generation = 0
    var phase by mutableStateOf(LensPhase.Idle)
        private set
    var deformation by mutableFloatStateOf(0f)
        private set
    val held: Boolean get() = phase == LensPhase.PickedUp || phase == LensPhase.Dragging

    fun position(pagerPosition: Float): Float = previewTabPosition ?: pagerPosition

    fun pickUp(position: Float): Int {
        val token = interruptSpring()
        previewTabPosition = position
        phase = LensPhase.PickedUp
        return token
    }

    fun isHolding(token: Int): Boolean = generation == token && held

    fun drag(position: Float, stretch: Float) {
        previewTabPosition = position
        deformation = stretch
        phase = LensPhase.Dragging
    }

    fun reset() {
        interruptSpring()
        finish()
    }

    private fun interruptSpring(): Int {
        generation++
        springJob?.cancel()
        return generation
    }

    private fun finish() {
        previewTabPosition = null
        phase = LensPhase.Idle
        deformation = 0f
    }

    fun settle(pager: PagerState, target: Int, initialVelocity: Float, reduced: Boolean, select: ((Int) -> Unit)?) {
        val start = position(pager.indicatorPage(reduced))
        val token = interruptSpring()
        previewTabPosition = if (reduced) target.toFloat() else start
        phase = LensPhase.Settling
        select?.invoke(target)
        springJob = scope.launch {
            try {
                if (!reduced) {
                    AnimationState(start, initialVelocity = initialVelocity.coerceIn(-8f, 8f)).animateTo(
                        target.toFloat(),
                        spring(dampingRatio = .94f, stiffness = Spring.StiffnessMedium, visibilityThreshold = .004f),
                    ) {
                        previewTabPosition = value
                        deformation = (abs(velocity) * .0035f).coerceAtMost(.036f)
                    }
                }
                // Keep the lens on its destination while a committed Pager move
                // settles, including when a new touch is cancelled mid-animation.
                withTimeoutOrNull(1_800L) {
                    snapshotFlow { pager.settledPage == target && !pager.isScrollInProgress }.first { it }
                }
            } finally {
                if (generation == token) finish()
            }
        }
    }
}

@Composable internal fun LumaGlassTabBar(
    pager: PagerState,
    swipeEnabled: Boolean,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    compact: Float = 0f,
    surfaceOpacity: Float = 1f,
    onLensOwnershipChanged: (Boolean) -> Unit = {},
) {
    val reduced = LocalReducedMotion.current
    val scope = rememberCoroutineScope()
    val select by rememberUpdatedState(onSelect)
    val changeOwnership by rememberUpdatedState(onLensOwnershipChanged)
    val haptics = LocalHapticFeedback.current
    val motion = remember(scope) { NavigationLensMotion(scope, LumaTabs.size) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle, motion) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (!resumed) motion.reset()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            changeOwnership(false)
        }
    }
    val tabInteractions = remember { List(LumaTabs.size) { MutableInteractionSource() } }
    val tabPresses = tabInteractions.map { it.collectIsPressedAsState() }
    var measuredSize by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(swipeEnabled, reduced) { motion.reset() }
    val lift by animateFloatAsState(
        if ((tabPresses.any { it.value } || motion.held || pager.isScrollInProgress) && !reduced) 1f else 0f,
        spring(dampingRatio = .8f, stiffness = Spring.StiffnessMediumLow * 2f),
        label = "navigation lens lift",
    )
    val materialLift = { if (!reduced && motion.held) lift.coerceAtLeast(.18f) else lift }

    Box(
        modifier.widthIn(max = 416.dp).fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        DockChrome(Modifier.matchParentSize(), LumaDock.NavigationRadius, surfaceOpacity)
        Box(
            Modifier.padding(4.dp).fillMaxWidth().onSizeChanged { measuredSize = it }
                .dragNavigationLens(pager, motion, swipeEnabled && resumed, reduced, measuredSize,
                    changeOwnership = { changeOwnership(it) },
                    tick = { haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick) },
                    select = { select(it) },
                ),
        ) {
            Box(
                Modifier.matchParentSize().positionNavigationLens(pager, motion, reduced, compact, materialLift)
                    .liquidSurface(selectionLens = true, highlight = LumaDock.GlassHighlight, shadow = NavigationShadow, lensLift = materialLift),
            )
            Row(Modifier.fillMaxWidth().selectableGroup()) {
                LumaTabs.forEachIndexed { index, tab ->
                    val anchor = Modifier.weight(1f).onGloballyPositioned {
                        val topLeft = it.positionInParent()
                        motion.bounds[index] = Rect(topLeft.x, topLeft.y, topLeft.x + it.size.width, topLeft.y + it.size.height)
                    }
                    NavigationTab(tab, pager.settledPage == index, index, compact, tabInteractions[index], {
                        motion.settle(pager, index, 0f, reduced) { select(it) }
                    }, {
                        motion.position(pager.indicatorPage(reduced))
                    }, anchor)
                }
            }
        }
    }
}

private fun Modifier.dragNavigationLens(
    pager: PagerState,
    motion: NavigationLensMotion,
    enabled: Boolean,
    reduced: Boolean,
    size: IntSize,
    changeOwnership: (Boolean) -> Unit,
    tick: () -> Unit,
    select: (Int) -> Unit,
) = pointerInput(pager, motion, enabled, reduced, size) {
    if (!enabled) return@pointerInput
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (pager.isScrollInProgress && motion.phase != LensPhase.Settling) return@awaitEachGesture
        val anchors = motion.anchors ?: return@awaitEachGesture
        val startPosition = motion.position(pager.indicatorPage(reduced)).coerceIn(0f, (pager.pageCount - 1).toFloat())
        val start = motion.bounds[startPosition.roundToInt()] ?: return@awaitEachGesture
        val startX = anchors.xAt(startPosition)
        if (down.position.y !in start.top..start.bottom || abs(down.position.x - startX) > start.width / 2f) {
            return@awaitEachGesture
        }
        val token = motion.pickUp(startPosition)
        changeOwnership(true)
        val velocity = VelocityTracker()
        velocity.addPosition(down.uptimeMillis, down.position)
        var delta = 0f
        var crossed = startPosition.roundToInt()
        fun followFinger(deformation: Float) {
            val position = anchors.positionAt(startX + delta)
            motion.drag(position, deformation)
            val nearest = position.roundToInt()
            // A small dead band avoids repeated ticks if the finger barely jitters
            // across a midpoint. Fast movement is limited to one tick per event.
            if (nearest != crossed && abs(position - crossed) >= .54f) {
                crossed = nearest
                if (!reduced) tick()
            }
        }
        try {
            val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ ->
                if (motion.isHolding(token)) {
                    change.consume()
                    delta = change.position.x - down.position.x
                }
            } ?: return@awaitEachGesture
            if (!motion.isHolding(token)) return@awaitEachGesture
            velocity.addPosition(drag.uptimeMillis, drag.position)
            var activePointerId = drag.id
            var lastSpeed = 0f
            followFinger(0f)
            val released = horizontalDrag(drag.id) { change ->
                if (!motion.isHolding(token)) return@horizontalDrag
                val dx = change.positionChange().x
                delta += dx
                val elapsed = (change.uptimeMillis - change.previousUptimeMillis).coerceAtLeast(1)
                val speed = (abs(dx) * 1000f / elapsed / 1500.dp.toPx()).coerceIn(0f, 1f)
                val stretch = (speed * .036f + (speed - lastSpeed) * .014f).coerceIn(-.014f, .048f)
                if (change.id != activePointerId) {
                    activePointerId = change.id
                    velocity.resetTracking()
                }
                lastSpeed = speed
                velocity.addPosition(change.uptimeMillis, change.position)
                change.consume()
                followFinger(motion.deformation * .38f + stretch * .62f)
            }
            if (released && motion.isHolding(token)) {
                currentEvent.changes.firstOrNull { it.id == activePointerId }?.let {
                    velocity.addPosition(it.uptimeMillis, it.position)
                }
                val center = startX + delta
                val velocityX = velocity.calculateVelocity().x
                val target = anchors.snapTarget(center, velocityX)
                motion.settle(pager, target, anchors.pageVelocity(center, velocityX), reduced, select)
            }
        } catch (cancelled: CancellationException) {
            if (motion.isHolding(token)) motion.reset()
            throw cancelled
        } finally {
            if (motion.isHolding(token)) {
                motion.settle(pager, pager.targetPage, 0f, reduced, select = null)
            }
            changeOwnership(false)
        }
    }
}

private fun Modifier.positionNavigationLens(
    pager: PagerState,
    motion: NavigationLensMotion,
    reduced: Boolean,
    compact: Float,
    lift: () -> Float,
) = layout { measurable, constraints ->
    if (constraints.maxWidth == 0 || constraints.maxHeight == 0) return@layout layout(constraints.maxWidth, constraints.maxHeight) {}
    val last = pager.pageCount - 1
    val page = motion.position(pager.indicatorPage(reduced)).coerceIn(0f, last.toFloat())
    val index = page.toInt()
    val start = motion.bounds[index]
    val end = motion.bounds[(index + 1).coerceAtMost(last)]
    if (start == null || end == null) return@layout layout(constraints.maxWidth, constraints.maxHeight) {}
    val fraction = page - index
    val center = start.center.x + (end.center.x - start.center.x) * fraction
    val restWidth = (start.width + (end.width - start.width) * fraction).coerceAtLeast(1f)
    val stretch = if (reduced) 1f else 1f + .041f * sin(abs(page - round(page)) * PI).toFloat() + motion.deformation
    val raised = lift().coerceIn(0f, 1f)
    val width = (restWidth * stretch * (.95f + (.115f - .039f * compact) * raised))
        .roundToInt().coerceIn(1, constraints.maxWidth)
    val height = (constraints.maxHeight * (.86f + .14f * raised)).roundToInt().coerceAtLeast(1)
    val rise = (4.dp.toPx() * raised).roundToInt()
    val lens = measurable.measure(Constraints.fixed(width, height))
    layout(constraints.maxWidth, constraints.maxHeight) {
        lens.place((center - width / 2f).roundToInt(), (constraints.maxHeight - height) / 2 - rise)
    }
}

@Composable private fun NavigationTab(
    tab: LumaTab,
    chosen: Boolean,
    index: Int,
    compact: Float,
    interactions: MutableInteractionSource,
    onSelect: () -> Unit,
    visualPosition: () -> Float,
    modifier: Modifier,
) {
    val strength = (1f - abs(visualPosition() - index)).coerceIn(0f, 1f)
    val ink = LocalContentColor.current
    val selectedInk = lerp(ink, MaterialTheme.colorScheme.primary, .22f)
    val tint = lerp(ink, selectedInk, strength)
    Column(
        modifier.heightIn(min = lerp(56.dp, 48.dp, compact)).clip(LumaShapes.Pill)
            .selectable(chosen, interactionSource = interactions, indication = LocalIndication.current, role = Role.Tab, onClick = onSelect)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val labelsVisible = compact < .97f
        Icon(tab.icon, if (labelsVisible) null else tab.label, Modifier.size(22.dp), tint = tint)
        if (labelsVisible) {
            val labelStyle = MaterialTheme.typography.labelSmall
            val labelHeight = with(LocalDensity.current) { labelStyle.lineHeight.toDp() } + 2.dp
            Box(Modifier.height(lerp(labelHeight, 0.dp, compact)).clipToBounds(), contentAlignment = Alignment.BottomCenter) {
                Text(tab.label, Modifier.graphicsLayer { alpha = 1f - compact }, style = labelStyle, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, color = tint)
            }
        }
    }
}

private fun PagerState.indicatorPage(reduced: Boolean): Float =
    if (reduced) settledPage.toFloat() else currentPage + currentPageOffsetFraction
