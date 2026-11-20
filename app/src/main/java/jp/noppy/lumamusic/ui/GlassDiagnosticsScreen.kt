package jp.noppy.lumamusic.ui

import android.os.Build
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
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Developer-only screen selected by the debug launch intent or instrumentation. */
@Composable
fun GlassDemoScreen() {
    var quality by remember { mutableStateOf(GlassQuality.BALANCED) }
    var effects by remember { mutableStateOf(GlassEffects()) }
    var motion by remember { mutableStateOf(true) }
    var checker by remember { mutableStateOf(false) }
    var dark by remember { mutableStateOf(false) }
    val pager = rememberPagerState { LumaTabs.size }
    val scope = rememberCoroutineScope()
    LumaTheme(if (dark) "dark" else "light") {
        CompositionLocalProvider(
            LocalGlassQuality provides quality,
            LocalGlassEffects provides effects,
            LocalReducedMotion provides !motion,
            LocalContentColor provides MaterialTheme.colorScheme.onBackground,
        ) {
            LiquidBackdropHost(
                Modifier.fillMaxSize(),
                content = {
                    HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
                        DiagnosticBackdrop(checker = checker, page = page)
                    }
                },
                overlay = {
                    Box(
                        Modifier.align(Alignment.Center).padding(horizontal = 24.dp)
                            .widthIn(max = 280.dp).fillMaxWidth().height(68.dp)
                            .semantics { contentDescription = "屈折の検証面" }
                            .liquidSurface(selectionLens = true),
                    )
                    LumaGlassTabBar(
                        pager = pager,
                        swipeEnabled = true,
                        onSelect = { page -> scope.launch {
                            if (motion) pager.animateScrollToPage(page) else pager.scrollToPage(page)
                        } },
                        modifier = Modifier.align(Alignment.BottomCenter).safeDrawingPadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                    Column(
                        Modifier.align(Alignment.TopCenter).fillMaxWidth().safeDrawingPadding()
                            .background(MaterialTheme.colorScheme.surface).padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("ガラス描画の診断", style = MaterialTheme.typography.titleMedium)
                        Text("API ${Build.VERSION.SDK_INT} · ${quality.label}", style = MaterialTheme.typography.labelSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            DiagnosticChip("Blur", effects.blur) { effects = effects.copy(blur = !effects.blur) }
                            DiagnosticChip("Refraction", effects.refraction) { effects = effects.copy(refraction = !effects.refraction) }
                            DiagnosticChip("Highlight", effects.highlight) { effects = effects.copy(highlight = !effects.highlight) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            DiagnosticChip("Tint", effects.tint) { effects = effects.copy(tint = !effects.tint) }
                            DiagnosticChip("Shadow", effects.shadow) { effects = effects.copy(shadow = !effects.shadow) }
                            DiagnosticChip("Motion", motion) { motion = !motion }
                        }
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            GlassQuality.entries.forEach { option ->
                                DiagnosticChip(option.label, quality == option) { quality = option }
                            }
                        }
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            DiagnosticChip("文字", !checker) { checker = false }
                            DiagnosticChip("赤青チェック", checker) { checker = true }
                            DiagnosticChip("ライト", !dark) { dark = false }
                            DiagnosticChip("ダーク", dark) { dark = true }
                        }
                    }
                },
            )
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
private fun DiagnosticBackdrop(checker: Boolean, page: Int) {
    val background = MaterialTheme.colorScheme.background
    val foreground = MaterialTheme.colorScheme.onBackground
    Column(
        Modifier.fillMaxSize().background(background).verticalScroll(rememberScrollState())
            .semantics { contentDescription = "検証用の背景" },
    ) {
        if (checker) {
            Box(
                Modifier.fillMaxWidth().height(1800.dp).drawWithCache {
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
                },
            )
        } else {
            repeat(24) { row ->
                Column(
                    Modifier.fillMaxWidth().height(80.dp).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        "背景の文字 ${page + 1}・${row + 1}　━━━━━━━━━━",
                        color = foreground,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                    )
                    Text(
                        "ABCDEFGHIJKLMNOPQRSTUVWXYZ　0123456789",
                        color = foreground,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
