package jp.noppy.lumamusic.ui.audio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import jp.noppy.lumamusic.audio.DeviceReport
import jp.noppy.lumamusic.audio.OutputState
import jp.noppy.lumamusic.audio.RouteSelectionStatus
import jp.noppy.lumamusic.audio.encodingName
import jp.noppy.lumamusic.data.Track
import jp.noppy.lumamusic.ui.rateText

@Composable
internal fun AudioOutputPanel(
    output: OutputState,
    track: Track?,
    playing: Boolean,
    onSelect: (Int?) -> Unit,
    onClose: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val actual = output.actual.takeIf { playing }
    val routeStatus = when {
        output.preferredId == null -> null
        !playing -> "再生すると出力先を確認できます"
        output.routeStatus == RouteSelectionStatus.SWITCHING -> "切り替えを確認中"
        output.routeStatus == RouteSelectionStatus.MISMATCH -> "Androidが別の機器に出力しています"
        output.routeStatus == RouteSelectionStatus.WAITING -> "出力経路を確認できませんでした"
        else -> null
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("音声出力") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutputField("実際の出力先", actual?.description ?: "未確認")
                routeStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
                output.routeMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }

                HorizontalDivider()
                Text("出力先を選ぶ", style = MaterialTheme.typography.titleSmall)
                Column(Modifier.selectableGroup()) {
                    OutputChoice("自動", "Androidが出力先を選びます", output.preferredId == null) { onSelect(null) }
                    output.connected.filter { it.selectable }.forEach { device ->
                        OutputChoice(
                            device.name, device.type.takeUnless { device.speaker }, output.preferredId == device.id,
                        ) { onSelect(device.id) }
                    }
                }

                HorizontalDivider()
                Text("再生情報", style = MaterialTheme.typography.titleSmall)
                OutputField("ソース", track?.let {
                    val depth = it.bitDepth?.let { bits -> "$bits bit" } ?: "ビット深度未確認"
                    "${it.mime}\n${rateText(it.sampleRate)} / $depth"
                } ?: "未選択")
                OutputField("AudioTrack設定", output.track?.let {
                    "${rateText(it.rate)} / ${it.channels} ch / ${encodingName(it.encoding)}"
                } ?: "未確認")
                OutputField("実出力サンプルレート", "未確認")
                OutputField("再生方式", output.method)
                OutputField("ビットパーフェクト対応状況", output.bitPerfect)
                output.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
                if (output.connected.isNotEmpty()) {
                    HorizontalDivider()
                    Text("接続機器が報告する仕様", style = MaterialTheme.typography.titleSmall)
                    output.connected.forEach { device ->
                        val rates = device.rates.joinToString { rateText(it) }.ifEmpty { "未列挙" }
                        val channels = device.channels.joinToString().ifEmpty { "未列挙" }
                        val encodings = device.encodings.joinToString(transform = ::encodingName).ifEmpty { "未取得" }
                        OutputField(device.description, "サンプルレート $rates\nチャンネル数 $channels\nエンコーディング $encodings")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClose) { Text("閉じる") } },
    )
}

private val DeviceReport.description get() = if (speaker) name else "$name ・ $type"

@Composable
private fun OutputChoice(title: String, subtitle: String?, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected, role = Role.RadioButton, onClick = onSelect),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun OutputField(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
