package jp.noppy.lumamusic.update

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import jp.noppy.lumamusic.LumaApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal enum class InstallFailure { INVALID_APK, SAVE_PLAYBACK, STORAGE, BLOCKED, CONFLICT, INCOMPATIBLE, INTERRUPTED, SYSTEM }

internal sealed interface InstallState {
    data object Idle : InstallState
    data object AwaitingPermission : InstallState
    data object Preparing : InstallState
    data object AwaitingUserAction : InstallState
    data object Installing : InstallState
    data object Cancelled : InstallState
    data class Succeeded(val versionCode: Long) : InstallState
    data class Failed(val reason: InstallFailure) : InstallState
}

internal class UpdateInstaller(context: Context) {
    private val app = context.applicationContext
    private val packageInstaller = app.packageManager.packageInstaller
    private val preferences = app.getSharedPreferences("update_install", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val startRequested = AtomicBoolean()
    private val confirmation = AtomicReference<Confirmation?>(null)
    private val mutableState = MutableStateFlow<InstallState>(InstallState.Idle)
    val state: StateFlow<InstallState> = mutableState.asStateFlow()

    fun canInstall(): Boolean = app.packageManager.canRequestPackageInstalls()

    fun permissionIntent(): Intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.fromParts("package", app.packageName, null),
    )

    suspend fun refresh() = withContext(Dispatchers.IO) {
        mutex.withLock { refreshLocked() }
    }

    suspend fun start(file: File, candidate: UpdateCandidate, savePlayback: suspend () -> Unit) {
        if (!startRequested.compareAndSet(false, true)) return
        try {
            withContext(Dispatchers.IO) {
                mutex.withLock {
                    refreshLocked()
                    if (readSession() != null) return@withLock
                    if (canInstall()) startLocked(file, candidate, savePlayback)
                    else finish(InstallState.AwaitingPermission)
                }
            }
        } finally {
            startRequested.set(false)
        }
    }

