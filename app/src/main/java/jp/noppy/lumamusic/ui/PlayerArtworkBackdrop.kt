package jp.noppy.lumamusic.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
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
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
    val diffusion: Bitmap?,
    val sourceSize: Size = Size.Zero,
    val headerSamples: List<Color> = emptyList(),
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
    val headerSamples = artworkHeaderSamples(pixels, width, height)
    // The texture preserves the coordinates of the source, so the color below
    // a feathered edge continues from the art at that exact point.
    val columns = 32
    val rows = 32
    val radiusX = (width * .16f).roundToInt()
    val radiusY = (height * .16f).roundToInt()
    val softPixels = IntArray(columns * rows) { index ->
        val x = (index % columns).toFloat() / (columns - 1)
        val y = (index / columns).toFloat() / (rows - 1)
        val centerX = (x * (width - 1)).roundToInt()
        val centerY = (y * (height - 1)).roundToInt()
        val sample = regionColor(
            pixels, width,
            (centerX - radiusX).coerceAtLeast(0), (centerY - radiusY).coerceAtLeast(0),
            (centerX + radiusX + 1).coerceAtMost(width), (centerY + radiusY + 1).coerceAtMost(height),
            dominant,
        )
        playerAmbientTone(sample, accent).toArgb()
    }
    val diffusion = Bitmap.createBitmap(softPixels, columns, rows, Bitmap.Config.ARGB_8888)
    val sourceSize = Size(width.toFloat(), height.toFloat())
    return ArtworkAmbient(palette, diffusion, sourceSize, headerSamples).also { artworkAmbientCache.put(artwork, it) }
}

// Local highlights can sit directly behind the white clock or menu. Ignore
// isolated bright pixels, but keep the near-white values in each header tile.
private fun artworkHeaderSamples(pixels: IntArray, width: Int, height: Int): List<Color> =
    (0 until 12).mapNotNull { tile ->
        val column = tile % 6
        val row = tile / 6
        val colors = buildList {
            for (y in height * row / 8 until (height * (row + 1) / 8).coerceAtLeast(1)) {
                for (x in width * column / 6 until width * (column + 1) / 6) {
                    val argb = pixels[y * width + x]
                    if (argb ushr 24 >= 192) add(Color(argb).copy(alpha = 1f))
                }
            }
        }.sortedBy(Color::luminance)
        colors.getOrNull(colors.size * 9 / 10)
    }

private fun playerArtworkBounds(width: Float, height: Float, controlsTop: Float, overlap: Float, wide: Boolean): Rect =
    if (wide) Rect(0f, 0f, width * .50f, height)
    else Rect(0f, 0f, width, (controlsTop + overlap).coerceIn(1f, height.coerceAtLeast(1f)))

