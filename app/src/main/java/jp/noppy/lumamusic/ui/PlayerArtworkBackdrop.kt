package jp.noppy.lumamusic.ui

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.ScaleFactor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.palette.graphics.Palette
import coil3.BitmapImage
import coil3.ImageLoader
import coil3.compose.asPainter
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

internal class PlayerArtworkScene(
    val hero: Painter?,
    val ambient: ArtworkAmbient,
) {
    companion object {
        val Default = PlayerArtworkScene(null, ArtworkAmbient.Default)
    }
}

internal class ArtworkAmbient(
    val palette: ArtworkAmbientPalette,
    val diffusion: ImageBitmap?,
) {
    companion object {
        val Default = ArtworkAmbient(ArtworkAmbientPalette.Default, null)
    }
}

// LibraryRepository's artwork URIs already include the source URI, size and modified time.
private val artworkAmbientCache = LruCache<String, ArtworkAmbient>(64)

@Composable
internal fun rememberPlayerArtwork(artwork: String?): PlayerArtworkScene? {
    val context = LocalContext.current
    var ready by remember(artwork) {
        mutableStateOf(if (artwork == null) PlayerArtworkScene.Default else null)
    }
    LaunchedEffect(context, artwork) {
        if (artwork == null) return@LaunchedEffect
        coroutineScope {
            // A slow decode briefly holds the complete outgoing scene. After this grace
            // period retire it until a complete replacement can be shown.
            val placeholder = launch {
                delay(220)
                ready = PlayerArtworkScene.Default
            }
            val loader = context.imageLoader
            val scene = withContext(Dispatchers.IO) {
                try {
                    coroutineScope {
                        val ambient = async { loadArtworkAmbient(context, loader, artwork) }
                        val request = ImageRequest.Builder(context).data(artwork).size(1080, 1080).build()
                        val foreground = loader.execute(request) as? SuccessResult
                        if (foreground == null) {
                            ambient.cancel()
                            PlayerArtworkScene.Default
                        } else {
                            PlayerArtworkScene(
                                foreground.image.asPainter(context),
                                ambient.await() ?: ArtworkAmbient.Default,
                            )
                        }
                    }
                } catch (_: OutOfMemoryError) {
                    PlayerArtworkScene.Default
                }
            }
            placeholder.cancel()
            ready = scene
        }
    }
    return ready
}

private suspend fun loadArtworkAmbient(context: Context, loader: ImageLoader, artwork: String): ArtworkAmbient? {
    artworkAmbientCache.get(artwork)?.let { return it }
    val request = ImageRequest.Builder(context)
        .data(artwork)
        .size(96, 96)
        .allowHardware(false)
        .memoryCachePolicy(CachePolicy.DISABLED)
        .build()
    val bitmap = ((loader.execute(request) as? SuccessResult)?.image as? BitmapImage)?.bitmap ?: return null
    val width = bitmap.width
    val height = bitmap.height
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    val mean = regionColor(pixels, width, 0, 0, width, height, ArtworkAmbientPalette.Default.dominant)
    // Apply no contrast filters: black, white and neutral covers are meaningful.
    val dominant = if (pixels.any { it ushr 24 != 255 }) mean else {
        val swatches = Palette.from(bitmap).clearFilters().maximumColorCount(10).generate()
        Color(swatches.getDominantColor(mean.toArgb()))
    }
    val palette = ArtworkAmbientPalette.fromPixels(pixels, width, height, dominant)
    val accent = palette.accent
    // Box-average a small grid once. Bilinear sampling of this grid gives a wide blur
    // across the entire canvas on API 29+, with no full resolution blur per frame.
    val columns = minOf(10, width)
    val rows = minOf(10, height)
    val softPixels = IntArray(columns * rows) { index ->
        val x = index % columns
        val y = index / columns
        val sample = regionColor(
            pixels, width, x * width / columns, y * height / rows,
            (x + 1) * width / columns, (y + 1) * height / rows, dominant,
        )
        val ceiling = .39f - .10f * (y.toFloat() / rows)
        playerAmbientTone(sample, accent, ceiling).toArgb()
    }
    val diffusion = Bitmap.createBitmap(softPixels, columns, rows, Bitmap.Config.ARGB_8888).asImageBitmap()
    return ArtworkAmbient(palette, diffusion).also { artworkAmbientCache.put(artwork, it) }
}