    private suspend fun startLocked(file: File, candidate: UpdateCandidate, savePlayback: suspend () -> Unit) {
        mutableState.value = InstallState.Preparing
        var sessionId: Int? = null
        try {
            ApkVerifier.verify(app, file, candidate)
            // Process death before recording the ID can leave an uncommitted session behind.
            for (session in packageInstaller.mySessions) {
                if (!session.isCommitted && session.appPackageName == app.packageName) abandon(session.sessionId)
            }
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(app.packageName)
                setSize(candidate.size)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
                }
            }
            val id = packageInstaller.createSession(params)
            sessionId = id
            val record = InstallSession(id, candidate.versionCode, UUID.randomUUID().toString(), Phase.PREPARING)
            persistSession(record)
            val context = currentCoroutineContext()
            packageInstaller.openSession(id).use { session ->
                openSource(file).use { input ->
                    session.openWrite("base.apk", 0, candidate.size).use { output ->
                        ApkVerifier.verifyBytes(input, candidate.size, candidate.sha256, output) { context.ensureActive() }
                        session.fsync(output)
                    }
                }
                context.ensureActive()
                if (!canInstall()) {
                    finishUncommitted(id, InstallState.AwaitingPermission)
                    return
                }
                try {
                    savePlayback()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    finishUncommitted(id, InstallState.Failed(InstallFailure.SAVE_PLAYBACK))
                    return
                }
                context.ensureActive()
                val callback = checkNotNull(callback(record, PendingIntent.FLAG_UPDATE_CURRENT))
                persistSession(record.copy(phase = Phase.COMMITTED))
                session.commit(callback.intentSender)
            }
            mutableState.value = InstallState.Installing
        } catch (cancelled: CancellationException) {
            finishUncommitted(sessionId, InstallState.Cancelled)
            throw cancelled
        } catch (_: ApkVerifier.Failure) {
            finishUncommitted(sessionId, InstallState.Failed(InstallFailure.INVALID_APK))
        } catch (_: SecurityException) {
            val result = if (canInstall()) InstallState.Failed(InstallFailure.BLOCKED) else InstallState.AwaitingPermission
            finishUncommitted(sessionId, result)
        } catch (_: IOException) {
            finishUncommitted(sessionId, InstallState.Failed(InstallFailure.STORAGE))
        } catch (_: RuntimeException) {
            finishUncommitted(sessionId, InstallState.Failed(InstallFailure.SYSTEM))
        }
    }

    fun confirmationIntent(): Intent? {
        val pending = confirmation.get() ?: return null
        if (pending.handedOff || !confirmation.compareAndSet(pending, pending.copy(handedOff = true))) return null
        scope.launch {
            mutex.withLock {
                val session = readSession()
                if (session?.id == pending.sessionId && session.phase == Phase.AWAITING) {
                    writeSession(session.copy(phase = Phase.HANDED_OFF))
                    mutableState.value = InstallState.Installing
                }
            }
        }
        return Intent(pending.intent)
    }

    fun cancel() {
        scope.launch {
            mutex.withLock {
                val session = readSession()
                if (session == null) {
                    if (mutableState.value == InstallState.AwaitingPermission) finish(InstallState.Cancelled)
                    return@withLock
                }
                if (session.phase == Phase.AWAITING || session.phase == Phase.HANDED_OFF) {
                    if (abandon(session.id)) finish(InstallState.Cancelled)
                    else refreshLocked()
                }
            }
        }
    }

    fun dismissResult() {
        val result = mutableState.value
        if (result !is InstallState.Succeeded && result !is InstallState.Failed && result != InstallState.Cancelled) return
        scope.launch {
            mutex.withLock {
                if (readSession() == null && mutableState.value == result) finish(InstallState.Idle)
            }
        }
    }

    internal suspend fun handleResult(intent: Intent) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val session = readSession() ?: return@withLock
            if (intent.action != RESULT_ACTION || intent.data != session.callbackUri ||
                intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) != session.id ||
                intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)?.let { it != app.packageName } == true
            ) return@withLock

            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val action = intent.userActionIntent()
                    if (action == null) {
                        finishUncommitted(session.id, InstallState.Failed(InstallFailure.SYSTEM))
                    } else if (!writeSession(session.copy(phase = Phase.AWAITING))) {
                        finishUncommitted(session.id, InstallState.Failed(InstallFailure.STORAGE))
                    } else {
                        confirmation.set(Confirmation(session.id, action))
                        mutableState.value = InstallState.AwaitingUserAction
                    }
                }
                PackageInstaller.STATUS_SUCCESS -> {
                    if (installedVersion() == session.versionCode) finish(InstallState.Succeeded(session.versionCode))
                    else {
                        // Keep the target version across a process restart until PackageManager confirms it.
                        writeSession(session.copy(phase = Phase.REPORTED_SUCCESS))
                        mutableState.value = InstallState.Installing
                    }
                }
                PackageInstaller.STATUS_FAILURE_ABORTED -> {
                    val failedVerification = intent.hasExtra(DEVELOPER_VERIFICATION_FAILURE)
                    finish(if (failedVerification) InstallState.Failed(InstallFailure.SYSTEM) else InstallState.Cancelled)
                }
                PackageInstaller.STATUS_FAILURE_BLOCKED -> {
                    finish(if (canInstall()) InstallState.Failed(InstallFailure.BLOCKED) else InstallState.AwaitingPermission)
                }
                PackageInstaller.STATUS_FAILURE_STORAGE -> finish(InstallState.Failed(InstallFailure.STORAGE))
                PackageInstaller.STATUS_FAILURE_CONFLICT -> finish(InstallState.Failed(InstallFailure.CONFLICT))
                PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> finish(InstallState.Failed(InstallFailure.INCOMPATIBLE))
                PackageInstaller.STATUS_FAILURE_INVALID -> finish(InstallState.Failed(InstallFailure.INVALID_APK))
                PackageInstaller.STATUS_FAILURE, PackageInstaller.STATUS_FAILURE_TIMEOUT -> {
                    finish(InstallState.Failed(InstallFailure.SYSTEM))
                }
            }
        }
    }

    private fun refreshLocked() {
        val session = readSession()
        if (session == null) {
            val result = preferences.getString(KEY_RESULT, null)
            val version = preferences.getLong(KEY_VERSION, -1L)
            mutableState.value = when {
                result == RESULT_SUCCESS && installedVersion() == version -> InstallState.Succeeded(version)
                result == RESULT_CANCELLED -> InstallState.Cancelled
                result == RESULT_PERMISSION && !canInstall() -> InstallState.AwaitingPermission
                else -> InstallFailure.entries.firstOrNull { it.name == result }?.let { InstallState.Failed(it) }
                    ?: InstallState.Idle
            }
            return
        }

        if (session.phase != Phase.PREPARING && installedVersion() == session.versionCode) {
            finish(InstallState.Succeeded(session.versionCode))
            return
        }
        val info = packageInstaller.getSessionInfo(session.id)
        val hasConfirmation = confirmation.get()?.let { it.sessionId == session.id && !it.handedOff } == true
        when {
            info == null -> finish(InstallState.Failed(InstallFailure.INTERRUPTED))
            !info.isCommitted -> finishUncommitted(session.id, InstallState.Failed(InstallFailure.INTERRUPTED))
            session.phase == Phase.AWAITING && confirmation.get() == null -> {
                // The system's confirmation Intent cannot be recovered after process death.
                finishUncommitted(session.id, InstallState.Failed(InstallFailure.INTERRUPTED))
            }
            hasConfirmation -> mutableState.value = InstallState.AwaitingUserAction
            else -> mutableState.value = InstallState.Installing
        }
    }

    private fun finishUncommitted(id: Int?, result: InstallState) {
        if (id == null || abandon(id)) finish(result) else mutableState.value = InstallState.Installing
    }

    private fun abandon(id: Int): Boolean = try {
        packageInstaller.abandonSession(id)
        true
    } catch (_: SecurityException) {
        packageInstaller.getSessionInfo(id) == null
    }

    @SuppressLint("ApplySharedPref")
    private fun finish(result: InstallState) {
        readSession()?.let { callback(it, PendingIntent.FLAG_NO_CREATE)?.cancel() }
        confirmation.set(null)
        val edit = preferences.edit().clear()
        when (result) {
            is InstallState.Succeeded -> edit.putString(KEY_RESULT, RESULT_SUCCESS).putLong(KEY_VERSION, result.versionCode)
            is InstallState.Failed -> edit.putString(KEY_RESULT, result.reason.name)
            InstallState.Cancelled -> edit.putString(KEY_RESULT, RESULT_CANCELLED)
            InstallState.AwaitingPermission -> edit.putString(KEY_RESULT, RESULT_PERMISSION)
            else -> Unit
        }
        // Persist before publishing because a self-update can terminate this process immediately.
        edit.commit()
        mutableState.value = result
    }

    private fun readSession(): InstallSession? {
        val id = preferences.getInt(KEY_SESSION, -1)
        val version = preferences.getLong(KEY_VERSION, -1L)
        val token = preferences.getString(KEY_TOKEN, null) ?: return null
        val phase = Phase.entries.getOrNull(preferences.getInt(KEY_PHASE, -1)) ?: return null
        return if (id >= 0 && version > 0) InstallSession(id, version, token, phase) else null
    }

    @SuppressLint("ApplySharedPref")
    private fun writeSession(session: InstallSession): Boolean =
        preferences.edit().remove(KEY_RESULT).putInt(KEY_SESSION, session.id)
            .putLong(KEY_VERSION, session.versionCode).putString(KEY_TOKEN, session.token)
            .putInt(KEY_PHASE, session.phase.ordinal).commit()

    private fun persistSession(session: InstallSession) {
        if (!writeSession(session)) throw IOException("Could not persist the update install session")
    }

    @Suppress("DEPRECATION")
    private fun installedVersion(): Long? = try {
        app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    @Suppress("DEPRECATION")
    private fun Intent.userActionIntent(): Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
    } else {
        getParcelableExtra(Intent.EXTRA_INTENT)
    }

    private fun callback(session: InstallSession, flags: Int): PendingIntent? = PendingIntent.getBroadcast(
        app,
        session.id,
        Intent(app, UpdateInstallReceiver::class.java).setAction(RESULT_ACTION).setData(session.callbackUri),
        // PackageInstaller attaches the status to the explicit PendingIntent.
        flags or PendingIntent.FLAG_MUTABLE,
    )

    private fun openSource(file: File) = try {
        file.inputStream()
    } catch (failure: FileNotFoundException) {
        throw ApkVerifier.Failure(ApkVerifier.Reason.UNREADABLE, failure)
    }

    private data class Confirmation(val sessionId: Int, val intent: Intent, val handedOff: Boolean = false)
    private data class InstallSession(val id: Int, val versionCode: Long, val token: String, val phase: Phase) {
        val callbackUri: Uri get() = Uri.fromParts("aurio-install", token, null)
    }
    private enum class Phase { PREPARING, COMMITTED, AWAITING, HANDED_OFF, REPORTED_SUCCESS }

    companion object {
        internal const val RESULT_ACTION = "jp.noppy.lumamusic.update.INSTALL_RESULT"
        private const val DEVELOPER_VERIFICATION_FAILURE = "android.content.pm.extra.DEVELOPER_VERIFICATION_FAILURE_REASON"
        private const val KEY_SESSION = "session"
        private const val KEY_VERSION = "version"
        private const val KEY_TOKEN = "token"
        private const val KEY_PHASE = "phase"
        private const val KEY_RESULT = "result"
        private const val RESULT_SUCCESS = "success"
        private const val RESULT_CANCELLED = "cancelled"
        private const val RESULT_PERMISSION = "permission"
    }
}

class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != UpdateInstaller.RESULT_ACTION) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                (context.applicationContext as LumaApp).updates.installer.handleResult(intent)
            } finally {
                pending.finish()
            }
        }
    }
}
