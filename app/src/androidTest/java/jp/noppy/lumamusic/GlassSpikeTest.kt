package jp.noppy.lumamusic

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import jp.noppy.lumamusic.ui.GlassDemoScreen
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class GlassSpikeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation = instrumentation.uiAutomation
    private val context = instrumentation.targetContext

    @Test fun refractionMovesLiveCheckerEdgesWithoutPaintingOutsideLens() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        val activity = launchDemo()
        try {
            click("赤青チェック")
            listOf("Blur", "Refraction", "Highlight", "Tint", "Shadow", "Motion").forEach(::click)
            val probe = Rect().also { bounds ->
                find { it.contentDescription?.toString() == "屈折の検証面" }.getBoundsInScreen(bounds)
            }
            val nearEdge = Rect(
                probe.left + probe.width() / 5, probe.top + 2,
                probe.right - probe.width() / 5, probe.top + probe.height() / 3,
            )
            val outside = Rect(nearEdge.left, probe.bottom + 4, nearEdge.right, probe.bottom + nearEdge.height())
            capture("v055-checker-neutral") { neutral ->
                click("Refraction")
                capture("v055-checker-refraction-only") { refracted ->
                    val changed = changedRatio(neutral, refracted, nearEdge)
                    assertTrue("Refraction must relocate the colored checker edges ($changed)", changed >= .03)
                    assertTrue("Refraction must stay inside the optical surface", changedRatio(neutral, refracted, outside) < .01)
                    scrollBackground()
                    capture("v055-refraction-after-scroll") { scrolled ->
                        assertTrue("The live source must move behind the lens", changedRatio(refracted, scrolled, nearEdge) >= .03)
                    }
                }
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test fun capturesIsolatedEffectsAndQualityFallbacks() {
        val activity = launchDemo()
        try {
            capture("v055-light-rest") { }
            scrollBackground()
            capture("v055-light-scroll") { }
            click("ダーク")
            capture("v055-dark-rest") { }
            click("赤青チェック")
            val effects = listOf("Blur", "Refraction", "Highlight", "Tint", "Shadow", "Motion")
            effects.forEach(::click)
            for (effect in effects) {
                click(effect)
                capture("v055-${effect.lowercase()}-only") { }
                click(effect)
            }
            effects.forEach(::click)
            for (quality in listOf("高品質", "軽量", "オフ", "バランス")) {
                click(quality)
                find { it.text?.toString()?.contains("· $quality") == true }
                capture("v055-quality-$quality") { }
            }
            val original = activity.requestedOrientation
            try {
                instrumentation.runOnMainSync { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                capture("v055-landscape") { }
            } finally {
                instrumentation.runOnMainSync { activity.requestedOrientation = original }
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun launchDemo(): MainActivity {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val activity = instrumentation.startActivitySync(intent) as MainActivity
        // Use the real renderer even when verifying the signed release package.
        instrumentation.runOnMainSync { activity.setContent { GlassDemoScreen() } }
        return activity
    }

    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        repeat(40) {
            if (Build.VERSION.SDK_INT >= 36) automation.clearCache()
            val pending = ArrayDeque<AccessibilityNodeInfo>()
            automation.rootInActiveWindow?.let(pending::add)
            while (pending.isNotEmpty()) {
                val node = pending.removeFirst()
                node.refresh()
                if (predicate(node)) return node
                repeat(node.childCount) { index -> node.getChild(index)?.let(pending::add) }
            }
            Thread.sleep(100)
        }
        error("Missing glass diagnostics control")
    }

    private fun click(label: String) {
        val labelNode = find { it.text?.toString() == label }
        val control = generateSequence(labelNode) { it.parent }.first { it.isClickable }
        assertTrue("Cannot activate $label", control.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }

    private fun scrollBackground() {
        val area = Rect().also { bounds ->
            find { it.contentDescription?.toString() == "検証用の背景" }.getBoundsInScreen(bounds)
        }
        val x = area.left + area.width() / 8
        val y = area.top + area.height() * 2 / 3
        // A short, slow gesture advances by less than a screen and avoids a fling.
        val travel = maxOf(31, area.height() / 17)
        ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand("input swipe $x $y $x ${y - travel} 520"),
        ).use { it.readBytes() }
    }

    private fun capture(name: String, verify: (Bitmap) -> Unit) {
        instrumentation.waitForIdleSync()
        Thread.sleep(430)
        val bitmap = checkNotNull(automation.takeScreenshot()) { "Missing screenshot $name" }
        try {
            File(context.getExternalFilesDir(null), "$name.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            verify(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private fun changedRatio(before: Bitmap, after: Bitmap, area: Rect): Double {
        var changed = 0
        for (y in area.top until area.bottom) {
            for (x in area.left until area.right) {
                val a = before.getPixel(x, y)
                val b = after.getPixel(x, y)
                val delta = abs(Color.red(a) - Color.red(b)) + abs(Color.blue(a) - Color.blue(b))
                if (delta >= 80) changed++
            }
        }
        return changed.toDouble() / (area.width() * area.height())
    }
}