@Composable
internal fun PlayerArtworkBackdrop(
    sheet: PlayerSheetState,
    ready: PlayerArtworkScene?,
    title: String,
    miniSurface: Color,
    wide: Boolean,
    alternatePanel: Boolean,
    detailsHeight: () -> Int,
) {
    val reducedMotion = LocalReducedMotion.current
    val target by rememberUpdatedState(ready)
    var shown by remember { mutableStateOf(ready ?: PlayerArtworkScene.Default) }
    var incoming by remember { mutableStateOf<PlayerArtworkScene?>(null) }
    val fade = remember { Animatable(1f) }

    LaunchedEffect(reducedMotion) {
        try {
            // Keep one outgoing and one incoming scene. During rapid skips only the
            // latest completed load is eligible for the next transition.
            snapshotFlow { target }.conflate().collect { next ->
                if (next == null || next === shown || next !== target) return@collect
                if (!reducedMotion) {
                    fade.snapTo(0f)
                    incoming = next
                    fade.animateTo(1f, tween(260))
                }
                shown = next
                incoming = null
            }
        } finally {
            incoming?.let { shown = it }
            incoming = null
        }
    }

    val primary = if (reducedMotion) ready ?: shown else shown
    PlayerArtworkSceneLayer(
        sheet, primary, title.takeIf { primary === ready }, miniSurface, wide, alternatePanel, detailsHeight, Modifier,
    )
    if (!reducedMotion) incoming?.let { next ->
        PlayerArtworkSceneLayer(
            sheet, next, title.takeIf { next === ready }, miniSurface, wide, alternatePanel, detailsHeight,
            Modifier.graphicsLayer { alpha = fade.value },
        )
    }
}

