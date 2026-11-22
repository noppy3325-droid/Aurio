package jp.noppy.lumamusic.ui

import android.os.Build
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Source = GlassEffects(blur = false, refraction = false, highlight = false, tint = false, shadow = false)

private enum class MaterialPreset(val label: String, val effects: GlassEffects) {
    SOURCE("Source only", Source),
    BLUR("Blur only", Source.copy(blur = true)),
    REFRACTION("Refraction only", Source.copy(refraction = true)),
    HIGHLIGHT("Highlight only", Source.copy(highlight = true)),
    FULL("Full glass", GlassEffects()),
}

@Composable
internal fun GlassDiagnosticsScreen() {
    var quality by rememberSaveable { mutableStateOf(GlassQuality.BALANCED) }
    var preset by rememberSaveable { mutableStateOf(MaterialPreset.FULL) }
    var checker by rememberSaveable { mutableStateOf(false) }
    var dark by rememberSaveable { mutableStateOf(false) }
    var showSampling by rememberSaveable { mutableStateOf(false) }
    var lensOwnsInput by remember { mutableStateOf(false) }
    val trace = remember(showSampling, quality) { SamplingTrace() }
    val observer: ((GlassSample) -> Unit)? = remember(showSampling, trace) {
        if (showSampling) trace::record else null
    }
    val pager = rememberPagerState { LumaTabs.size }
    val fling = PagerDefaults.flingBehavior(pager, pagerSnapDistance = PagerSnapDistance.atMost(LumaTabs.lastIndex))
    val scope = rememberCoroutineScope()
    LumaTheme(if (dark) "dark" else "light") {
        CompositionLocalProvider(
            LocalGlassQuality provides quality,
            LocalGlassEffects provides preset.effects,
            LocalGlassSampleObserver provides observer,
            LocalContentColor provides MaterialTheme.colorScheme.onBackground,
        ) {
            LiquidBackdropHost(Modifier.fillMaxSize(), content = {
                HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), flingBehavior = fling, userScrollEnabled = !lensOwnsInput) { page ->
                    OpticalTestPattern(checker, page)
                }
            }, overlay = {
                Box(Modifier.align(Alignment.Center).padding(horizontal = 24.dp)
                    .widthIn(max = 280.dp).fillMaxWidth().height(68.dp).liquidSurface(selectionLens = true))
                LumaGlassTabBar(
                    pager = pager,
                    swipeEnabled = true,
                    onSelect = { page -> scope.launch { pager.animateScrollToPage(page) } },
                    modifier = Modifier.align(Alignment.BottomCenter).safeDrawingPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    onLensOwnershipChanged = { lensOwnsInput = it },
                )
                if (showSampling) SamplingOverlay(trace, Modifier.matchParentSize())
                Column(
                    Modifier.align(Alignment.TopCenter).fillMaxWidth().safeDrawingPadding()
                        .background(MaterialTheme.colorScheme.surface).padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("ガラス描画の診断", style = MaterialTheme.typography.titleMedium)
                    Text("API ${Build.VERSION.SDK_INT} · ${quality.label}", style = MaterialTheme.typography.labelSmall)
                    MaterialPreset.entries.chunked(3).forEach { group ->
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            group.forEach { item -> DiagnosticChip(item.label, preset == item) { preset = item } }
                        }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        GlassQuality.entries.forEach { item -> DiagnosticChip(item.label, quality == item) { quality = item } }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        DiagnosticChip("チェック柄", checker) { checker = !checker }
                        DiagnosticChip("ダーク", dark) { dark = !dark }
                        DiagnosticChip("サンプル範囲", showSampling) { showSampling = !showSampling }
                    }
                }
            })
        }
    }
}

@Composable
private fun DiagnosticChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = {
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    })
}

@Composable
private fun OpticalTestPattern(checker: Boolean, page: Int) {
    val text = MaterialTheme.colorScheme.onBackground
    val paper = MaterialTheme.colorScheme.background
    Column(Modifier.fillMaxSize().background(paper).verticalScroll(rememberScrollState())) {
        if (checker) {
            Box(Modifier.fillMaxWidth().height(1800.dp).drawWithCache {
                val tile = 15.dp.toPx()
                val redSquares = Path()
                val rows = (size.height / tile).toInt() + 1
                val columns = (size.width / tile).toInt() + 1
                for (row in 0 until rows) {
                    for (column in (row + page) % 2 until columns step 2) {
                        redSquares.addRect(Rect(column * tile, row * tile, (column + 1) * tile, (row + 1) * tile))
                    }
                }
                onDrawBehind {
                    drawRect(Color(0xFF2768CF))
                    drawPath(redSquares, Color(0xFFDD3C35))
                }
            })
        } else {
            repeat(24) { index ->
                Column(
                    Modifier.fillMaxWidth().height(80.dp).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("背景の文字 ${page + 1}・${index + 1}　━━━━━━━━━━", color = text, maxLines = 1)
                    Text("ABCDEFGHIJKLMNOPQRSTUVWXYZ　0123456789", color = text, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
            }
        }
    }
}

private class SamplingTrace {
    private val surfaces = LinkedHashMap<Any, GlassSample>()
    private var sampleCount = 0L
    private var lastSampleAt = 0L

    // This callback runs during draw. The overlay reads a periodic copy without
    // writing Compose state or invalidating the source during capture itself.
    fun record(sample: GlassSample) {
        surfaces[sample.surfaceId] = sample
        sampleCount++
        lastSampleAt = SystemClock.uptimeMillis()
    }

    fun snapshot(): SamplingSnapshot = SamplingSnapshot(
        surfaces.values.toList(), sampleCount, SystemClock.uptimeMillis() - lastSampleAt,
    )
}

private data class SamplingSnapshot(val surfaces: List<GlassSample>, val count: Long, val ageMillis: Long)

@Composable
private fun SamplingOverlay(trace: SamplingTrace, modifier: Modifier) {
    var snapshot by remember(trace) { mutableStateOf(trace.snapshot()) }
    LaunchedEffect(trace) {
        while (true) {
            delay(125)
            snapshot = trace.snapshot()
        }
    }
    Box(modifier) {
        Canvas(Modifier.matchParentSize()) {
            snapshot.surfaces.lastOrNull()?.sourceBounds?.let { source ->
                drawRect(Color(0xFF24C9DE), source.topLeft, source.size, style = Stroke(1.dp.toPx()))
            }
            snapshot.surfaces.forEach { surface ->
                val sample = surface.sampleBounds
                val ink = if (surface.selectionLens) Color(0xFFFFAD4D) else Color(0xFFE4CD53)
                drawRect(ink, sample.topLeft, sample.size, style = Stroke(1.dp.toPx()))
            }
        }
        val revision = snapshot.surfaces.maxOfOrNull { it.sourceRevision }
        val label = if (revision == null) "キャプチャなし" else {
            "背景記録 $revision · サンプル ${snapshot.count} · ${snapshot.ageMillis} ms前"
        }
        Text(
            label,
            Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(bottom = 88.dp)
                .background(MaterialTheme.colorScheme.surface).padding(horizontal = 8.dp, vertical = 4.dp),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}
