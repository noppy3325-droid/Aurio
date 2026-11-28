package jp.noppy.lumamusic

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageInstaller
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import jp.noppy.lumamusic.update.ApkVerifier
import jp.noppy.lumamusic.update.InstallState
import jp.noppy.lumamusic.update.UpdateCandidate
import jp.noppy.lumamusic.update.UpdateCheckWorker
import jp.noppy.lumamusic.update.UpdateInstallReceiver
import jp.noppy.lumamusic.update.UpdateInstaller
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class UpdateSecurityDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun automaticCheckOptInAddsAndRemovesPeriodicWork() = runBlocking {
        val updates = (context.applicationContext as LumaApp).updates
        val workManager = WorkManager.getInstance(context)
        val original = updates.state.value.autoCheck
        suspend fun awaitSchedule(enabled: Boolean) {
            withTimeout(12_000) {
                while (true) {
                    val infos = workManager.getWorkInfosForUniqueWork(UpdateCheckWorker.UniqueName).get(5, TimeUnit.SECONDS)
                    if (updates.state.value.autoCheck == enabled && infos.any { !it.state.isFinished } == enabled) return@withTimeout
                    delay(100)
                }
            }
        }
        try {
            updates.setAutoCheck(true)
            awaitSchedule(true)
            updates.setAutoCheck(false)
            awaitSchedule(false)
        } finally {
            updates.setAutoCheck(original)
            awaitSchedule(original)
        }
    }

    @Test fun theInstalledSignedArchiveCannotBeReinstalledAsAnUpdate() {
        val apk = File(context.applicationInfo.sourceDir)
        val digest = MessageDigest.getInstance("SHA-256")
        apk.inputStream().buffered().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val abis = ZipFile(apk).use { archive ->
            archive.entries().asSequence().map { it.name.split('/') }
                .filter { it.size == 3 && it[0] == "lib" && it[2].endsWith(".so") }.map { it[1] }.toSet()
        }
        val candidate = UpdateCandidate(1, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong(),
            context.applicationInfo.minSdkVersion, abis.ifEmpty { setOf("universal") },
            "Aurio-${BuildConfig.VERSION_NAME}.apk", apk.length(), digest.digest().joinToString("") { "%02x".format(it) }, "", "")
        val same = assertThrows(ApkVerifier.Failure::class.java) { ApkVerifier.verify(context, apk, candidate) }
        assertEquals(ApkVerifier.Reason.VERSION, same.reason)
        val changed = assertThrows(ApkVerifier.Failure::class.java) {
            ApkVerifier.verify(context, apk, candidate.copy(versionCode = candidate.versionCode + 1))
        }
        assertEquals(ApkVerifier.Reason.VERSION, changed.reason)
    }

    @Suppress("DEPRECATION")
    @Test fun permissionIsRequestedExplicitlyAndExternalInstallResultsDoNotAdvanceState() = runBlocking<Unit> {
        val installer = UpdateInstaller(context)
        assumeFalse(installer.canInstall())
        val receiver = context.packageManager.getReceiverInfo(ComponentName(context, UpdateInstallReceiver::class.java), 0)
        assertFalse(receiver.exported)
        val candidate = UpdateCandidate(1, "2.0.0", 100, 29, setOf("universal"),
            "Aurio-2.0.0.apk", 1, "0".repeat(64), "", "")
        installer.start(File(context.cacheDir, "unused-update-fixture.apk"), candidate) {
            error("Playback save must wait until the app has install permission")
        }
        try {
            assertEquals(InstallState.AwaitingPermission, installer.state.value)
            assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, installer.permissionIntent().action)
            assertEquals("package:${context.packageName}", installer.permissionIntent().dataString)
            installer.handleResult(Intent("jp.noppy.lumamusic.UPDATE_RESULT")
                .putExtra(PackageInstaller.EXTRA_SESSION_ID, 7)
                .putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_SUCCESS))
            assertEquals(InstallState.AwaitingPermission, installer.state.value)
            installer.cancel()
            withTimeout(5_000) { installer.state.first { it == InstallState.Cancelled } }
        } finally {
            installer.dismissResult()
        }
    }
}
