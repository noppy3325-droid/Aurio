package jp.noppy.lumamusic

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Keeps the player task independent of the launcher component being enabled or disabled. */
class LauncherEntryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, AurioActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        finish()
    }
}
