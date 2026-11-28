package jp.noppy.lumamusic

import android.app.Application
import androidx.room.Room
import androidx.work.Configuration
import jp.noppy.lumamusic.audio.OutputMonitor
import jp.noppy.lumamusic.data.*
import jp.noppy.lumamusic.update.UpdateManager

class LumaApp : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration get() = Configuration.Builder().build()
    val database by lazy {
        Room.databaseBuilder(this, LibraryDatabase::class.java, "luma.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .build()
    }
    val library by lazy { LibraryRepository(this, database) }
    internal val playbackHistory by lazy { PlaybackHistoryStore(library.dao) }
    val settings by lazy { SettingsRepository(this) }
    val output by lazy { OutputMonitor(this) }
    internal val updates by lazy { UpdateManager(this) }
}
