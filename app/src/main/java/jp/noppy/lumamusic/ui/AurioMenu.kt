package jp.noppy.lumamusic.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

@Composable
internal fun AurioMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < .5f
    val glass = LocalGlassQuality.current != GlassQuality.OFF
    val tint = lerp(scheme.surface, scheme.surfaceVariant, .26f)
    val edge = if (dark) Color.White.copy(alpha = .18f) else Color.Black.copy(alpha = .11f)
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        offset = DpOffset(0.dp, 4.dp),
        shape = RoundedCornerShape(20.dp),
        containerColor = if (glass) tint.copy(alpha = .96f) else scheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 12.dp,
        border = BorderStroke(1.dp, edge),
        content = content,
    )
}
