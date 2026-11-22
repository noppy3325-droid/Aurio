package jp.noppy.lumamusic.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import jp.noppy.lumamusic.LauncherIcon
import jp.noppy.lumamusic.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun LauncherIconPicker() {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var selected by remember(context) { mutableStateOf(LauncherIcon.selected(context)) }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme

    Text(stringResource(R.string.icon_settings), style = MaterialTheme.typography.titleMedium)
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LauncherIcon.entries.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { icon ->
                    val active = icon == selected
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp),
                        color = if (active) colors.secondaryContainer else colors.surfaceVariant,
                        contentColor = if (active) colors.onSecondaryContainer else colors.onSurfaceVariant,
                        border = if (active) BorderStroke(1.dp, colors.primary) else null,
                    ) {
                        Column(
                            Modifier.fillMaxWidth().selectable(active, enabled = !saving, role = Role.RadioButton) {
                                if (active) return@selectable
                                saving = true
                                failed = false
                                scope.launch {
                                    try {
                                        withContext(Dispatchers.IO) { icon.apply(context) }
                                        selected = icon
                                    } catch (error: SecurityException) {
                                        failed = true
                                    } catch (error: IllegalArgumentException) {
                                        failed = true
                                    } finally {
                                        saving = false
                                    }
                                }
                            }.padding(vertical = 9.dp, horizontal = 5.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Image(painterResource(icon.preview), contentDescription = null, Modifier.size(56.dp))
                            Text(stringResource(icon.label), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
    if (failed) {
        Text(stringResource(R.string.icon_change_failed), color = colors.error, style = MaterialTheme.typography.bodySmall)
    }
}
