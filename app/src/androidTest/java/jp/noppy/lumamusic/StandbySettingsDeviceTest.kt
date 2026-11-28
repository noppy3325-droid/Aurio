package jp.noppy.lumamusic

import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import jp.noppy.lumamusic.data.SettingsRepository
import jp.noppy.lumamusic.ui.StandbyWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class StandbySettingsDeviceTest {
    @Test
    fun cleanSettingsUseSystemBrightnessAndStoredChoicesSurvive() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "standby-${UUID.randomUUID()}.preferences_pb")
        val job = SupervisorJob()
        val data = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { file }
        val repository = SettingsRepository(data)
        try {
            val initial = repository.preferences.first()
            assertFalse(initial.dimAfter)
            assertFalse(initial.standbyFixedBrightness)
            repository.number("brightness", .62f)
            repository.boolean("dim", true)
            val legacy = repository.preferences.first()
            assertTrue(legacy.dimAfter)
            assertTrue(legacy.standbyFixedBrightness)
            assertEquals(.62f, legacy.brightness, .001f)
            repository.boolean("standbyFixedBrightness", false)
            val overridden = repository.preferences.first()
            assertFalse(overridden.standbyFixedBrightness)
            assertTrue(overridden.dimAfter)
            assertEquals(.62f, overridden.brightness, .001f)
        } finally {
            job.cancelAndJoin()
            file.delete()
        }
    }

    @Test
    fun windowRestoresBrightnessAndKeepAwakeOnPauseAndWhenDisabled() {
        val brightness = mutableStateOf<Float?>(null)
        val keepAwake = mutableStateOf(false)
        val keepOn = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        ActivityScenario.launch(AurioActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.window.clearFlags(keepOn)
                activity.window.attributes = activity.window.attributes.apply { screenBrightness = .47f }
                activity.setContent { StandbyWindow(keepAwake.value, brightness.value) }
            }
            fun checkWindow(expected: Float, awake: Boolean) {
                val timeout = SystemClock.uptimeMillis() + 4_000
                while (SystemClock.uptimeMillis() < timeout) {
                    var matched = false
                    scenario.onActivity { activity ->
                        val attributes = activity.window.attributes
                        matched = kotlin.math.abs(attributes.screenBrightness - expected) < .001f &&
                            (attributes.flags and keepOn != 0) == awake
                    }
                    if (matched) return
                    SystemClock.sleep(50)
                }
                fail("Expected standby brightness $expected and keep-awake=$awake")
            }
            checkWindow(.47f, false)
            scenario.onActivity { brightness.value = .24f; keepAwake.value = true }
            checkWindow(.24f, true)
            scenario.moveToState(Lifecycle.State.STARTED)
            checkWindow(.47f, false)
            scenario.moveToState(Lifecycle.State.RESUMED)
            checkWindow(.24f, true)
            scenario.onActivity { brightness.value = null; keepAwake.value = false }
            checkWindow(.47f, false)
            scenario.onActivity {
                it.window.addFlags(keepOn)
                brightness.value = .24f
                keepAwake.value = true
            }
            checkWindow(.24f, true)
            scenario.onActivity { it.setContent {} }
            checkWindow(.47f, true)
        }
    }
}
