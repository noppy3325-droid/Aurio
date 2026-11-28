package jp.noppy.lumamusic.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import jp.noppy.lumamusic.core.dsp.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

data class DspSnapshot(val config: DspConfig = DspConfig(), val analysis: TrackAnalysis? = null, val revision: Long = 0)

data class DspStatus(
    val mode: SoundMode = SoundMode.DIRECT,
    val sampleRate: Int? = null,
    val channels: Int? = null,
    val processedFrames: Long = 0,
    val applied: Boolean = false,
    val transitioning: Boolean = false,
    val gainDb: Double = 0.0,
    val analysis: String = "解析待ち",
    val error: String? = null,
)

/** Publishes one coherent configuration to the audio thread, with no I/O in the PCM loop. */
object DspRuntime {
    @Volatile var sourceUri:String?=null
        private set
    val snapshot=AtomicReference(DspSnapshot())
    private var tags:Map<String,String> = emptyMap()
    private var rawAnalysis:TrackAnalysis?=null
    private var analysisMessage="解析待ち"
    private val mutable=MutableStateFlow(DspStatus())
    val state=mutable.asStateFlow()
    @Synchronized fun settings(config: DspConfig) = publish(config.safe())
    @Synchronized fun analysis(value:TrackAnalysis?,message:String) { rawAnalysis=value;analysisMessage=message;publish() }
    @Synchronized fun metadata(values:Map<String,String>) { tags=tags+values;publish() }
    private fun publish(config: DspConfig = snapshot.get().config) {
        val tag = if (config.normalizeEnabled) {
            parseGainTags(tags, config.normalizeMode == NormalizeMode.ALBUM, config.targetLufs.toDouble())
        } else null
        val value = if (tag == null) rawAnalysis else {
            val measured = rawAnalysis ?: TrackAnalysis(null, 1.0, EmptyToneGains, 0, 0, false)
            measured.copy(tagGainDb = tag.gainDb, tagKind = tag.kind, peak = tag.peak ?: measured.peak)
        }
        snapshot.updateAndGet {
            if (it.config == config && it.analysis == value) it
            else DspSnapshot(config, value, it.revision + 1)
        }
        mutable.update {
            it.copy(mode = config.mode, analysis = if (tag == null) analysisMessage else "${tag.kind}タグ優先 / $analysisMessage")
        }
    }

    @Synchronized fun source(uri: String?) {
        sourceUri = uri
        tags = emptyMap()
        mutable.update { it.copy(sampleRate = null, channels = null, processedFrames = 0, gainDb = 0.0) }
        analysis(null, "解析待ち")
    }

    fun audio(rate: Int, channels: Int, frames: Long, applied: Boolean, transitioning: Boolean, gain: Double) {
        mutable.update {
            it.copy(
                sampleRate = rate, channels = channels, processedFrames = frames,
                applied = applied, transitioning = transitioning, gainDb = gain, error = null,
            )
        }
    }

    fun unavailable(message: String) {
        mutable.update {
            it.copy(applied = false, transitioning = false, sampleRate = null, channels = null,
                processedFrames = 0, gainDb = 0.0, error = message)
        }
    }

    fun stopped() {
        mutable.update { DspStatus(mode = it.mode, analysis = it.analysis) }
    }

    private val EmptyToneGains = List(10) { 0f }
}

@androidx.annotation.OptIn(UnstableApi::class)
class LumaAudioProcessor:BaseAudioProcessor() {
    private var engine:PcmDspEngine?=null
    private var frame=FloatArray(0)
    private var revision= -1L
    private var frames=0L
    private var nextReport=0L
    private var lastApplied = false
    private var lastTransition = false

    override fun onConfigure(inputAudioFormat:AudioProcessor.AudioFormat):AudioProcessor.AudioFormat {
        if(inputAudioFormat.encoding!=C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount !in 1..8 || inputAudioFormat.sampleRate<=0){
            DspRuntime.unavailable("このPCM形式ではAurio DSPを利用できません")
            return AudioProcessor.AudioFormat.NOT_SET
        }
        return inputAudioFormat // Remains active in Direct so switching never recreates the player.
    }

    override fun onFlush() {
        engine = if (isActive) PcmDspEngine(inputAudioFormat.sampleRate, inputAudioFormat.channelCount) else null
        frame = FloatArray(engine?.channels ?: 0)
        revision = -1
        frames = 0
        nextReport = 0
        lastApplied = false
        lastTransition = false
    }
    override fun queueInput(inputBuffer:ByteBuffer) {
        // Media3 also calls processors with the shared EMPTY_BUFFER while draining.
        // Do not make that same zero-capacity object both source and destination.
        if (!inputBuffer.hasRemaining()) return
        val dsp=engine?:return
        val snapshot=DspRuntime.snapshot.get()
        val changed = snapshot.revision != revision
        if (changed) {
            dsp.configure(snapshot.config, snapshot.analysis)
            revision = snapshot.revision
        }
        val output=replaceOutputBuffer(inputBuffer.remaining()).order(ByteOrder.nativeOrder())
        inputBuffer.order(ByteOrder.nativeOrder())
        val count=inputBuffer.remaining()/(2*frame.size)
        if(dsp.isDirect)output.put(inputBuffer) // Byte exact bypass, no float roundtrip or limiter.
        else {
            repeat(count){for(ch in frame.indices)frame[ch]=inputBuffer.short/32768f;dsp.processFrame(frame)
                for(ch in frame.indices)output.putShort((frame[ch]*32768).roundToInt().coerceIn(-32768,32767).toShort())}
            while(inputBuffer.hasRemaining())output.put(inputBuffer.get())
        }
        frames+=count
        val applied = !dsp.isDirect
        if (changed || applied != lastApplied || dsp.transitioning != lastTransition || frames >= nextReport) {
            DspRuntime.audio(dsp.sampleRate, dsp.channels, frames, applied, dsp.transitioning, dsp.gainDb)
            nextReport = frames + dsp.sampleRate / 2
            lastApplied = applied
            lastTransition = dsp.transitioning
        }
        output.flip()
    }
    override fun onReset(){engine=null;frame=FloatArray(0);DspRuntime.stopped()}
}
