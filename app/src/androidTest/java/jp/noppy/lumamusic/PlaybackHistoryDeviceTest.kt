package jp.noppy.lumamusic

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.ListenableFuture
import jp.noppy.lumamusic.data.stableTrackId
import jp.noppy.lumamusic.playback.PlaybackService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PlaybackHistoryDeviceTest {
    @Test fun qualifiesEachBackgroundRepeatAndRecordsNaturalCompletion() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as LumaApp
        val file = File.createTempFile("history-repeat-", ".wav", context.filesDir)
        val uri = Uri.fromFile(file)
        val trackId = stableTrackId(uri.toString())
        writePcmFixture(file)
        var pending: ListenableFuture<MediaController>? = null
        var controller: MediaController? = null
        try {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            instrumentation.runOnMainSync {
                val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
                pending = MediaController.Builder(context, token).buildAsync()
            }
            val player = pending!!.get(15, TimeUnit.SECONDS)
            controller = player
            instrumentation.runOnMainSync {
                player.setMediaItem(MediaItem.Builder().setMediaId(uri.toString()).setUri(uri).build())
                player.repeatMode = Player.REPEAT_MODE_ONE
                player.prepare()
                player.play()
            }
            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").use { command ->
                FileInputStream(command.fileDescriptor).readBytes()
            }
            val stats = withTimeout(20_000L) {
                app.library.dao.playbackStats().first { rows ->
                    rows.any { it.stableTrackId == trackId && it.validPlayCount >= 2 && it.completedPlayCount >= 2 }
                }.single { it.stableTrackId == trackId }
            }
            assertTrue(stats.totalAudibleMs >= 5_100L)
            val history = app.library.dao.history().first().filter { it.trackUri == uri.toString() }
            assertTrue(history.size.toLong() >= stats.validPlayCount)
        } finally {
            instrumentation.runOnMainSync {
                controller?.let { player ->
                    player.pause()
                    player.repeatMode = Player.REPEAT_MODE_OFF
                    player.clearMediaItems()
                }
                pending?.let { MediaController.releaseFuture(it) }
            }
            file.delete()
        }
    }

    private fun writePcmFixture(file: File) {
        val sampleRate = 44_100
        val dataSize = sampleRate * 3 * 4
        val bytes = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(36 + dataSize).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(2).putInt(sampleRate).putInt(sampleRate * 4)
            .putShort(4).putShort(16).put("data".toByteArray()).putInt(dataSize)
        file.writeBytes(bytes.array())
    }
}
