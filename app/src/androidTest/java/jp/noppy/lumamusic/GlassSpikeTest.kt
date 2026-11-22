package jp.noppy.lumamusic

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import jp.noppy.lumamusic.ui.GlassEffects
import jp.noppy.lumamusic.ui.GlassQuality
import jp.noppy.lumamusic.ui.LiquidBackdropHost
import jp.noppy.lumamusic.ui.LocalGlassEffects
import jp.noppy.lumamusic.ui.LocalGlassQuality
import jp.noppy.lumamusic.ui.LocalGlassSampleObserver
import jp.noppy.lumamusic.ui.LumaTheme
import jp.noppy.lumamusic.ui.liquidSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class GlassSpikeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation = instrumentation.uiAutomation
    private val context = instrumentation.targetContext

    @Test fun refractionMovesLiveCheckerEdgesWithoutPaintingOutsideLens() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        val fixture = GlassFixture()
        val activity = launchFixture(fixture)
        try {
            val lens = probeBounds()
            val edge = Rect(lens.left + lens.width() / 5, lens.top + 2, lens.right - lens.width() / 5, lens.top + lens.height() / 3)
            val outside = Rect(edge.left, lens.bottom + 4, edge.right, lens.bottom + edge.height())
            val source = capture("v056-source-only")
            try {
                select(fixture, Preset.REFRACTION)
                val refracted = capture("v056-refraction-only")
                try {
                    assertTrue("Refraction must relocate live checker edges inside the lens", changedRatio(source, refracted, edge) >= .03)
                    assertTrue("The lens must not paint outside its bounds", changedRatio(source, refracted, outside) < .01)
                    assertTrue("The real surface must report samples within its source", fixture.validSamples.get() > 0)
                    instrumentation.runOnMainSync { fixture.quality = GlassQuality.REDUCED }
                    val fallback = capture("v056-refraction-lightweight")
                    try {
                        assertTrue("The lightweight profile must disable displacement", changedRatio(source, fallback, edge) < .01)
                    } finally {
                        fallback.recycle()
                    }
                    instrumentation.runOnMainSync { fixture.quality = GlassQuality.BALANCED }
                    scrollChecker(refracted)
                    val scrolled = capture("v056-refraction-after-scroll")
                    try {
                        assertTrue("Scrolling must update the live source in the lens", changedRatio(refracted, scrolled, edge) >= .03)
                    } finally {
                        scrolled.recycle()
                    }
                } finally {
                    refracted.recycle()
                }
            } finally {
                source.recycle()
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test fun fullGlassPreservesShapeContrastFromDecodedArtwork() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        val artwork = decodeArtwork()
        val fixture = GlassFixture(artwork.asImageBitmap())
        val activity = launchFixture(fixture)
        try {
            assertEquals(Bitmap.Config.HARDWARE, artwork.config)
            val lens = probeBounds()
            val source = capture("v056-artwork-source-only")
            try {
                for (preset in Preset.entries.filter { it != Preset.SOURCE }) {
                    select(fixture, preset)
                    val frame = capture("v056-artwork-${preset.fileName}")
                    try {
                        if (preset == Preset.FULL) {
                            val rawSeparation = artworkSeparation(source, lens)
                            val glassSeparation = artworkSeparation(frame, lens)
                            assertTrue("The decoded fixture must show its dark and light image patches", rawSeparation >= 140)
                            assertTrue(
                                "Full glass must retain local contrast inside the lens ($glassSeparation / $rawSeparation)",
                                glassSeparation >= rawSeparation * .35,
                            )
                            assertTrue(
                                "Full glass must adapt the light-theme surface instead of copying source pixels",
                                glassSeparation < rawSeparation,
                            )
                        }
                    } finally {
                        frame.recycle()
                    }
                }
            } finally {
                source.recycle()
            }
            instrumentation.runOnMainSync { fixture.dark = true }
            capture("v056-artwork-full-glass-dark").recycle()
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun launchFixture(fixture: GlassFixture): AurioActivity {
        val intent = Intent(context, AurioActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return (instrumentation.startActivitySync(intent) as AurioActivity).also { activity ->
            instrumentation.runOnMainSync { activity.setContent { fixture.Content() } }
        }
    }

    private fun probeBounds(): Rect {
        repeat(40) {
            if (Build.VERSION.SDK_INT >= 36) automation.clearCache()
            val pending = ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
            automation.rootInActiveWindow?.let(pending::add)
            while (pending.isNotEmpty()) {
                val node = pending.removeFirst()
                if (node.contentDescription?.toString() == "Optical probe") return Rect().also(node::getBoundsInScreen)
                repeat(node.childCount) { index -> node.getChild(index)?.let(pending::add) }
            }
            Thread.sleep(100)
        }
        error("Missing optical probe")
    }

    private fun select(fixture: GlassFixture, preset: Preset) {
        instrumentation.runOnMainSync { fixture.preset = preset }
    }

    private fun scrollChecker(image: Bitmap) {
        val x = image.width / 8
        val y = image.height * 2 / 3
        val travel = maxOf(31, image.height / 17)
        ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand("input swipe $x $y $x ${y - travel} 520"),
        ).use { it.readBytes() }
    }

    private fun capture(name: String): Bitmap {
        instrumentation.waitForIdleSync()
        Thread.sleep(430)
        val bitmap = checkNotNull(automation.takeScreenshot()) { "Missing screenshot $name" }
        File(context.getExternalFilesDir(null), "$name.png").outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        return bitmap
    }

    private fun artworkSeparation(image: Bitmap, lens: Rect): Int {
        val y = lens.centerY() + lens.height() / 5
        val dark = image.getPixel(lens.left + lens.width() / 4, y)
        val light = image.getPixel(lens.right - lens.width() / 4, y)
        return (Color.red(light) + Color.green(light) + Color.blue(light) -
            Color.red(dark) - Color.green(dark) - Color.blue(dark)) / 3
    }

    private fun changedRatio(before: Bitmap, after: Bitmap, area: Rect): Double {
        var changed = 0
        for (y in area.top until area.bottom) {
            for (x in area.left until area.right) {
                val a = before.getPixel(x, y)
                val b = after.getPixel(x, y)
                if (abs(Color.red(a) - Color.red(b)) + abs(Color.blue(a) - Color.blue(b)) >= 80) changed++
            }
        }
        return changed.toDouble() / (area.width() * area.height())
    }

    private fun decodeArtwork(): Bitmap {
        val generated = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(generated)
        val paint = Paint()
        canvas.drawColor(Color.rgb(32, 32, 40))
        paint.color = Color.rgb(229, 229, 235)
        canvas.drawRect(200f, 0f, 400f, 400f, paint)
        paint.color = Color.rgb(222, 122, 63)
        canvas.drawRect(0f, 12f, 400f, 45f, paint)
        paint.color = Color.argb(36, 35, 123, 210)
        for (top in 60 until 400 step 12) {
            canvas.drawRect(0f, top.toFloat(), 400f, top + 3f, paint)
        }
        val png = ByteArrayOutputStream().use { bytes ->
            check(generated.compress(Bitmap.CompressFormat.PNG, 100, bytes))
            bytes.toByteArray()
        }
        generated.recycle()
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(png))) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_HARDWARE
        }
    }
}

