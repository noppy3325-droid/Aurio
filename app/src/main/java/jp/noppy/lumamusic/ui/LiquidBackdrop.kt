package jp.noppy.lumamusic.ui

import android.os.Build
import android.os.PowerManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import kotlinx.coroutines.launch

enum class GlassQuality(val label: String) { HIGH("高品質"), BALANCED("バランス"), REDUCED("軽量"), OFF("オフ") }
val LocalLiquidBackdrop = staticCompositionLocalOf<Backdrop?> { null }
val LocalGlassQuality = staticCompositionLocalOf { GlassQuality.BALANCED }
val LocalReducedMotion = staticCompositionLocalOf { false }

@Composable fun effectiveGlassQuality(saved: String, reducedMotion: Boolean): GlassQuality {
    val context = LocalContext.current
    val power = remember(context) { context.getSystemService(PowerManager::class.java) }
    var batterySaver by remember { mutableStateOf(power.isPowerSaveMode) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() { override fun onReceive(context: Context?, intent: Intent?) { batterySaver = power.isPowerSaveMode } }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    val requested = runCatching { GlassQuality.valueOf(saved) }.getOrDefault(GlassQuality.BALANCED)
    return if ((batterySaver || reducedMotion) && requested in listOf(GlassQuality.HIGH, GlassQuality.BALANCED)) GlassQuality.REDUCED else requested
}

/** Record only source content. Floating surfaces are siblings, never part of their own source. */
@Composable fun LiquidBackdropHost(
    modifier: Modifier = Modifier,
    capture: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
    overlay: @Composable BoxScope.() -> Unit,
) {
    val source = if (capture && LocalGlassQuality.current != GlassQuality.OFF && Build.VERSION.SDK_INT >= 31) {
        rememberLayerBackdrop()
    } else {
        null
    }
    CompositionLocalProvider(LocalLiquidBackdrop provides source) {
        Box(modifier) {
            val sourceLayer = if (source == null) Modifier else Modifier.layerBackdrop(source)
            Box(Modifier.matchParentSize().then(sourceLayer), content = content)
            overlay()
        }
    }
}

@Composable fun Modifier.liquidSurface(
    radius: Int = 28,
    highlight: Highlight = Highlight.Default,
    shadow: Shadow = Shadow.Default,
    selectionLens: Boolean = false,
    lensLift: () -> Float = { 0f },
): Modifier {
    val source = LocalLiquidBackdrop.current
    val quality = LocalGlassQuality.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val shape = if (selectionLens) CircleShape else RoundedCornerShape(radius.dp)
    val tint = if (dark) Color(0xFF202027) else Color.White
    // RenderEffect starts on API 31 and the refractive AGSL lens on API 33.
    // Older devices and Off use an honest opaque surface with no claimed blur.
    if (source == null || quality == GlassQuality.OFF || Build.VERSION.SDK_INT < 31) {
        val fill = if (selectionLens) MaterialTheme.colorScheme.primary.copy(alpha = .14f) else tint.copy(alpha = .96f)
        return clip(shape).background(if (!selectionLens && quality == GlassQuality.OFF) tint else fill)
    }
    val accent = MaterialTheme.colorScheme.primary
    return drawBackdrop(
        backdrop = source,
        shape = { shape },
        highlight = { if (selectionLens) highlight.copy(alpha = .60f + .38f * lensLift()) else highlight },
        shadow = { shadow },
        effects = {
            vibrancy()
            // Limit sampled luminance as well as tinting: labels remain readable over
            // black/white jackets, while the live image and edge lens stay visible.
            colorControls(brightness = if (dark) -.18f else .18f, contrast = .6f)
            blur((when {
                selectionLens -> 4f - 2.5f * lensLift()
                quality == GlassQuality.HIGH -> 12f
                quality == GlassQuality.BALANCED -> 8f
                else -> 4f
            }).dp.toPx())
            if (quality != GlassQuality.REDUCED && Build.VERSION.SDK_INT >= 33) {
                val lift = lensLift()
                val depth = if (selectionLens) (10f + 12f * lift).dp else (8f + 5f * lift).dp
                val shift = when {
                    selectionLens -> (16f + 20f * lift).dp
                    quality == GlassQuality.HIGH -> (20f + 5f * lift).dp
                    else -> (12f + 4f * lift).dp
                }
                lens(depth.toPx(), shift.toPx(), chromaticAberration = selectionLens && quality == GlassQuality.HIGH)
            }
        },
        onDrawSurface = {
            if (selectionLens) {
                val lift = lensLift()
                drawRect(tint.copy(alpha = if (dark) .26f - .15f * lift else .17f - .11f * lift))
                drawRect(
                    Brush.verticalGradient(
                        0f to Color.White.copy(alpha = .29f + .22f * lift),
                        .22f to Color.White.copy(alpha = .035f),
                        .75f to accent.copy(alpha = .09f + .04f * lift),
                        1f to accent.copy(alpha = .22f + .10f * lift),
                    )
                )
            } else {
                drawRect(tint.copy(alpha = if (dark) .38f else .26f))
            }
        }
    )
}

/** Debug-only comparison, with the production lens and pager in the same drawing host. */
@Composable fun GlassDemoScreen() {
    var quality by remember { mutableStateOf(GlassQuality.BALANCED) }
    var mode by remember { mutableIntStateOf(2) }
    val pager = rememberPagerState { LumaTabs.size }
    val scope = rememberCoroutineScope()
    LumaTheme("light") {
        CompositionLocalProvider(LocalGlassQuality provides quality) {
            LiquidBackdropHost(Modifier.fillMaxSize(), content = {
                HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                            .background(Color(0xFFF7F6FA)).padding(horizontal = 24.dp, vertical = 60.dp),
                    ) {
                        Text("Backdrop 2.0.1 実描画テスト", style = MaterialTheme.typography.headlineMedium)
                        Text("スクロールしてガラス内の模様の変化を確認")
                        repeat(30) { index ->
                            val start = when ((index + page) % 5) {
                                0 -> Color.White
                                1 -> Color(0xFF777777)
                                2 -> Color(0xFF151515)
                                else -> Color.hsl((index * 37f + page * 60f) % 360, .7f, .65f)
                            }
                            val end = Color.hsl((index * 37f + 90) % 360, .7f, .4f)
                            Row(
                                Modifier.fillMaxWidth().height(112.dp)
                                    .background(Brush.horizontalGradient(listOf(start, end))).padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Artwork(null, "検証アルバム $index", Modifier.size(80.dp), 12)
                                Spacer(Modifier.width(12.dp))
                                Text("背景の文字 ${index + 1}\n━━━━━━", color = if (start.luminance() > .45f) Color.Black else Color.White, style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
            }, overlay = {
                val comparison = when (mode) {
                    0 -> Modifier
                    1 -> Modifier.clip(CircleShape).background(Color.White.copy(alpha = .64f))
                    else -> Modifier.liquidSurface(selectionLens = true)
                }
                Text("背景に追従するピル", Modifier.align(Alignment.Center).then(comparison).padding(horizontal = 24.dp, vertical = 16.dp), color = Color.Black)
                Column(
                    Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Column(Modifier.fillMaxWidth().liquidSurface(24).padding(horizontal = 10.dp)) {
                        Text("ライブガラス", style = MaterialTheme.typography.titleLarge)
                        Text("API ${Build.VERSION.SDK_INT} · ${quality.label}")
                        Row {
                            listOf("効果なし", "半透明", "レンズ").forEachIndexed { index, label ->
                                FilterChip(mode == index, { mode = index }, { Text(label) }, modifier = Modifier.padding(end = 4.dp))
                            }
                        }
                        Row {
                            GlassQuality.entries.forEach { option ->
                                TextButton({ quality = option }) { Text(option.label) }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    LumaGlassTabBar(pager, swipeEnabled = true, onSelect = { scope.launch { pager.animateScrollToPage(it) } })
                }
            })
        }
    }
}
