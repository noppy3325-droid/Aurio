package jp.noppy.lumamusic.audio

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import jp.noppy.lumamusic.core.shouldPauseOnRouteChange

data class DeviceReport(
    val id: Int,
    val name: String,
    val type: String,
    val usb: Boolean,
    val rates: List<Int>,
    val channels: List<Int>,
    val encodings: List<Int>,
    val selectable: Boolean,
    val speaker: Boolean,
)

internal fun isUsb(device: AudioDeviceInfo): Boolean = when (device.type) {
    AudioDeviceInfo.TYPE_USB_DEVICE,
    AudioDeviceInfo.TYPE_USB_HEADSET,
    AudioDeviceInfo.TYPE_USB_ACCESSORY -> true
    else -> false
}

internal fun AudioDeviceInfo.mediaOutputType(): String? = when (type) {
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "本体スピーカー"
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    AudioDeviceInfo.TYPE_BLE_HEADSET,
    AudioDeviceInfo.TYPE_BLE_SPEAKER,
    AudioDeviceInfo.TYPE_BLE_BROADCAST,
    AudioDeviceInfo.TYPE_HEARING_AID -> "Bluetooth"
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_LINE_ANALOG,
    AudioDeviceInfo.TYPE_LINE_DIGITAL,
    AudioDeviceInfo.TYPE_AUX_LINE -> "有線オーディオ"
    AudioDeviceInfo.TYPE_HDMI,
    AudioDeviceInfo.TYPE_HDMI_ARC,
    AudioDeviceInfo.TYPE_HDMI_EARC -> "HDMI"
    else -> if (isUsb(this)) "USB Audio" else null
}

internal fun AudioDeviceInfo.toReport(): DeviceReport {
    val speaker = type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER || type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE
    val outputType = mediaOutputType()
    return DeviceReport(
        id = id,
        name = if (speaker) "本体スピーカー" else productName.toString(),
        type = if (speaker) "本体スピーカー" else outputType ?: "Android Audio",
        usb = isUsb(this),
        rates = sampleRates.toList(),
        channels = channelCounts.toList(),
        encodings = encodings.toList(),
        selectable = outputType != null,
        speaker = speaker,
    )
}

enum class RouteSelectionStatus { AUTOMATIC, WAITING, SWITCHING, ACTIVE, MISMATCH }

internal fun routeSelectionStatus(preferredId: Int?, actualId: Int?, switching: Boolean): RouteSelectionStatus = when {
    preferredId == null -> RouteSelectionStatus.AUTOMATIC
    preferredId == actualId -> RouteSelectionStatus.ACTIVE
    switching -> RouteSelectionStatus.SWITCHING
    actualId == null -> RouteSelectionStatus.WAITING
    else -> RouteSelectionStatus.MISMATCH
}

internal fun pauseAfterUsbRouteChange(
    previousWasUsb: Boolean,
    previousId: Int?,
    currentId: Int?,
    previousConnected: Boolean,
    switching: Boolean,
): Boolean = shouldPauseOnRouteChange(previousWasUsb, previousId, currentId) && (!switching || !previousConnected)
fun encodingName(encoding: Int): String = when (encoding) {
    AudioFormat.ENCODING_PCM_8BIT -> "PCM 8 bit"
    AudioFormat.ENCODING_PCM_16BIT -> "PCM 16 bit"
    AudioFormat.ENCODING_PCM_24BIT_PACKED -> "PCM 24 bit"
    AudioFormat.ENCODING_PCM_32BIT -> "PCM 32 bit"
    AudioFormat.ENCODING_PCM_FLOAT -> "PCM float 32 bit"
    else -> "encoding=$encoding"
}
