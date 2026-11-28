package jp.noppy.lumamusic

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import jp.noppy.lumamusic.core.dsp.DspConfig
import jp.noppy.lumamusic.core.dsp.DspFilters
import jp.noppy.lumamusic.core.dsp.NormalizeMode
import jp.noppy.lumamusic.core.dsp.SoundMode
import jp.noppy.lumamusic.data.DspPreset
import jp.noppy.lumamusic.data.LibraryDatabase
import jp.noppy.lumamusic.data.SettingsRepository
import jp.noppy.lumamusic.data.applyTo
import jp.noppy.lumamusic.data.toDspPreset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SoundSettingsPersistenceDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val filters = DspFilters(highPassHz = 80f, lowPassHz = 10_000f, lowShelfDb = -2f, highShelfDb = 3.5f)
    private val legacyBands = listOf(-1f, 0f, 2f, 3f, 0f, -2f, 0f, 0f, 1f, 4f)

    @Test
    fun legacySoundLoadsAndNewSettingsSurviveReopeningDataStore() = runBlocking<Unit> {
        val file = File(context.cacheDir, "sound-${UUID.randomUUID()}.preferences_pb")
        val expectedLegacy = DspConfig(
            mode = SoundMode.DIRECT,
            bands = legacyBands,
            preampDb = -2.5f,
            autoEnabled = true,
            normalizeEnabled = true,
            normalizeMode = NormalizeMode.ALBUM,
            targetLufs = -18f,
            widthEnabled = true,
            width = 1.1f,
            preset = "Legacy",
        )
        val expectedNew = expectedLegacy.copy(filters = filters, comparisonTrimDb = -3f)
        try {
            withSettings(file) { settings ->
                settings.text("sound", """
                    {"mode":"DIRECT","eq":true,"bands":[-1,0,2,3,0,-2,0,0,1,4],"preamp":-2.5,
                     "auto":true,"normalize":true,"normalizeMode":"ALBUM","target":-18,
                     "widthEnabled":true,"width":1.1,"preset":"Legacy"}
                """.trimIndent())
                assertEquals(expectedLegacy, settings.soundSettings.first())
                settings.saveSound(expectedNew)
            }
            withSettings(file) { settings ->
                assertEquals(expectedNew, settings.soundSettings.first())
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun roomPresetsRestoreEqualizerAndFiltersWithoutChangingDirectOrComparisonTrim() = runBlocking<Unit> {
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val dao = database.library()
            val current = DspConfig(mode = SoundMode.DIRECT, filters = filters, comparisonTrimDb = -3f)
            dao.upsertPreset(DspPreset("Legacy", "-1,0,2,3,0,-2,0,0,1,4", -2.5f))
            dao.upsertPreset(current.copy(bands = legacyBands, preampDb = -4f, comparisonTrimDb = -1f).toDspPreset("New"))

            val presets = dao.presets().first().associateBy { it.name }
            assertEquals(
                current.copy(bands = legacyBands, preampDb = -2.5f, filters = DspFilters(), preset = "Legacy"),
                presets.getValue("Legacy").applyTo(current),
            )
            assertEquals(
                current.copy(bands = legacyBands, preampDb = -4f, preset = "New"),
                presets.getValue("New").applyTo(current.copy(filters = DspFilters())),
            )
        } finally {
            database.close()
        }
    }

    private suspend fun withSettings(file: File, block: suspend (SettingsRepository) -> Unit) {
        val job = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { file }
        try {
            block(SettingsRepository(store))
        } finally {
            job.cancelAndJoin()
        }
    }
}
