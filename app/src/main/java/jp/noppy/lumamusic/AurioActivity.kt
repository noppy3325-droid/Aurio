package jp.noppy.lumamusic

import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import jp.noppy.lumamusic.ui.*
import jp.noppy.lumamusic.update.OpenAurioUpdate

class AurioActivity : ComponentActivity() {
    private val model by viewModels<LumaViewModel>()
    private lateinit var standby: StandbyController
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        volumeControlStream = AudioManager.STREAM_MUSIC
        standby = StandbyController(this)
        if (intent.action == OpenAurioUpdate) model.updateRequested.value = true
        setContent { LumaScreen(model, standby) }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == OpenAurioUpdate) model.updateRequested.value = true
    }
    override fun onResume() {
        super.onResume()
        if (::standby.isInitialized) standby.foreground(true)
        model.refreshIfStale()
        model.updates.foreground()
    }
    override fun onPause() { if (::standby.isInitialized) standby.foreground(false); super.onPause() }
    override fun onDestroy() { if (::standby.isInitialized) standby.close(); super.onDestroy() }
}
