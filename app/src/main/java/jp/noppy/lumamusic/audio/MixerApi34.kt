package jp.noppy.lumamusic.audio

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Handler
import androidx.annotation.RequiresApi
import jp.noppy.lumamusic.core.MixerOption
import jp.noppy.lumamusic.core.PcmSpec
internal data class MixerResult(val applied: Boolean, val bitPerfect: String, val message: String?)

@RequiresApi(34)
internal class MixerApi34(
    private val audio: AudioManager, private val attributes: AudioAttributes, handler: Handler, onInvalidated: (Int) -> Unit,
) {
    private var device: AudioDeviceInfo? = null
    private var selected: AudioMixerAttributes? = null
    private val listener = AudioManager.OnPreferredMixerAttributesChangedListener { attrs, changedDevice, mixer ->
        if (attrs.usage == attributes.usage && changedDevice.id == device?.id && mixer != selected) onInvalidated(changedDevice.id)
    }
    init { audio.addOnPreferredMixerAttributesChangedListener({ runnable -> handler.post(runnable) }, listener) }
    fun apply(target: AudioDeviceInfo, spec: PcmSpec, mask: Int, engine: AudioOutputEngine, unchanged: Boolean): MixerResult {
        return try {
            val supported = audio.getSupportedMixerAttributes(target)
            val candidates = supported.map { MixerOption(PcmSpec(it.format.sampleRate, it.format.channelCount, it.format.encoding), it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT) }
            val support = if (candidates.any { it.bitPerfect }) "対応形式あり（動作未確認）" else "この接続で対応形式なし"
            val choice = engine.select(spec, candidates, unchanged)
            val attribute = choice?.let { chosen -> supported.firstOrNull { candidate ->
                PcmSpec(candidate.format.sampleRate, candidate.format.channelCount, candidate.format.encoding) == chosen.spec &&
                    (candidate.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT) == chosen.bitPerfect &&
                    candidate.format.channelMask == mask
            } }
            if (attribute == null) {
                clear()
                MixerResult(false, support, "一致するミキサー形式を適用できないため通常再生します")
            } else if (selected == attribute && device?.id == target.id && audio.getPreferredMixerAttributes(attributes, target) == attribute) {
                MixerResult(true, if (choice.bitPerfect) "API設定済み・実出力未確認" else support, null)
            } else {
                clear()
                val accepted = audio.setPreferredMixerAttributes(attributes, target, attribute)
                if (accepted && audio.getPreferredMixerAttributes(attributes, target) == attribute) {
                    device = target; selected = attribute
                    MixerResult(true, if (choice.bitPerfect) "API設定済み・実出力未確認" else support, null)
                } else {
                    audio.clearPreferredMixerAttributes(attributes, target)
                    MixerResult(false, support, "ミキサー設定が受理されなかったため通常再生します")
                }
            }
        } catch (e: RuntimeException) {
            runCatching { audio.clearPreferredMixerAttributes(attributes, target) }
            clear()
            MixerResult(false, "未確認", "ミキサーAPIを利用できません: ${e.localizedMessage}。通常再生します")
        }
    }
    fun clear() {
        val old = device
        device = null; selected = null
        if (old != null) runCatching { audio.clearPreferredMixerAttributes(attributes, old) }
    }
    fun close() { clear(); audio.removeOnPreferredMixerAttributesChangedListener(listener) }
}
