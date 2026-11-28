package jp.noppy.lumamusic.ui.audio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import jp.noppy.lumamusic.LumaApp
import jp.noppy.lumamusic.R
import jp.noppy.lumamusic.audio.*
import jp.noppy.lumamusic.core.dsp.*
import jp.noppy.lumamusic.data.applyTo
import jp.noppy.lumamusic.data.toDspPreset
import jp.noppy.lumamusic.ui.LumaViewModel
import kotlin.math.*

@Composable
fun SoundSettingsScreen(vm: LumaViewModel, onOutput: () -> Unit, onClose: () -> Unit) {
    val app=LocalContext.current.applicationContext as LumaApp
    val saved by vm.settings.soundSettings.collectAsState(DspConfig())
    var draft by remember { mutableStateOf<DspConfig?>(null) }
    val config = draft ?: saved
    LaunchedEffect(saved) { if (draft == saved) draft = null }
    val status by DspRuntime.state.collectAsState()
    val output by vm.output.collectAsState()
    val playback by vm.playback.state.collectAsState()
    val tracks by vm.tracks.collectAsState()
    val presets by vm.library.dao.presets().collectAsState(emptyList())
    val repository=remember(app){AnalysisRepository(app,vm.library.dao)}
    var presetName by remember{mutableStateOf("")}
    var analyzing by remember{mutableStateOf(false)}
    val active=config.mode==SoundMode.ENHANCE
    val canEdit = active && status.error == null
    val track=tracks.firstOrNull{it.uri==playback.mediaId}
    fun save(next: DspConfig) {
        val safe = next.safe()
        if (safe == config) return
        draft = safe
        vm.action {
            try {
                vm.settings.saveSound(safe)
            } catch (error: Exception) {
                if (draft == safe) draft = null
                throw error
            }
        }
    }
    val profile=DspRuntime.snapshot.get().analysis
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(start=20.dp,end=20.dp,top=16.dp,bottom=140.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item{Row(verticalAlignment=Alignment.CenterVertically){IconButton(onClick=onClose){Icon(Icons.AutoMirrored.Filled.ArrowBack,"閉じる")};Text("サウンド",style=MaterialTheme.typography.headlineMedium)}}
        item {
            Text(output.actual?.name ?: "実際の出力先は未確認", style = MaterialTheme.typography.bodyMedium)
            TextButton(onOutput) { Text("出力先を切り替え") }
        }
        item{Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){FilterChip(selected=!active,onClick={save(config.copy(mode=SoundMode.DIRECT))},label={Text("Aurio Direct")});FilterChip(selected=active,onClick={save(config.copy(mode=SoundMode.ENHANCE))},label={Text("Aurio Enhance")})}
            Text(stringResource(if (active) R.string.sound_enhance_description else R.string.sound_direct_description), style = MaterialTheme.typography.bodySmall)}
        item{SoundCard("音質の状態"){
            Text(when {
                status.error != null -> status.error!!
                status.sampleRate == null -> "PCM入力待ち（再生すると状態を確認できます）"
                !active && status.applied -> "Directへ切替中"
                !active -> "Aurio Direct / アプリ内補正 OFF"
                status.transitioning || !status.applied -> "Enhanceへ切替中"
                else -> "Aurio Enhance / PCM処理中"
            })
            Text("DSP入力: ${status.sampleRate?.let{"${it/1000.0} kHz"}?:"不明"} / ${status.channels?.let{"${it}ch"}?:"不明"} / PCM 16bit",style=MaterialTheme.typography.bodySmall)
            if (active && status.applied) {
                Text(stringResource(R.string.sound_input_gain, "%.1f".format(status.gainDb)), style = MaterialTheme.typography.bodySmall)
            }
            Text("ビットパーフェクト: ${if(active||status.applied)"対象外（DSP適用・切替中）"else output.bitPerfect}",style=MaterialTheme.typography.bodySmall)
            Text("USB出力周波数はDSP入力とは別です。OS/HALの実クロックは未確認。24bit/float音源はMedia3で16bitへ変換されます。",style=MaterialTheme.typography.bodySmall)
        }}
        item{SoundCard("10バンド EQ"){
            SoundResponseGraph(if (status.error == null) config else config.copy(mode = SoundMode.DIRECT), status.sampleRate, profile)
            SoundSwitch("EQ",config.eqEnabled,canEdit){save(config.copy(eqEnabled=it))}
            TextButton(enabled = canEdit, onClick = {
                save(config.copy(bands = FACTORY_PRESETS.getValue("フラット"), preampDb = 0f, filters = DspFilters(), preset = "フラット"))
            }) { Text(stringResource(R.string.sound_tone_reset)) }
        }}
        item { SoundCard(stringResource(R.string.sound_filters_title)) {
            SoundFilterControls(config.filters, status.sampleRate, canEdit) {
                save(config.copy(filters = it, preset = "カスタム"))
            }
        }}
        item { SoundCard(stringResource(R.string.sound_comparison_title)) {
            SoundSlider(
                stringResource(R.string.sound_comparison_trim), config.comparisonTrimDb, DspConfig.COMPARISON_TRIM_RANGE,
                canEdit, "%.1f dB".format(config.comparisonTrimDb),
            ) { save(config.copy(comparisonTrimDb = it)) }
            Text(stringResource(R.string.sound_comparison_help), style = MaterialTheme.typography.bodySmall)
        }}
        items(EQ_FREQUENCIES.indices.toList(),key={"eq$it"}){i->SoundCard(null){val f=EQ_FREQUENCIES[i];val supported=f<(status.sampleRate?:48000)*.48
            SoundSlider(if(f<1000)"${f.toInt()} Hz"else "${(f/1000).toInt()} kHz",config.bands[i],-12f..12f,canEdit&&config.eqEnabled&&supported,"%.1f dB".format(config.bands[i])){v->save(config.copy(bands=config.bands.toMutableList().also{it[i]=v},preset="カスタム"))}
            if(!supported)Text("この入力周波数では帯域をバイパスします",style=MaterialTheme.typography.labelSmall)
        }}
        item{SoundCard("プリセット"){
            FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)){FACTORY_PRESETS.forEach{(name,bands)->FilterChip(selected=config.preset==name,onClick={save(config.copy(bands=bands,preampDb=0f,filters=DspFilters(),preset=name))},enabled=canEdit,label={Text(name)})}}
            presets.forEach { preset ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(enabled = canEdit, onClick = { save(preset.applyTo(config)) }, modifier = Modifier.weight(1f)) { Text(preset.name) }
                    TextButton(enabled = canEdit, onClick = { vm.action { vm.library.dao.deletePreset(preset.name) } }) { Text("削除") }
                }
            }
            OutlinedTextField(value=presetName,onValueChange={presetName=it.take(40)},label={Text("カスタム名（同名で更新）")},singleLine=true,enabled=canEdit,modifier=Modifier.fillMaxWidth())
            Button(enabled = canEdit && presetName.isNotBlank() && presetName.trim() !in FACTORY_PRESETS, onClick = {
                val preset = config.toDspPreset(presetName.trim())
                vm.action { vm.library.dao.upsertPreset(preset) }
                save(config.copy(preset = preset.name))
            }) { Text("保存 / 更新") }
            SoundSlider("プリアンプ",config.preampDb,-12f..6f,canEdit,"%.1f dB".format(config.preampDb)){save(config.copy(preampDb=it,preset="カスタム"))}
            Text("正のゲインはピーク余裕が確認できる範囲に制限。EQブーストと幅の増幅分は先に減衰します。",style=MaterialTheme.typography.bodySmall)
        }}
        item{SoundCard("Aurio Auto"){
            SoundSwitch("自動音質調整",config.autoEnabled,canEdit){save(config.copy(autoEnabled=it))}
            Text("端末内の実解析から低域・高域の過多を最大2 dB抑制。手動EQの非ゼロ帯域を優先します。AI復元ではありません。",style=MaterialTheme.typography.bodySmall)
            Text(status.analysis,style=MaterialTheme.typography.bodyMedium)
            Button(enabled=canEdit&&track!=null&&!analyzing,onClick={val t=track?:return@Button;analyzing=true;vm.action{try{repository.analyze(t)}finally{analyzing=false}}}){Text(if(analyzing)"解析中…"else "現在曲を解析 / キャッシュ利用")}
            if(track!=null)TextButton(enabled=canEdit&&!analyzing,onClick={val t=track;analyzing=true;vm.action{try{repository.analyze(t,true)}finally{analyzing=false}}}){Text("現在曲を再解析")}
            Text("自動スキャンには含めません。明示した曲だけ低優先度で解析（最大先頭10分）。未解析なら補正せず再生します。",style=MaterialTheme.typography.bodySmall)
        }}
        item{SoundCard("音量均一化"){
            SoundSwitch("音量均一化",config.normalizeEnabled,canEdit){save(config.copy(normalizeEnabled=it))}
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){NormalizeMode.entries.forEach{m->FilterChip(selected=config.normalizeMode==m,enabled=canEdit&&config.normalizeEnabled,onClick={save(config.copy(normalizeMode=m))},label={Text(if(m==NormalizeMode.TRACK)"曲単位"else "アルバム単位")})}}
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf(-18f,-16f,-14f).forEach{target->FilterChip(selected=config.targetLufs==target,enabled=canEdit&&config.normalizeEnabled,onClick={save(config.copy(targetLufs=target))},label={Text("${target.toInt()} LUFS")})}}
            Text("R128 / ReplayGainタグを優先し、なければ解析値を使用。ReplayGainは89 dB基準のタグゲインを使用しLUFSへ読み替えません。増幅は最大+6 dB、ピーク余裕を優先。",style=MaterialTheme.typography.bodySmall)
            if(config.normalizeMode==NormalizeMode.ALBUM){Text("全曲の完全な解析キャッシュが必要。曲別ゲートの長さ加重平均によるアルバム推定値で音量差を保ちます。未完了ならゲイン補正を保留。",style=MaterialTheme.typography.bodySmall)
                Button(enabled=canEdit&&track!=null&&!analyzing,onClick={val t=track?:return@Button;val album=tracks.filter{it.album==t.album&&it.albumArtist.ifBlank{it.artist}==t.albumArtist.ifBlank{t.artist}};analyzing=true;vm.action{try{album.forEach{repository.analyze(it)};repository.analyze(t)}finally{analyzing=false}}}){Text("現在のアルバムを解析")}}
        }}
        item{SoundCard("ステレオ幅"){
            SoundSwitch("ステレオ幅",config.widthEnabled,canEdit){save(config.copy(widthEnabled=it))}
            SoundSlider("幅",config.width,0f..1.2f,canEdit&&config.widthEnabled&&status.channels!=1,"${(config.width*100).roundToInt()}%"){save(config.copy(width=it))}
            Text(if(status.channels==1)"モノラル入力では適用しません"else "Mid/Side方式。100%超は音色や定位が変化します。0%はモノラル。",style=MaterialTheme.typography.bodySmall)
        }}
    }
}

@Composable private fun SoundCard(title:String?,content:@Composable ColumnScope.()->Unit){Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surfaceContainer){Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){if(title!=null)Text(title,style=MaterialTheme.typography.titleMedium);content()}}}
@Composable internal fun SoundSwitch(label:String,value:Boolean,enabled:Boolean,onChange:(Boolean)->Unit){Row(Modifier.fillMaxWidth().heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically){Text(label,modifier=Modifier.weight(1f));Switch(value,onChange,enabled=enabled,modifier=Modifier.semantics{contentDescription=label})}}
@Composable internal fun SoundSlider(label:String,value:Float,range:ClosedFloatingPointRange<Float>,enabled:Boolean,display:String,onChange:(Float)->Unit){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){Text(label, modifier = Modifier.weight(1f));Text(display)};Slider(value.coerceIn(range.start,range.endInclusive),onChange,enabled=enabled,valueRange=range,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).semantics{contentDescription=label})}

