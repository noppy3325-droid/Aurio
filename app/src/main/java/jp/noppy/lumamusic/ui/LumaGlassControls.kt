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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import com.kyant.backdrop.shadow.Shadow
import jp.noppy.lumamusic.playback.PlaybackUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.sign
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
        DockChrome(Modifier.matchParentSize(), LumaDock.MiniRadius, surfaceOpacity) { lift }
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
    LumaTab("home", "ホーム", Icons.Rounded.Home),
    LumaTab("library", "ライブラリ", Icons.Rounded.LibraryMusic),
    LumaTab("search", "検索", Icons.Rounded.Search),
    LumaTab("playlists", "プレイリスト", Icons.Rounded.PlaylistPlay),
)

private val NavigationShadow = Shadow(radius = 14.dp, color = Color.Black.copy(alpha = .08f))

@Composable
internal fun DockChrome(modifier: Modifier, radius: Int, opacity: Float, lift: () -> Float = { 0f }) {
    if (opacity > .01f) {
        Box(modifier.graphicsLayer { alpha = opacity }
            .liquidSurface(radius, highlight = LumaDock.GlassHighlight, shadow = NavigationShadow, lensLift = lift))
    }
}

private data class LensHandoff(val fromPage: Int, val toPage: Int, val releaseX: Float)

/** Ephemeral touch geometry. The pager remains the owner of the selected tab. */
private class NavigationLensMotion(private val scope: CoroutineScope) {
    val bounds = mutableStateMapOf<Int, Rect>()
    private var visualCenter by mutableStateOf<Float?>(null)
    private var handoff by mutableStateOf<LensHandoff?>(null)
    private var settling: Job? = null
    var speedStretch by mutableFloatStateOf(0f)
    var pressed by mutableStateOf(false)
    val lifted by derivedStateOf { pressed || visualCenter != null && handoff == null }
    val canStart: Boolean get() = settling?.isActive != true

    fun move(center: Float) { visualCenter = center }

    fun reset() {
        settling?.cancel()
        handoff = null
        visualCenter = null
        speedStretch = 0f
        pressed = false
    }

    fun release(pager: PagerState, origin: Int, target: Int, originX: Float, reduced: Boolean, select: (Int) -> Unit) {
        val releaseX = visualCenter ?: originX
        settling = scope.launch {
            try {
                if (target != origin) {
                    handoff = LensHandoff(origin, target, releaseX)
                    select(target)
                    kotlinx.coroutines.withTimeoutOrNull(1_500L) {
                        snapshotFlow { pager.settledPage }.first { it == target }
                    }
                } else if (!reduced) {
                    AnimationState(releaseX).animateTo(
                        originX,
                        spring(dampingRatio = .94f, stiffness = Spring.StiffnessMedium, visibilityThreshold = .5f),
                    ) { visualCenter = value }
                }
            } finally {
                handoff = null
                visualCenter = null
            }
        }
    }

    fun center(page: Float, fallback: Float): Float {
        val transition = handoff ?: return visualCenter ?: fallback
        val destination = bounds[transition.toPage]?.center?.x ?: return fallback
        val progress = ((page - transition.fromPage) / (transition.toPage - transition.fromPage)).coerceIn(0f, 1f)
        return transition.releaseX + (destination - transition.releaseX) * progress
    }

    fun stretch(page: Float, center: Float, width: Float): Float {
        if (visualCenter == null) return 1f + .07f * sin(abs(page - round(page)) * PI).toFloat()
        val offset = bounds.values.minOf { abs(center - it.center.x) } / width
        return 1f + .065f * offset.coerceIn(0f, .5f) * 2f + speedStretch
    }
}