private fun artworkFieldPaint(ambient: ArtworkAmbient, hero: Painter?, focus: Rect, wide: Boolean): Paint? {
    val bitmap = ambient.diffusion ?: return null
    if (focus.width <= 0f || focus.height <= 0f) return null
    val intrinsicSize = hero?.intrinsicSize
    val source = if (intrinsicSize != null && intrinsicSize != Size.Unspecified &&
        intrinsicSize.width > 0f && intrinsicSize.height > 0f
    ) {
        intrinsicSize
    } else {
        ambient.sourceSize
    }
    val scale = ContentScale.Crop.computeScaleFactor(source, focus.size)
    val width = source.width * scale.scaleX
    val height = source.height * scale.scaleY
    val left = focus.left + (focus.width - width) / 2f
    val top = focus.top + if (wide) (focus.height - height) / 2f else 0f
    val projection = Matrix().apply {
        setScale(width / bitmap.width, height / bitmap.height)
        postTranslate(left, top)
    }
    return Paint(Paint.FILTER_BITMAP_FLAG).apply {
        // Expand the image beyond its canvas by reflecting its low-frequency
        // colors. The edge stays continuous as the photo fades into this field.
        shader = BitmapShader(bitmap, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR).apply {
            setLocalMatrix(projection)
        }
    }
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
    val top = playerAmbientTone(upperEdge, accent)
    val bottom = playerAmbientTone(lowerEdge, accent)
    val statusShade = remember(scene.ambient, top) { playerHeaderShade(scene.ambient.headerSamples, top) }
    val heroAlpha = if (alternatePanel) .06f else 1f
    val overlap = 96.dp
    fun controlsTop(height: Float, bottomInset: Int): Float {
        val details = detailsHeight().takeIf { it > 0 }?.toFloat() ?: height * .42f
        return (height - bottomInset - details).coerceAtLeast(0f)
    }
    Box(modifier.fillMaxSize().background(miniSurface)) {
        Box(Modifier.fillMaxSize().drawWithCache {
            val field = Brush.verticalGradient(
                0f to top,
                .48f to playerAmbientTone(palette.dominant, accent),
                1f to bottom,
            )
            val focus = playerArtworkBounds(size.width, size.height,
                controlsTop(size.height, safeDrawing.getBottom(this)), overlap.toPx(), wide)
            val fieldPaint = artworkFieldPaint(scene.ambient, scene.hero, focus, wide)
            val falloff = Brush.verticalGradient(.74f to Color.Transparent, 1f to Color.Black.copy(alpha = .14f))
            onDrawBehind {
                val progress = sheet.progress
                if (progress > 0f) {
                    val ambientAlpha = progress * (2f - progress)
                    if (fieldPaint == null) {
                        drawRect(field, alpha = ambientAlpha)
                    } else {
                        fieldPaint.alpha = (ambientAlpha * 255f).roundToInt()
                        drawIntoCanvas { canvas ->
                            canvas.nativeCanvas.drawRect(0f, 0f, size.width, size.height, fieldPaint)
                        }
                    }
                    drawRect(falloff, alpha = ambientAlpha)
                    if (alternatePanel) drawRect(Color.Black, alpha = .15f * ambientAlpha)
                }
            }
        })
        Box(
            Modifier.morphBounds { width, height ->
                val start = sheet.miniArtworkInHost() ?: run {
                    val mini = sheet.miniInHost() ?: Rect(0f, height * .85f, width, height)
                    val edge = 42.dp.toPx()
                    Rect(Offset(mini.left + 10.dp.toPx(), mini.center.y - edge / 2), Size(edge, edge))
                }
                val end = playerArtworkBounds(width, height,
                    controlsTop(height, safeDrawing.getBottom(this)), overlap.toPx(), wide)
                interpolateBounds(start, end, sheet.progress)
            }.graphicsLayer {
                clip = true
                shape = RoundedCornerShape((10f * (1f - sheet.progress)).dp)
                compositingStrategy = CompositingStrategy.Offscreen
            }.drawWithCache {
                if (size.minDimension <= 0f) return@drawWithCache onDrawWithContent { drawContent() }
                val titleLine = (1f - overlap.toPx() / size.height).coerceIn(.22f, .94f)
                val fadeStart = (titleLine - 126.dp.toPx() / size.height).coerceIn(.03f, titleLine - .06f)
                val mask = if (wide) {
                    Brush.horizontalGradient(.68f to Color.Transparent, .86f to Color.Black.copy(alpha = .34f), 1f to Color.Black)
                } else {
                    Brush.verticalGradient(
                        fadeStart to Color.Transparent,
                        (fadeStart + (titleLine - fadeStart) * .48f) to Color.Black.copy(alpha = .24f),
                        titleLine to Color.Black.copy(alpha = .83f),
                        1f to Color.Black,
                    )
                }
                val titleShade = Brush.verticalGradient(
                    fadeStart to Color.Transparent,
                    titleLine to Color.Black.copy(alpha = .32f),
                    1f to Color.Black.copy(alpha = .32f),
                )
                val statusBottom = safeDrawing.getTop(this) + 12.dp.toPx()
                val headerShade = Brush.verticalGradient(
                    colors = listOf(statusShade, statusShade.copy(alpha = 0f)),
                    startY = statusBottom,
                    endY = statusBottom + 44.dp.toPx(),
                )
                val miniFill = Brush.linearGradient(listOf(top, bottom))
                onDrawWithContent {
                    val progress = sheet.progress
                    val plateOpacity = (1f - progress / .64f).coerceIn(0f, 1f)
                    if (plateOpacity > 0f) drawRect(miniFill, alpha = plateOpacity)
                    drawContent()
                    val feather = ((progress - .08f) / .92f).coerceIn(0f, 1f)
                    if (feather > 0f) {
                        if (scene.hero != null) {
                            if (statusShade.alpha > 0f) drawRect(headerShade, alpha = feather * heroAlpha)
                            if (!wide) drawRect(titleShade, alpha = feather * heroAlpha)
                        }
                        drawRect(mask, alpha = feather, blendMode = BlendMode.DstOut)
                    }
                }
            },
            contentAlignment = Alignment.Center,
        ) {
            val hero = scene.hero
            if (hero != null) {
                Image(
                    hero, title?.let { "アルバムアート: " + it }, Modifier.fillMaxSize(),
                    alignment = if (wide) Alignment.Center else Alignment.TopCenter,
                    contentScale = ContentScale.Crop,
                    alpha = heroAlpha,
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
