package jp.noppy.lumamusic.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import kotlin.math.abs

/** Observes vertical travel already handled by the page without consuming any input. */
internal class DockScroll(private val collapseDistance: Float, private val expandDistance: Float) : NestedScrollConnection {
    var compact by mutableStateOf(false)
        private set
    var enabled = true
    private var travel = 0f

    fun expand() {
        travel = 0f
        compact = false
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (enabled && source == NestedScrollSource.UserInput && abs(consumed.y) > abs(consumed.x)) {
            val delta = consumed.y
            val canChange = if (compact) delta > 0f else delta < 0f
            travel = if (canChange) travel + abs(delta) else 0f
            if (travel >= if (compact) expandDistance else collapseDistance) {
                compact = !compact
                travel = 0f
            }
        }
        return Offset.Zero
    }
}

@Composable
internal fun PlaybackDock(
    pager: PagerState,
    compact: Boolean,
    swipeEnabled: Boolean,
    selectTab: (Int) -> Unit,
    modifier: Modifier,
    miniPlayer: (@Composable (Modifier, Float, Float) -> Unit)?,
    onLensOwnershipChanged: (Boolean) -> Unit = {},
) {
    val reduced = LocalReducedMotion.current
    val fontScale = LocalDensity.current.fontScale
    val navHeight = maxOf(64.dp, (48 + 16 * fontScale).dp)
    val miniHeight = 64.dp
    val mergedHeight = 56.dp
    val gap = LumaDock.PanelGap
    // Keep the list clearance stable while the visual dock changes height.
    val reservedHeight = if (miniPlayer != null) navHeight + gap + miniHeight else navHeight

    BoxWithConstraints(modifier.widthIn(max = 540.dp).fillMaxWidth().height(reservedHeight)) {
        val availableWidth = maxWidth
        val hasRoom = availableWidth >= 320.dp && fontScale <= 1.3f
        val progress by animateFloatAsState(
            if (compact && hasRoom && miniPlayer != null) 1f else 0f,
            if (reduced) snap() else spring(dampingRatio = .92f, stiffness = Spring.StiffnessMedium),
            label = "dock collapse",
        )
        val amount = progress.coerceIn(0f, 1f)
        val expandedNavWidth = minOf(416.dp, availableWidth)
        val mergedNavWidth = if (miniPlayer == null) expandedNavWidth else 200.dp
        fun phase(start: Float, end: Float): Float {
            val t = ((amount - start) / (end - start)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }
        val resize = phase(0f, .68f)
        val descend = phase(.36f, 1f)
        val navWidth = lerp(expandedNavWidth, mergedNavWidth, resize)
        val navX = lerp((availableWidth - expandedNavWidth) / 2, 0.dp, if (miniPlayer == null) 0f else resize)
        val joined = miniPlayer != null && amount >= .98f
        val separateChrome = if (joined) 0f else 1f
        val rowHeight = lerp(navHeight, mergedHeight, amount)

        if (joined) {
            DockChrome(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(rowHeight), LumaDock.NavigationRadius, 1f)
        }
        miniPlayer?.invoke(
            Modifier.align(Alignment.BottomStart)
                .offset(x = (mergedNavWidth + 2.dp) * resize, y = -(navHeight + gap) * (1f - descend))
                .width(lerp(availableWidth, (availableWidth - mergedNavWidth - 2.dp).coerceAtLeast(1.dp), resize))
                .height(lerp(miniHeight, mergedHeight, amount)),
            resize,
            separateChrome,
        )
        LumaGlassTabBar(
            pager, swipeEnabled, selectTab,
            Modifier.align(Alignment.BottomStart).offset(x = navX).width(navWidth).height(rowHeight),
            compact = resize,
            surfaceOpacity = separateChrome,
            onLensOwnershipChanged = onLensOwnershipChanged,
        )
    }
}