@Composable internal fun LumaGlassTabBar(
    pager: PagerState,
    swipeEnabled: Boolean,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    compact: Float = 0f,
    surfaceOpacity: Float = 1f,
) {
    val reduced = LocalReducedMotion.current
    val scope = rememberCoroutineScope()
    val select by rememberUpdatedState(onSelect)
    val motion = remember(scope) { NavigationLensMotion(scope) }
    var measuredSize by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(measuredSize, swipeEnabled, reduced) { motion.reset() }
    val lift by animateFloatAsState(
        if ((motion.lifted || pager.isScrollInProgress) && !reduced) 1f else 0f,
        spring(dampingRatio = .78f, stiffness = Spring.StiffnessMedium),
        label = "navigation lens lift",
    )

    Box(
        modifier.widthIn(max = 416.dp).fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        DockChrome(Modifier.matchParentSize(), LumaDock.NavigationRadius, surfaceOpacity)
        Box(
            Modifier.padding(4.dp).fillMaxWidth().onSizeChanged { measuredSize = it }.clip(LumaShapes.Pill)
                .dragNavigationLens(pager, motion, swipeEnabled, reduced, measuredSize) { select(it) },
        ) {
            Box(
                Modifier.matchParentSize().positionNavigationLens(pager, motion, reduced) { lift }
                    .liquidSurface(selectionLens = true, highlight = LumaDock.GlassHighlight, shadow = NavigationShadow, lensLift = { lift }),
            )
            Row(Modifier.fillMaxWidth().selectableGroup()) {
                LumaTabs.forEachIndexed { index, tab ->
                    val anchor = Modifier.weight(1f).onGloballyPositioned {
                        val topLeft = it.positionInParent()
                        motion.bounds[index] = Rect(topLeft.x, topLeft.y, topLeft.x + it.size.width, topLeft.y + it.size.height)
                    }
                    NavigationTab(tab, pager, index, reduced, compact, {
                        motion.reset()
                        select(index)
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
    select: (Int) -> Unit,
) = pointerInput(pager, motion, enabled, reduced, size) {
    if (!enabled) return@pointerInput
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val origin = pager.settledPage
        val start = motion.bounds[origin] ?: return@awaitEachGesture
        if (!start.contains(down.position) || pager.isScrollInProgress || !motion.canStart) return@awaitEachGesture

        val lastIndex = pager.pageCount - 1
        val first = motion.bounds[0]?.center?.x ?: return@awaitEachGesture
        val last = motion.bounds[lastIndex]?.center?.x ?: return@awaitEachGesture
        val direction = (last - first).sign
        val velocity = VelocityTracker()
        velocity.addPosition(down.uptimeMillis, down.position)
        var delta = 0f
        motion.pressed = true
        val drag = try {
            awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ ->
                change.consume()
                delta = change.position.x - down.position.x
            }
        } finally {
            motion.pressed = false
        } ?: return@awaitEachGesture

        val center = start.center.x
        val previous = motion.bounds[origin - 1]?.center?.x
        val next = motion.bounds[origin + 1]?.center?.x
        val low = minOf(center, previous ?: center, next ?: center)
        val high = maxOf(center, previous ?: center, next ?: center)
        fun followFinger() {
            val raw = center + delta
            val clamped = raw.coerceIn(low, high)
            val outerExcess = when {
                origin == 0 && delta * direction < 0f -> delta
                origin == lastIndex && delta * direction > 0f -> delta
                else -> 0f
            }
            motion.move(clamped + (outerExcess * .16f).coerceIn(-6.dp.toPx(), 6.dp.toPx()))
        }
        velocity.addPosition(drag.uptimeMillis, drag.position)
        var activePointerId = drag.id
        followFinger()
        val released = try {
            horizontalDrag(drag.id) { change ->
                val dx = change.positionChange().x
                delta += dx
                val elapsed = (change.uptimeMillis - change.previousUptimeMillis).coerceAtLeast(1)
                motion.speedStretch = (abs(dx) * 1000f / elapsed / 1500.dp.toPx()).coerceIn(0f, 1f) * .035f
                if (change.id != activePointerId) {
                    activePointerId = change.id
                    velocity.resetTracking()
                }
                velocity.addPosition(change.uptimeMillis, change.position)
                change.consume()
                followFinger()
            }
        } catch (cancelled: CancellationException) {
            motion.reset()
            throw cancelled
        } finally {
            motion.speedStretch = 0f
        }
        currentEvent.changes.firstOrNull { it.id == activePointerId }?.let {
            velocity.addPosition(it.uptimeMillis, it.position)
        }
        val neighbor = if (delta * direction < 0f) previous else next
        val distance = neighbor?.let { abs(it - center) } ?: start.width
        val target = if (released) lensDragTarget(
            origin, lastIndex, delta * direction, distance,
            velocity.calculateVelocity().x * direction, 620.dp.toPx(),
        ) else origin
        motion.release(pager, origin, target, center, reduced, select)
    }
}

private fun Modifier.positionNavigationLens(
    pager: PagerState,
    motion: NavigationLensMotion,
    reduced: Boolean,
    lift: () -> Float,
) = layout { measurable, constraints ->
    if (constraints.maxWidth == 0 || constraints.maxHeight == 0) return@layout layout(constraints.maxWidth, constraints.maxHeight) {}
    val last = pager.pageCount - 1
    val page = (pager.currentPage + pager.currentPageOffsetFraction).coerceIn(0f, last.toFloat())
    val index = page.toInt()
    val start = motion.bounds[index]
    val end = motion.bounds[(index + 1).coerceAtMost(last)]
    if (start == null || end == null) return@layout layout(constraints.maxWidth, constraints.maxHeight) {}
    val fraction = page - index
    val center = motion.center(page, start.center.x + (end.center.x - start.center.x) * fraction)
    val restWidth = (start.width + (end.width - start.width) * fraction).coerceAtLeast(1f)
    val stretch = if (reduced) 1f else motion.stretch(page, center, restWidth)
    val raised = lift().coerceIn(0f, 1f)
    val width = (restWidth * stretch * (.96f + .10f * raised)).roundToInt().coerceIn(1, constraints.maxWidth)
    val height = (constraints.maxHeight * (.87f + .13f * raised)).roundToInt().coerceAtLeast(1)
    val lens = measurable.measure(Constraints.fixed(width, height))
    layout(constraints.maxWidth, constraints.maxHeight) {
        lens.place((center - width / 2f).roundToInt(), (constraints.maxHeight - height) / 2)
    }
}

internal fun lensDragTarget(origin: Int, last: Int, delta: Float, distance: Float, velocity: Float, flingThreshold: Float): Int {
    if (distance <= 0f) return origin
    val progress = abs(delta) / distance
    val fastEnough = velocity.sign == delta.sign && abs(velocity) >= flingThreshold
    val direction = if (progress >= .35f || progress >= .08f && fastEnough) delta.sign.toInt() else 0
    return (origin + direction).coerceIn(0, last)
}

@Composable private fun NavigationTab(tab: LumaTab, pager: PagerState, index: Int, reduced: Boolean, compact: Float, onSelect: () -> Unit, modifier: Modifier) {
    val chosen = pager.settledPage == index
    val strength = (1f - abs(pager.indicatorPage(reduced) - index)).coerceIn(0f, 1f)
    val tint = lerp(LocalContentColor.current.copy(alpha = .79f), MaterialTheme.colorScheme.primary, strength)
    Column(
        modifier.heightIn(min = lerp(56.dp, 48.dp, compact)).clip(LumaShapes.Pill)
            .selectable(chosen, role = Role.Tab, onClick = onSelect).padding(vertical = 6.dp),
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