@Composable
private fun PlayerArtworkSceneLayer(
    sheet: PlayerSheetState,
    scene: PlayerArtworkScene,
    title: String?,
    miniSurface: Color,
    wide: Boolean,
    alternatePanel: Boolean,
    detailsHeight: () -> Int,
    modifier: Modifier,
) {
    val safeDrawing = WindowInsets.safeDrawing
    val palette = scene.ambient.palette
    val accent = remember(palette) { palette.accent }
    val upperEdge = lerp(palette.topLeft, palette.topRight, .5f)
    val lowerEdge = lerp(palette.bottomLeft, palette.bottomRight, .5f)
    val top = playerAmbientTone(upperEdge, accent, .39f)
    val bottom = playerAmbientTone(lowerEdge, accent, .31f)
    val heroScale = remember(sheet) {
        object : ContentScale {
            override fun computeScaleFactor(srcSize: Size, dstSize: Size): ScaleFactor {
                val crop = ContentScale.Crop.computeScaleFactor(srcSize, dstSize).scaleX
                val fit = ContentScale.Fit.computeScaleFactor(srcSize, dstSize).scaleX
                val scale = androidx.compose.ui.util.lerp(crop, minOf(crop, fit * 1.08f), sheet.progress)
                return ScaleFactor(scale, scale)
            }
        }
    }
    Box(modifier.fillMaxSize().background(miniSurface)) {
        Box(Modifier.fillMaxSize().drawWithCache {
            val field = Brush.verticalGradient(
                0f to top,
                .40f to playerAmbientTone(palette.dominant, accent, .39f),
                .73f to bottom,
                1f to playerAmbientTone(lowerEdge, accent, .23f),
            )
            val diffusionSize = IntSize(size.width.roundToInt(), size.height.roundToInt())
            val radius = (size.maxDimension * .62f).coerceAtLeast(1f)
            val cornerHues = listOf(
                playerAmbientTone(palette.topLeft, accent, .39f) to Offset(0f, 0f),
                playerAmbientTone(palette.topRight, accent, .39f) to Offset(size.width, 0f),
                playerAmbientTone(palette.bottomLeft, accent, .33f) to Offset(0f, size.height * .76f),
                playerAmbientTone(palette.bottomRight, accent, .33f) to Offset(size.width, size.height * .76f),
            )
            val accents = cornerHues.map { (color, center) ->
                Brush.radialGradient(listOf(color.copy(alpha = .60f), color.copy(alpha = 0f)), center, radius)
            }
            onDrawBehind {
                val progress = sheet.progress
                if (progress > 0f) {
                    drawRect(field, alpha = progress)
                    scene.ambient.diffusion?.let {
                        drawImage(it, dstSize = diffusionSize, alpha = .38f * progress, filterQuality = FilterQuality.Low)
                    }
                    accents.forEach { drawRect(it, alpha = progress) }
                }
            }
        })
        Box(
            Modifier.morphBounds { width, height ->
                val mini = sheet.miniInHost() ?: Rect(0f, height * .85f, width, height)
                val edge = 42.dp.toPx()
                val start = Offset(mini.left + 10.dp.toPx(), mini.center.y - edge / 2)
                val end = if (wide) {
                    Rect(0f, 0f, width * .50f, height)
                } else {
                    // Keep the original pixels between the two control areas. The
                    // header and detail rows sit entirely on the graded field.
                    val imageTop = safeDrawing.getTop(this) + 48.dp.toPx()
                    val detailsTop = height - safeDrawing.getBottom(this) - detailsHeight()
                    val imageBottom = minOf(height * .72f, imageTop + width * 1.08f, detailsTop)
                    Rect(0f, imageTop, width, maxOf(imageTop, imageBottom))
                }
                interpolateBounds(Rect(start, Size(edge, edge)), end, sheet.progress)
            }.graphicsLayer {
                clip = true
                shape = RoundedCornerShape((10f * (1f - sheet.progress)).dp)
                compositingStrategy = CompositingStrategy.Offscreen
            }.drawWithCache {
                if (size.minDimension <= 0f) return@drawWithCache onDrawWithContent { drawContent() }
                val intrinsic = scene.hero?.intrinsicSize ?: Size.Unspecified
                val source = intrinsic.takeIf { it != Size.Unspecified && it.width > 0f && it.height > 0f } ?: size
                val scale = heroScale.computeScaleFactor(source, size).scaleX
                val drawnHeight = minOf(source.height * scale, size.height)
                val topStop = ((size.height - drawnHeight) / (2f * size.height)).coerceIn(0f, .5f)
                val span = (drawnHeight / size.height).coerceIn(.01f, 1f - topStop)
                val clear = Color.Transparent
                val opaque = Color.Black
                val mask = if (wide) {
                    val drawnWidth = minOf(source.width * scale, size.width)
                    val start = ((size.width - drawnWidth) / (2f * size.width)).coerceIn(0f, .5f)
                    val widthFraction = (drawnWidth / size.width).coerceIn(.01f, 1f - start)
                    Brush.horizontalGradient(
                        (start + .72f * widthFraction) to clear,
                        (start + widthFraction) to opaque,
                    )
                } else {
                    Brush.verticalGradient(
                        topStop to opaque,
                        (topStop + .16f * span) to clear,
                        (topStop + .78f * span) to clear,
                        (topStop + span) to opaque,
                    )
                }
                val miniFill = Brush.linearGradient(listOf(top, bottom))
                onDrawWithContent {
                    val progress = sheet.progress
                    val plateOpacity = (1f - progress / .64f).coerceIn(0f, 1f)
                    if (plateOpacity > 0f) drawRect(miniFill, alpha = plateOpacity)
                    drawContent()
                    val feather = ((progress - .08f) / .92f).coerceIn(0f, 1f)
                    if (feather > 0f) drawRect(mask, alpha = feather, blendMode = BlendMode.DstOut)
                }
            },
            contentAlignment = Alignment.Center,
        ) {
            val hero = scene.hero
            if (hero != null) {
                Image(
                    hero, title?.let { "アルバムアート: " + it }, Modifier.fillMaxSize(),
                    contentScale = heroScale, alpha = if (alternatePanel) .12f else 1f,
                )
            } else {
                Icon(
                    Icons.Rounded.MusicNote, null, Modifier.fillMaxSize(.27f),
                    tint = Color.White.copy(alpha = if (alternatePanel) .12f else .45f),
                )
            }
        }
    }
}