private val RawGlass = GlassEffects(blur = false, refraction = false, highlight = false, tint = false, shadow = false)

private enum class Preset(val label: String, val fileName: String, val effects: GlassEffects) {
    SOURCE("Source only", "source-only", RawGlass),
    BLUR("Blur only", "blur-only", RawGlass.copy(blur = true)),
    REFRACTION("Refraction only", "refraction-only", RawGlass.copy(refraction = true)),
    HIGHLIGHT("Highlight only", "highlight-only", RawGlass.copy(highlight = true)),
    FULL("Full glass", "full-glass", GlassEffects()),
}

private class GlassFixture(private val artwork: ImageBitmap? = null) {
    var preset by mutableStateOf(Preset.SOURCE)
    var dark by mutableStateOf(false)
    var quality by mutableStateOf(GlassQuality.BALANCED)
    val validSamples = AtomicInteger()

    @Composable fun Content() {
        LumaTheme(if (dark) "dark" else "light") {
            CompositionLocalProvider(
                LocalGlassQuality provides quality,
                LocalGlassEffects provides preset.effects,
                LocalGlassSampleObserver provides { sample ->
                    if (sample.selectionLens && sample.sourceRevision > 0 &&
                        sample.sourceBounds.contains(sample.sampleBounds.center)) validSamples.incrementAndGet()
                },
            ) {
                LiquidBackdropHost(Modifier.fillMaxSize(), content = {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        if (artwork != null) {
                            Image(artwork, null, Modifier.fillMaxWidth().height(1800.dp), contentScale = ContentScale.FillBounds)
                        } else {
                            ComposeCanvas(Modifier.fillMaxWidth().height(1800.dp)) {
                                val tile = 15.dp.toPx()
                                val rows = (size.height / tile).toInt() + 1
                                val columns = (size.width / tile).toInt() + 1
                                drawRect(ComposeColor(0xFF2768CF))
                                for (row in 0 until rows) {
                                    for (column in row % 2 until columns step 2) {
                                        drawRect(ComposeColor(0xFFDD3C35), Offset(column * tile, row * tile), Size(tile, tile))
                                    }
                                }
                            }
                        }
                    }
                }, overlay = {
                    Text(preset.label, Modifier.align(Alignment.TopCenter).padding(top = 42.dp)
                        .background(MaterialTheme.colorScheme.surface).padding(8.dp), color = MaterialTheme.colorScheme.onSurface)
                    Box(
                        Modifier.align(Alignment.Center).padding(horizontal = 24.dp)
                            .widthIn(max = 280.dp).fillMaxWidth().height(68.dp)
                            .semantics { contentDescription = "Optical probe" }
                            .liquidSurface(selectionLens = true),
                    )
                })
            }
        }
    }
}
