package jp.noppy.lumamusic.ui

import android.view.Window
import android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
internal fun StandbyWindow(keepAwake: Boolean, brightness: Float?) {
    val window = LocalActivity.current?.window ?: return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val requestedKeepAwake by rememberUpdatedState(keepAwake)
    val requestedBrightness by rememberUpdatedState(brightness)
    val lease = remember(window) { StandbyWindowLease(window) }

    DisposableEffect(lifecycle, lease) {
        // The resume/pause callback restores the window even if no more Compose frames are drawn.
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) lease.apply(requestedKeepAwake, requestedBrightness)
            if (event == Lifecycle.Event.ON_PAUSE) lease.restore()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            lease.restore()
        }
    }
    SideEffect {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) lease.apply(keepAwake, brightness)
    }
}

private class StandbyWindowLease(private val window: Window) {
    private var savedBrightness: Float? = null
    private var addedKeepAwake = false

    fun apply(keepAwake: Boolean, brightness: Float?) {
        if (keepAwake && (window.attributes.flags and FLAG_KEEP_SCREEN_ON) == 0) {
            window.addFlags(FLAG_KEEP_SCREEN_ON)
            addedKeepAwake = true
        } else if (!keepAwake && addedKeepAwake) {
            window.clearFlags(FLAG_KEEP_SCREEN_ON)
            addedKeepAwake = false
        }
        if (brightness != null) {
            if (savedBrightness == null) savedBrightness = window.attributes.screenBrightness
            setBrightness(brightness)
        } else {
            savedBrightness?.let(::setBrightness)
            savedBrightness = null
        }
    }

    fun restore() = apply(keepAwake = false, brightness = null)

    private fun setBrightness(brightness: Float) {
        val attributes = window.attributes
        if (attributes.screenBrightness != brightness) {
            attributes.screenBrightness = brightness
            window.attributes = attributes
        }
    }
}
