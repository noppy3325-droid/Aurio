package jp.noppy.lumamusic

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import jp.noppy.lumamusic.data.PlayHistory
import jp.noppy.lumamusic.data.Track
import jp.noppy.lumamusic.data.UserTrack
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class UpgradeDataDeviceTest {
    @Suppress("DEPRECATION")
    @Test fun existingRowsAndSettingsSurviveAnInPlaceUpgrade() = runBlocking<Unit> {
        val phase = InstrumentationRegistry.getArguments().getString("upgradePhase")
        assumeTrue(phase == "seed" || phase == "check")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as LumaApp
        val dao = app.library.dao
        val file = File(context.filesDir, "v140-upgrade-fixture")
        val version = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        val grants = context.contentResolver.persistedUriPermissions.sortedBy { it.uri.toString() }
            .map { "${it.uri} ${it.isReadPermission} ${it.isWritePermission}" }
        if (phase == "seed") {
            assertEquals(32L, version)
            check(!file.exists()) { "A previous upgrade fixture must be checked first" }
            val id = UUID.randomUUID().toString()
            val uri = "aurio-upgrade-qa:$id"
            val previousMotion = app.settings.preferences.first().reducedMotion
            dao.upsertTracks(listOf(Track(uri, "Aurio upgrade QA")))
            dao.userTrack(UserTrack(uri, favorite = true, lyrics = "QA lyric"))
            dao.insertHistory(PlayHistory(trackUri = uri, playedAt = System.currentTimeMillis()))
            app.library.createPlaylistWithTrack("Upgrade QA $id", uri)
            app.settings.boolean("motion", !previousMotion)
            file.writeText((listOf(id, previousMotion.toString()) + grants).joinToString("\n"))
            Log.i("AurioUpgradeQA", "seeded version=$version persistedGrants=${grants.size}")
        } else {
            assertEquals(33L, version)
            val saved = file.readLines()
            val id = saved[0]
            val uri = "aurio-upgrade-qa:$id"
            val playlist = dao.playlists().first().single { it.name == "Upgrade QA $id" }
            try {
                assertEquals("Aurio upgrade QA", dao.track(uri)?.title)
                assertEquals(UserTrack(uri, favorite = true, lyrics = "QA lyric"), dao.userTrack(uri))
                assertTrue(dao.history().first().any { it.trackUri == uri })
                assertEquals(listOf(uri), dao.entries().first().filter { it.playlistId == playlist.id }.map { it.trackUri })
                assertEquals(!saved[1].toBooleanStrict(), app.settings.preferences.first().reducedMotion)
                assertEquals(saved.drop(2), grants)
                Log.i("AurioUpgradeQA", "verified version=$version persistedGrants=${grants.size}")
            } finally {
                app.settings.boolean("motion", saved[1].toBooleanStrict())
                dao.clearPlaylist(playlist.id)
                dao.deletePlaylist(playlist.id)
                val db = app.database.openHelper.writableDatabase
                db.execSQL("DELETE FROM play_history WHERE trackUri=?", arrayOf(uri))
                db.execSQL("DELETE FROM user_tracks WHERE uri=?", arrayOf(uri))
                db.execSQL("DELETE FROM tracks WHERE uri=?", arrayOf(uri))
                file.delete()
            }
        }
    }
}
