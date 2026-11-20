package jp.noppy.lumamusic.ui

import android.app.UiModeManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.isSpecified
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
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow

enum class GlassQuality(val label: String) { HIGH("高品質"), BALANCED("バランス"), REDUCED("軽量"), OFF("オフ") }

internal data class GlassEffects(
    val blur: Boolean = true,
    val refraction: Boolean = true,
    val highlight: Boolean = true,
    val tint: Boolean = true,
    val shadow: Boolean = true,
)

internal val LocalGlassEffects = staticCompositionLocalOf { GlassEffects() }
val LocalLiquidBackdrop = staticCompositionLocalOf<Backdrop?> { null }
val LocalGlassQuality = staticCompositionLocalOf { GlassQuality.BALANCED }
val LocalReducedMotion = staticCompositionLocalOf { false }

@Composable fun effectiveGlassQuality(saved: String, reducedMotion: Boolean): GlassQuality {
    val context = LocalContext.current
    val power = remember(context) { context.getSystemService(PowerManager::class.java) }
    var batterySaver by remember(power) { mutableStateOf(power.isPowerSaveMode) }
    val uiMode = remember(context) { context.getSystemService(UiModeManager::class.java) }
    var highContrast by remember(uiMode) {
        mutableStateOf(Build.VERSION.SDK_INT >= 34 && uiMode.contrast >= .5f)
    }
    DisposableEffect(context, power, uiMode) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { batterySaver = power.isPowerSaveMode }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        val contrastListener = if (Build.VERSION.SDK_INT >= 34) {
            UiModeManager.ContrastChangeListener { contrast -> highContrast = contrast >= .5f }
                .also { uiMode.addContrastChangeListener(ContextCompat.getMainExecutor(context), it) }
        } else null
        onDispose {
            context.unregisterReceiver(receiver)
            if (Build.VERSION.SDK_INT >= 34 && contrastListener != null) uiMode.removeContrastChangeListener(contrastListener)
        }
    }
    val requested = remember(saved) { GlassQuality.entries.find { it.name == saved } ?: GlassQuality.BALANCED }
    return when {
        highContrast || Build.VERSION.SDK_INT < 31 -> GlassQuality.OFF
        (batterySaver || reducedMotion || Build.VERSION.SDK_INT < 33) && requested != GlassQuality.OFF -> GlassQuality.REDUCED
        else -> requested
    }
}

/** Capture the live page once. The dock and the lifted lens independently read that page. */
@Composable fun LiquidBackdropHost(
    modifier: Modifier = Modifier,
    capture: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
    overlay: @Composable BoxScope.() -> Unit,
) {
    val source = if (capture && LocalGlassQuality.current != GlassQuality.OFF && Build.VERSION.SDK_INT >= 31) {
        rememberLayerBackdrop()
    } else null
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
    val effects = LocalGlassEffects.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val shape = if (selectionLens) CircleShape else RoundedCornerShape(radius.dp)
    val neutral = if (dark) Color(0xFF24262B) else Color(0xFFF9FAFB)
    val selected = if (dark) Color(0xFF373A41) else Color(0xFFE6E9EF)
    if (source == null || quality == GlassQuality.OFF || Build.VERSION.SDK_INT < 31) {
        return clip(shape).background(if (selectionLens) selected else neutral)
    }
    val optical = quality != GlassQuality.REDUCED && Build.VERSION.SDK_INT >= 33
    val softness = when {
        selectionLens && optical -> 1.2f
        optical && quality == GlassQuality.HIGH -> 3f
        optical -> 2.2f
        else -> 3.5f
    }
    return drawBackdrop(
        backdrop = source,
        shape = { shape },
        highlight = if (!optical && effects.highlight) ({ highlight.copy(alpha = .44f) }) else null,
        shadow = if (effects.shadow) ({
            shadow.copy(alpha = (if (selectionLens) .25f else .52f) + .35f * lensLift().coerceIn(0f, 1f))
        }) else null,
        effects = {
            if (size.isSpecified && size.minDimension > 0f) {
                if (effects.blur) blur(softness.dp.toPx())
                if (optical && Build.VERSION.SDK_INT >= 33) {
                    val corner = if (selectionLens) size.minDimension / 2f else radius.dp.toPx().coerceAtMost(size.minDimension / 2f)
                    glassOptics(corner, dark, quality == GlassQuality.HIGH, selectionLens, lensLift().coerceIn(0f, 1f), effects)
                } else if (effects.tint) {
                    colorControls(brightness = if (dark) -.22f else .24f, contrast = .65f)
                }
            }
        },
        onDrawSurface = {
            if (!optical && effects.tint) {
                drawRect((if (selectionLens) selected else neutral).copy(alpha = if (dark) .38f else .30f))
            }
        },
    )
}
