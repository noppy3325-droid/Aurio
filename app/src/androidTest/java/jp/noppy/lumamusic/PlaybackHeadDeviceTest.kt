package jp.noppy.lumamusic

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import jp.noppy.lumamusic.audio.DspRuntime
import jp.noppy.lumamusic.audio.LumaAudioProcessor
import jp.noppy.lumamusic.core.dsp.DspConfig
import jp.noppy.lumamusic.core.dsp.SoundMode
import jp.noppy.lumamusic.playback.PlaybackShuffle
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ErrorCollector
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Measures PCM after Aurio's processor, before the platform output. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackHeadDeviceTest {
    @get:Rule val errors = ErrorCollector()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun twentyDirectTransitionsPreserveEverySampleAndEnhanceKeepsTheFirstTone() {
        checkHead(44_100, SoundMode.DIRECT)
        checkHead(48_000, SoundMode.DIRECT)
        checkHead(48_000, SoundMode.ENHANCE)
    }

    @Test fun flacMp3AndAacRetainTheirDecodedOpeningSignal() {
        checkHead(48_000, SoundMode.DIRECT, "flac", items = 21)
        for (extension in listOf("mp3", "m4a")) {
            for (start in listOf(Start.QUEUE, Start.MANUAL_SKIP, Start.REPEAT_ONE)) {
                errors.checkSucceeds<Unit> {
                    checkHead(48_000, SoundMode.DIRECT, extension, items = if (start == Start.QUEUE) 4 else 2, start = start)
                }
            }
        }
    }

    @Test fun shuffledDecodingKeepsTheOpeningAcrossFormats() {
        for (extension in listOf("wav", "flac", "mp3", "m4a")) {
            errors.checkSucceeds<Unit> { checkHead(48_000, SoundMode.DIRECT, extension, items = 10, start = Start.SHUFFLED) }
        }
        checkHead(48_000, SoundMode.ENHANCE, items = 10, start = Start.SHUFFLED)
    }

    private enum class Start { QUEUE, MANUAL_SKIP, REPEAT_ONE, SHUFFLED }

    private fun checkHead(rate: Int, mode: SoundMode, extension: String = "wav", items: Int = 21, start: Start = Start.QUEUE) {
        val original = DspRuntime.snapshot.get()
        val assets = instrumentation.context.assets
        val source = if (extension == "wav") markerWave(rate)
            else assets.open("playback-head/head.$extension").use { it.readBytes() }
        val file = File.createTempFile("aurio-track-head-", ".$extension", context.cacheDir).apply { writeBytes(source) }
        val sourcePcm = if (extension == "wav") source.copyOfRange(44, source.size)
            else assets.open("playback-head/head-$extension.pcm").use { it.readBytes() }
        val tap = PcmTap()
        val completed = CountDownLatch(1)
        val positions = mutableListOf<Long>()
        lateinit var player: ExoPlayer
        try {
            instrumentation.runOnMainSync {
                DspRuntime.settings(DspConfig(mode = mode))
                val renderer = object : DefaultRenderersFactory(context) {
                    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink =
                        DefaultAudioSink.Builder().setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                            .setAudioOffloadSupportProvider { _, _ -> AudioOffloadSupport.DEFAULT_UNSUPPORTED }
                            .setEnableFloatOutput(false).setAudioProcessors(arrayOf(LumaAudioProcessor(), tap)).build()
                }
                player = ExoPlayer.Builder(context, renderer).build()
                val shuffle = if (start == Start.SHUFFLED) PlaybackShuffle(player) else null
                player.addListener(object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) { shuffle?.update() }
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        if (start == Start.REPEAT_ONE && reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                            player.repeatMode = Player.REPEAT_MODE_OFF
                        }
                    }
                    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                        if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) positions.add(newPosition.positionMs)
                    }
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED) completed.countDown()
                    }
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) { completed.countDown() }
                })
                val queueSize = when (start) {
                    Start.REPEAT_ONE -> 1
                    Start.MANUAL_SKIP -> items + 1
                    Start.QUEUE, Start.SHUFFLED -> items
                }
                player.setMediaItems(List(queueSize) { MediaItem.Builder().setUri(Uri.fromFile(file)).setMediaId(it.toString()).build() },
                    if (shuffle != null) queueSize / 2 else 0, 0)
                if (shuffle != null) player.shuffleModeEnabled = true
                if (start == Start.REPEAT_ONE) player.repeatMode = Player.REPEAT_MODE_ONE
                if (start == Start.MANUAL_SKIP) player.seekToNextMediaItem()
                player.prepare()
                player.play()
            }
            assertTrue("PCM stream must finish", completed.await(35, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { assertNull(player.playerError) }
            assertEquals(List(items - 1) { 0L }, positions)
            val pcm = tap.copy()
            val lossless = extension == "wav" || extension == "flac"
            var maxHeadError = 0.0
            val toneStarts: List<Int>
            if (lossless) {
                assertEquals("$extension $start: all PCM frames reach the output boundary", sourcePcm.size * items, pcm.size)
                repeat(items) { index ->
                    val offset = index * sourcePcm.size
                    val output = pcm.copyOfRange(offset, offset + sourcePcm.size)
                    if (mode == SoundMode.DIRECT) assertArrayEquals("Track $index sample sequence", sourcePcm, output)
                    else assertTrue("Track $index retains its first 10 ms of signal", output.take(rate / 50).any { it != 0.toByte() })
                }
                toneStarts = emptyList()
            } else {
                val reference = ByteBuffer.wrap(sourcePcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val output = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val referenceTone = toneRuns(reference, rate).single()
                val allTones = toneRuns(output, rate)
                // The sink can prebuffer one or more repeats of this 300 ms file.
                // The transition assertion above counts the repeats actually played.
                val decodedTones = if (start == Start.REPEAT_ONE) allTones.take(items) else allTones
                toneStarts = decodedTones.map { it.first }
                context.getExternalFilesDir("playback-qa")?.let { directory ->
                    File(directory, "v140-$extension-${start.name}-full.pcm").writeBytes(pcm)
                }
                Log.i("AurioPcmQA", "format=$extension start=$start refFrames=${reference.capacity()} " +
                    "frames=${output.capacity()} refTone=$referenceTone decodedTones=$allTones")
                // MP3/AAC decoders can retain different amounts of quiet padding at the
                // end. Measure each identifiable opening and its duration independently.
                assertEquals("$extension $start: missing opening tone (starts=$toneStarts)", items, decodedTones.size)
                assertTrue("$extension $start: unexpected leading silence (starts=$toneStarts)",
                    abs(toneStarts.first() - referenceTone.first) <= rate / 250)
                toneStarts.zipWithNext().forEach { (previous, next) ->
                    assertTrue("$extension $start: unexpected interval $previous -> $next",
                        abs(next - previous - reference.capacity()) <= 1152)
                }
                decodedTones.forEachIndexed { index, tone ->
                    assertTrue("$extension $start: truncated tone $index", abs(tone.count() - referenceTone.count()) <= rate / 250)
                    val headError = (0 until rate / 50).sumOf { frame ->
                        abs(reference[referenceTone.first + frame].toInt() - output[tone.first + frame].toInt())
                    }.toDouble() / (rate / 50)
                    maxHeadError = maxOf(maxHeadError, headError)
                    assertTrue("$extension $start track $index: opening PCM error $headError", headError < 480.0)
                }
            }
            if (start == Start.QUEUE) context.getExternalFilesDir("playback-qa")?.let { directory ->
                File(directory, "v140-$extension-$rate-${mode.name}.pcm").writeBytes(pcm.copyOf(sourcePcm.size))
            }
            Log.i("AurioPcmQA", "format=$extension rate=$rate mode=$mode start=$start transitions=${positions.size} frames=${pcm.size / 2} maxHeadError=$maxHeadError starts=$toneStarts firstPositions=$positions")
        } finally {
            instrumentation.runOnMainSync { player.release(); DspRuntime.settings(original.config) }
            file.delete()
        }
    }

    private fun toneRuns(samples: java.nio.ShortBuffer, rate: Int): List<IntRange> = buildList {
        var start = -1
        var loud = -1
        for (index in 0 until samples.capacity()) {
            if (abs(samples[index].toInt()) < 512) continue
            if (loud == -1 || index - loud > rate / 8) {
                if (start >= 0) add(start..loud)
                start = index
            }
            loud = index
        }
        if (start >= 0) add(start..loud)
    }

    private fun markerWave(rate: Int): ByteArray {
        val frames = rate * 3 / 10
        val dataBytes = frames * 2
        return ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVEfmt ".toByteArray()).putInt(16)
            putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
            put("data".toByteArray()).putInt(dataBytes)
            repeat(frames) { index ->
                val sample = if (index < rate / 20) sin((index + 1) * 2 * PI * 1_000 / rate) * 8_000 else 0.0
                putShort(sample.toInt().toShort())
            }
        }.array()
    }

    private class PcmTap : BaseAudioProcessor() {
        private val samples = ByteArrayOutputStream()

        override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat) = inputAudioFormat

        override fun queueInput(inputBuffer: ByteBuffer) {
            if (!inputBuffer.hasRemaining()) return
            val bytes = ByteArray(inputBuffer.remaining())
            inputBuffer.get(bytes)
            samples.write(bytes)
            replaceOutputBuffer(bytes.size).put(bytes).flip()
        }

        fun copy(): ByteArray = samples.toByteArray()
    }
}
