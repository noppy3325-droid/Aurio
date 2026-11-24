package jp.noppy.lumamusic

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import jp.noppy.lumamusic.ui.LumaTheme
import jp.noppy.lumamusic.ui.LocalReducedMotion
import jp.noppy.lumamusic.ui.MarqueeText
import jp.noppy.lumamusic.ui.TrackText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class TrackTextDeviceTest {
    @Test fun playbackMarqueeMovesLongTitleInReducedMotionButKeepsShortTitleStill() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, AurioActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        ) as AurioActivity
        val short = AtomicReference<Rect>()
        val long = AtomicReference<Rect>()
        val layout = AtomicReference<TextLayoutResult>()
        val positioned = CountDownLatch(2)
        val full = "長い曲名 🎵 A long title e\u0301 " + "最後まで読めるタイトル ".repeat(14) + "FIN"
        fun Modifier.saveBounds(destination: AtomicReference<Rect>) = onGloballyPositioned {
            val area = it.boundsInWindow()
            val rect = Rect(area.left.toInt(), area.top.toInt(), area.right.toInt(), area.bottom.toInt())
            if (!rect.isEmpty && destination.getAndSet(rect) == null) positioned.countDown()
        }
        fun ink(bitmap: Bitmap, area: Rect): List<Int> {
            require(area.width() > 0 && area.height() > 0)
            val pixels = IntArray(area.width() * area.height())
            bitmap.getPixels(pixels, 0, area.width(), area.left, area.top, area.width(), area.height())
            return pixels.indices.filter { index ->
                val color = pixels[index]
                android.graphics.Color.red(color) < 128 && android.graphics.Color.green(color) < 128 &&
                    android.graphics.Color.blue(color) < 128
            }
        }
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    val currentDensity = LocalDensity.current.density
                    CompositionLocalProvider(
                        LocalDensity provides Density(currentDensity, fontScale = 1.6f),
                        LocalReducedMotion provides true,
                    ) {
                        LumaTheme("light") {
                            val blackTitle = MaterialTheme.typography.titleMedium.copy(color = Color.Black)
                            Box(Modifier.fillMaxSize().background(Color.White).safeDrawingPadding()) {
                                Column(Modifier.width(170.dp)) {
                                    Box(Modifier.saveBounds(short)) { MarqueeText("短い曲名", style = blackTitle) }
                                    Box(Modifier.saveBounds(long)) { MarqueeText(full, style = blackTitle, onTextLayout = layout::set) }
                                }
                            }
                        }
                    }
                }
            }
            assertTrue("Both titles have bounds", positioned.await(10, TimeUnit.SECONDS))
            val clip = checkNotNull(long.get())
            val still = checkNotNull(short.get())
            fun getInk(): Pair<List<Int>, List<Int>> {
                val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                return try { ink(image, still) to ink(image, clip) } finally { image.recycle() }
            }
            Thread.sleep(500)
            val (shortPixels, longPixels) = getInk()
            assertTrue("Both titles have been drawn", shortPixels.isNotEmpty() && longPixels.isNotEmpty())
            var moved = false
            val deadline = SystemClock.elapsedRealtime() + 4_000
            while (!moved && SystemClock.elapsedRealtime() < deadline) {
                Thread.sleep(200)
                val (nextShort, nextLong) = getInk()
                assertEquals("Short title keeps the same position", shortPixels, nextShort)
                moved = longPixels != nextLong
            }
            assertTrue("Long title scrolls while reduced motion is active", moved)
            val textLayout = checkNotNull(layout.get())
            assertEquals(full, textLayout.layoutInput.text.text)
            assertEquals(1, textLayout.lineCount)
            assertTrue("The full title reaches its final character", textLayout.getLineEnd(0) == full.length)
            assertTrue("A marquee title does not ellipsize", !textLayout.isLineEllipsized(0))
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test fun longMixedTitleEllipsizesAtLargerScaleAndKeepsFullAccessibilityText() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, AurioActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        ) as AurioActivity
        val title = "あなたの夜が明けるまで (feat. ＩＡ) / A long mixed-language title"
        val result = AtomicReference<TextLayoutResult>()
        val laidOut = CountDownLatch(1)
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    val deviceDensity = LocalDensity.current.density
                    CompositionLocalProvider(LocalDensity provides Density(deviceDensity, fontScale = 1.6f)) {
                        LumaTheme("light") {
                            Column(Modifier.width(170.dp)) {
                                TrackText(title, onTextLayout = { layout ->
                                    result.set(layout)
                                    laidOut.countDown()
                                })
                            }
                        }
                    }
                }
            }
            assertTrue("Compose text was laid out", laidOut.await(5, TimeUnit.SECONDS))
            val layout = result.get()
            assertEquals(1, layout.lineCount)
            assertTrue("The line has an ellipsis", layout.isLineEllipsized(0))

            val automation = instrumentation.uiAutomation
            val until = SystemClock.elapsedRealtime() + 5000
            var semantics: String? = null
            while (semantics == null && SystemClock.elapsedRealtime() < until) {
                if (Build.VERSION.SDK_INT >= 36) automation.clearCache()
                val nodes = ArrayDeque<AccessibilityNodeInfo>()
                automation.rootInActiveWindow?.let(nodes::add)
                while (nodes.isNotEmpty()) {
                    val node = nodes.removeFirst()
                    val text = node.text?.toString()
                    if (text == title) {
                        semantics = text
                        break
                    }
                    repeat(node.childCount) { node.getChild(it)?.let(nodes::add) }
                }
                if (semantics == null) Thread.sleep(100)
            }
            assertNotNull("Full title is available to accessibility", semantics)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
