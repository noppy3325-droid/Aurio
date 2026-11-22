package jp.noppy.lumamusic.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRouting
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import jp.noppy.lumamusic.core.OutputMode
import jp.noppy.lumamusic.core.PcmSpec
import jp.noppy.lumamusic.core.unchangedPcm
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

private const val STANDARD_PLAYBACK = "Android標準音声再生"
private const val ROUTE_SETTLE_MS = 3_000L

data class OutputState(
    val connected: List<DeviceReport> = emptyList(),
    val actual: DeviceReport? = null,
    val preferredId: Int? = null,
    val routeStatus: RouteSelectionStatus = RouteSelectionStatus.AUTOMATIC,
    val routeMessage: String? = null,
    val track: PcmSpec? = null,
    val requested: OutputMode = OutputMode.USB_HIGH_QUALITY,
    val method: String = STANDARD_PLAYBACK,
    val bitPerfect: String = "未確認",
    val message: String? = null,
    val mixerDeviceId: Int? = null,
) {
    val mixerApplied: Boolean get() = mixerDeviceId != null
    val bitPerfectVolumeRestricted: Boolean
        get() = requested == OutputMode.BIT_PERFECT && actual?.id != null && actual.id == mixerDeviceId
}

class OutputMonitor(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val mutable = MutableStateFlow(OutputState())
    val state = mutable.asStateFlow()
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    private var engine: AudioOutputEngine = UsbHighQualityEngine()
    private var started = false
    private var track: AudioTrack? = null
    private var lastRoute: DeviceReport? = null
    private var switchUntil = 0L
    private var mixer: MixerApi34? = null
    private var unprocessed = true
    private var source: PcmSpec? = null
    private var optimizationFailed = false
    private var onDisconnect: (() -> Unit)? = null
    private var applyPreferred: ((AudioDeviceInfo?) -> Unit)? = null

    private val routeListener = AudioRouting.OnRoutingChangedListener { routing ->
        if (routing === track) updateRoute()
    }
    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refreshDevices()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            refreshDevices(removedDevices.filter { it.isSink }.mapTo(mutableSetOf()) { it.id })
        }
    }
    private val poll = object : Runnable {
        override fun run() {
            if (!started) return
            updateRoute()
            handler.postDelayed(this, 700)
        }
    }

    @Synchronized
    fun start(onDisconnect: () -> Unit, applyPreferred: (AudioDeviceInfo?) -> Unit) {
        if (started) return
        started = true
        this.onDisconnect = onDisconnect
        this.applyPreferred = applyPreferred
        if (Build.VERSION.SDK_INT >= 34) {
            mixer = runCatching { MixerApi34(audio, attributes, handler, ::mixerInvalidated) }.getOrNull()
        }
        refreshDevices()
        mutable.value.preferredId?.let { applyPreferred(device(it)) }
        audio.registerAudioDeviceCallback(callback, handler)
        handler.post(poll)
    }

    fun refreshDevices() = refreshDevices(emptySet())

    @Synchronized
    private fun refreshDevices(removedIds: Set<Int>) {
        val connected = devices()
        val ids = connected.mapTo(mutableSetOf()) { it.id }
        val previous = lastRoute
        val preferred = mutable.value.preferredId
        val preferredRemoved = preferred != null && preferred !in ids
        val activeRemoved = previous != null && previous.id in removedIds
        if (activeRemoved || preferredRemoved) {
            if (activeRemoved) lastRoute = null
            clearMixer()
            switchUntil = 0
            mutable.update {
                it.copy(
                    actual = if (activeRemoved) null else it.actual,
                    preferredId = if (preferredRemoved) null else preferred,
                    routeMessage = if (activeRemoved) "出力機器が外れたため一時停止しました" else
                        "選んだ機器が外れたため自動に戻しました",
                )
            }
            if (activeRemoved) onDisconnect?.invoke()
            if (preferredRemoved) applyPreferred?.invoke(null)
        }
        val reports = connected.map(AudioDeviceInfo::toReport)
            .sortedWith(compareByDescending<DeviceReport> { it.selectable && it.speaker }.thenBy { it.name })
        mutable.update { it.copy(connected = reports) }
        updateRoute()
    }

    @Synchronized
    fun preferDevice(id: Int?) {
        refreshDevices()
        val target = id?.let(::device)
        if (id != null && target?.mediaOutputType() == null) {
            mutable.update { it.copy(routeMessage = "機器が見つかりません。接続を確認してください") }
            return
        }
        if (mutable.value.preferredId == id && mutable.value.routeStatus != RouteSelectionStatus.MISMATCH) return
        switchUntil = SystemClock.elapsedRealtime() + ROUTE_SETTLE_MS
        clearMixer()
        mutable.update { it.copy(preferredId = id, routeMessage = null) }
        applyPreferred?.invoke(target)
        // Media3 can change the route without reopening the AudioTrack.
        updateRoute()
        val output = mutable.value
        if (output.actual != null && output.actual.id == target?.id) {
            configure(output.track, track?.format?.channelMask ?: 0)
        }
    }

    @Synchronized
    fun mode(value: OutputMode) {
        if (engine.mode == value) return
        optimizationFailed = false
        engine = when (value) {
            OutputMode.STANDARD -> StandardMedia3Engine()
            OutputMode.USB_HIGH_QUALITY -> UsbHighQualityEngine()
            OutputMode.BIT_PERFECT -> UsbBitPerfectEngine()
        }
        clearMixer()
        mutable.update { it.copy(requested = value) }
        configure(mutable.value.track, track?.format?.channelMask ?: 0)
    }

    @Synchronized
    fun processingIsUnchanged(value: Boolean) {
        if (unprocessed == value) return
        unprocessed = value
        clearMixer()
        configure(mutable.value.track, track?.format?.channelMask ?: 0)
    }

    /** Called before AudioTrack creation, using the actual PCM configuration produced by Media3. */
    @Synchronized
    fun configure(spec: PcmSpec?, channelMask: Int) {
        mutable.update { it.copy(track = spec) }
        val target = anticipatedDevice()?.takeIf(::isUsb)
        if (target == null || spec == null || engine.mode == OutputMode.STANDARD || optimizationFailed) {
            clearMixer(preserveMessage = optimizationFailed)
            return
        }
        val api = mixer
        if (Build.VERSION.SDK_INT < 34 || api == null) {
            clearMixer()
            val status = if (Build.VERSION.SDK_INT < 34) "このOSではAPI非対応" else "ミキサーAPI利用不可・未確認"
            mutable.update { it.copy(bitPerfect = status, message = "Android標準音声再生を使用します") }
            return
        }
        if (mutable.value.mixerDeviceId != target.id) clearMixer()
        val unchanged = unprocessed && unchangedPcm(source, spec, 1f, 1f, false)
        val result = api.apply(target, spec, channelMask, engine, unchanged)
        val method = when {
            !result.applied -> STANDARD_PLAYBACK
            engine.mode == OutputMode.BIT_PERFECT -> "USB / BIT_PERFECT API設定（実出力未確認）"
            else -> "USB / 形式一致のミキサー希望設定済み"
        }
        mutable.update {
            it.copy(method = method, bitPerfect = result.bitPerfect, message = result.message,
                mixerDeviceId = target.id.takeIf { result.applied })
        }
    }

    fun observe(newTrack: AudioTrack) {
        handler.post {
            synchronized(this) {
                if (!started) return@synchronized
                stopObservingTrack()
                track = newTrack
                newTrack.addOnRoutingChangedListener(routeListener, handler)
                updateRoute()
            }
        }
    }

    @Synchronized
    private fun updateRoute() {
        val current = track
        val route = runCatching {
            if (current?.playState == AudioTrack.PLAYSTATE_PLAYING) current.routedDevice else null
        }.getOrNull()
        val output = mutable.value
        val switching = SystemClock.elapsedRealtime() < switchUntil
        val justSettled = switchUntil != 0L && !switching
        if (justSettled) switchUntil = 0
        val speaker = route?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE
        val selectedSpeaker = speaker && output.connected.any { it.id == output.preferredId && it.speaker }
        val status = routeSelectionStatus(output.preferredId, if (selectedSpeaker) output.preferredId else route?.id, switching)
        val previous = lastRoute
        val changed = route != null && previous?.id != route.id
        val report = route?.let { if (changed) it.toReport() else previous }
        if (changed && pauseAfterUsbRouteChange(
                previous?.usb == true, previous?.id, route.id, device(previous?.id) != null, switching,
            )) {
            onDisconnect?.invoke()
            mutable.update { it.copy(routeMessage = "USBの出力経路が外れたため一時停止しました") }
        }
        if (report != null) lastRoute = report
        mutable.update { it.copy(actual = report, routeStatus = status) }
        if (status == RouteSelectionStatus.MISMATCH && output.preferredId != null) {
            if (output.mixerDeviceId != null) clearMixer()
        } else if (route != null && current != null && (changed || justSettled || status != output.routeStatus)) {
            if (output.mixerDeviceId != null && output.mixerDeviceId != route.id) clearMixer()
            if (status != RouteSelectionStatus.SWITCHING) configure(output.track, current.format.channelMask)
        }
    }

    private fun anticipatedDevice(): AudioDeviceInfo? {
        val output = mutable.value
        if (output.routeStatus == RouteSelectionStatus.MISMATCH) return null
        output.preferredId?.let { return device(it) }
        output.actual?.id?.let { id -> device(id)?.let { return it } }
        return if (Build.VERSION.SDK_INT >= 33) runCatching { audio.getAudioDevicesForAttributes(attributes).singleOrNull() }.getOrNull()
        else device(lastRoute?.id)
    }

    private fun devices(): List<AudioDeviceInfo> = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.isSink }
    private fun device(id: Int?): AudioDeviceInfo? = id?.let { devices().firstOrNull { device -> device.id == it } }

    @Synchronized
    private fun mixerInvalidated(deviceId: Int) {
        if (mutable.value.mixerDeviceId != deviceId) return
        mutable.update {
            it.copy(mixerDeviceId = null, method = STANDARD_PLAYBACK, bitPerfect = "設定変更・実出力未確認",
                message = "ミキサー設定が解除または変更されました")
        }
    }

    private fun clearMixer(preserveMessage: Boolean = false) {
        if (Build.VERSION.SDK_INT >= 34) mixer?.clear()
        mutable.update {
            it.copy(mixerDeviceId = null, method = STANDARD_PLAYBACK, bitPerfect = "未確認",
                message = it.message.takeIf { preserveMessage })
        }
    }

    @Synchronized
    fun noteSource(value: PcmSpec?) { source = value }

    @Synchronized
    fun newSource() {
        optimizationFailed = false
        source = null
        clearMixer()
    }

    @Synchronized
    fun fallback(reason: String) {
        optimizationFailed = true
        clearMixer()
        mutable.update { it.copy(message = reason) }
    }

    private fun stopObservingTrack() {
        track?.let { runCatching { it.removeOnRoutingChangedListener(routeListener) } }
        track = null
    }

    @Synchronized
    fun close() {
        if (!started) return
        started = false
        audio.unregisterAudioDeviceCallback(callback)
        handler.removeCallbacks(poll)
        stopObservingTrack()
        lastRoute = null
        switchUntil = 0
        clearMixer()
        if (Build.VERSION.SDK_INT >= 34) mixer?.close()
        mixer = null
        onDisconnect = null
        applyPreferred = null
        mutable.update { it.copy(actual = null, track = null, routeStatus = routeSelectionStatus(it.preferredId, null, false)) }
    }
}
