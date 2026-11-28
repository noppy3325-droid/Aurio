package jp.noppy.lumamusic.update

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import jp.noppy.lumamusic.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

internal const val AurioReleasesUrl = "https://github.com/${BuildConfig.UPDATE_REPOSITORY}/releases"
internal const val OpenAurioUpdate = "jp.noppy.lumamusic.OPEN_UPDATE"
private const val CheckIntervalMs = 6 * 60 * 60_000L
private const val DailyMs = 24 * 60 * 60_000L

internal enum class CheckPhase { Idle, Checking, UpToDate, Available, Failed }
internal enum class TransferPhase { Idle, Downloading, Verifying, Ready, Cancelled, Failed }
internal enum class UpdateProblem { Network, RateLimit, Release, Compatibility, Storage, Integrity, Wifi }

internal data class UpdateState(
    val autoCheck: Boolean = false,
    val notifications: Boolean = false,
    val wifiOnly: Boolean = true,
    val latestVersion: String? = null,
    val lastChecked: Long = 0,
    val candidate: UpdateCandidate? = null,
    val check: CheckPhase = CheckPhase.Idle,
    val checkProblem: UpdateProblem? = null,
    val transfer: TransferPhase = TransferPhase.Idle,
    val transferProblem: UpdateProblem? = null,
    val bytes: Long = 0,
    val noticeVersion: String? = null,
) {
    val transferring: Boolean get() = transfer == TransferPhase.Downloading || transfer == TransferPhase.Verifying
}

internal class UpdateManager(private val context: Context) {
    private val repository = ReleaseRepository()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs by lazy { context.getSharedPreferences("release_connect", Context.MODE_PRIVATE) }
    private val directory by lazy { File(context.filesDir, "updates") }
    private val partial by lazy { File(directory, "download.part.apk") }
    private val verified by lazy { File(directory, "verified.apk") }
    private val connectivity by lazy { context.getSystemService(ConnectivityManager::class.java) }
    private val operations = Mutex()
    private val initialization = Mutex()
    private val transferJob = AtomicReference<Job?>()
    private val mutableState = MutableStateFlow(UpdateState())
    val state = mutableState.asStateFlow()
    val installer = UpdateInstaller(context)
    private var initialized = false
    private var etag: String? = null
    val currentVersion: String = BuildConfig.VERSION_NAME
    private val currentCode: Long = BuildConfig.VERSION_CODE.toLong()

    init {
        scope.launch {
            installer.state.collect { installation ->
                if (installation is InstallState.Succeeded) operations.withLock {
                    clearVerified()
                    mutableState.update { it.copy(transfer = TransferPhase.Idle) }
                }
                if (installation is InstallState.Failed && installation.reason == InstallFailure.INVALID_APK) {
                    val digest = state.value.candidate?.sha256
                    operations.withLock {
                        if (installer.state.value == installation && state.value.candidate?.sha256 == digest) {
                            clearVerified()
                            mutableState.update { it.copy(transfer = TransferPhase.Failed, transferProblem = UpdateProblem.Integrity) }
                        }
                    }
                }
            }
        }
    }

    fun foreground() {
        scope.launch {
            installer.refresh()
            load()
            updateNotice()
            check(manual = false)
        }
    }

    fun refresh() { scope.launch { check(manual = true) } }

    /** False requests WorkManager backoff. A duplicate trigger leaves the active operation in charge. */
    suspend fun check(manual: Boolean): Boolean {
        if (!operations.tryLock()) return true
        try {
            load()
            val current = state.value
            val now = System.currentTimeMillis()
            if (!manual && (!current.autoCheck || now - current.lastChecked in 0 until CheckIntervalMs)) return true
            if (prefs.getLong("retry_at", 0) > now) {
                if (manual) setCheckFailure(UpdateProblem.RateLimit)
                return false
            }
            mutableState.update { it.copy(check = CheckPhase.Checking, checkProblem = null) }
            val result = repository.check(currentVersion, currentCode, etag)
            if (!manual && !state.value.autoCheck) {
                mutableState.update { it.copy(check = current.check) }
                return true
            }
            val checkedAt = System.currentTimeMillis()
            when (result) {
                is ReleaseCheck.Fresh -> {
                    val candidate = result.candidate
                    val same = candidate != null && candidate.sha256 == current.candidate?.sha256
                    if (!same) clearVerified()
                    etag = result.etag
                    prefs.edit()
                        .putString("candidate", candidate?.encode())
                        .putString("latest", result.latestVersionName)
                        .putString("etag", etag)
                        .apply()
                    mutableState.update {
                        it.copy(candidate = candidate, latestVersion = result.latestVersionName,
                            transfer = if (same) it.transfer else TransferPhase.Idle,
                            transferProblem = if (same) it.transferProblem else null)
                    }
                }
                ReleaseCheck.NotModified -> if (etag == null || current.lastChecked == 0L) throw IOException("Missing release cache")
            }
            prefs.edit().putLong("checked", checkedAt).remove("retry_at").remove("failures").apply()
            mutableState.update {
                it.copy(lastChecked = checkedAt, check = if (it.candidate == null) CheckPhase.UpToDate else CheckPhase.Available)
            }
            updateNotice()
            return true
        } catch (cancelled: CancellationException) {
            mutableState.update { it.copy(check = CheckPhase.Idle) }
            throw cancelled
        } catch (error: Exception) {
            val problem = classify(error)
            setCheckFailure(problem)
            val failures = (prefs.getInt("failures", 0) + 1).coerceAtMost(8)
            val backoff = (30_000L shl (failures - 1)) + Random.nextLong(0, 15_000L)
            val requested = (error as? ReleaseHttpException)?.retryAfterMillis ?: 0L
            val failedAt = System.currentTimeMillis()
            val pause = maxOf(backoff, requested).coerceAtMost(Long.MAX_VALUE - failedAt)
            prefs.edit().putInt("failures", failures).putLong("retry_at", failedAt + pause).apply()
            return problem == UpdateProblem.Release || problem == UpdateProblem.Compatibility
        } finally {
            operations.unlock()
        }
    }

    fun setAutoCheck(enabled: Boolean) = changeSettings {
        prefs.edit().putBoolean("auto", enabled).apply()
        mutableState.update { it.copy(autoCheck = enabled) }
        if (enabled) scheduleChecks() else WorkManager.getInstance(context).cancelUniqueWork(UpdateCheckWorker.UniqueName)
        if (enabled) scope.launch { check(manual = false) }
        else UpdateNotifier.cancel(context)
    }

    fun setNotifications(enabled: Boolean) = changeSettings {
        prefs.edit().putBoolean("notifications", enabled).apply()
        mutableState.update { it.copy(notifications = enabled) }
        if (enabled) updateNotice() else UpdateNotifier.cancel(context)
    }

    fun setWifiOnly(enabled: Boolean) = changeSettings {
        prefs.edit().putBoolean("wifi", enabled).apply()
        mutableState.update { it.copy(wifiOnly = enabled) }
    }

    fun postpone(skip: Boolean) = changeSettings {
        val version = state.value.candidate?.versionName ?: return@changeSettings
        prefs.edit()
            .putString(if (skip) "skipped" else "postponed", version)
            .putLong("notice_after", if (skip) 0 else System.currentTimeMillis() + DailyMs)
            .apply()
        updateNotice()
        UpdateNotifier.cancel(context)
    }

    fun needsMobileConfirmation(): Boolean = !state.value.wifiOnly && !isWifi()

    fun download(mobileConfirmed: Boolean) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            operations.withLock {
                load()
                val current = state.value
                val candidate = current.candidate ?: return@withLock
                if (current.transfer == TransferPhase.Ready || sessionBusy()) return@withLock
                installer.dismissResult()
                try {
                    requireDownloadNetwork(mobileConfirmed)
                    if ((!directory.isDirectory && !directory.mkdirs()) || directory.usableSpace < candidate.size + 16 * 1024 * 1024) {
                        throw UpdateStorageException()
                    }
                    mutableState.update { it.copy(transfer = TransferPhase.Downloading, bytes = 0, transferProblem = null) }
                    var lastProgress = 0L
                    repository.download(candidate, partial) { bytes ->
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastProgress >= 120 || bytes == candidate.size) {
                            requireDownloadNetwork(mobileConfirmed)
                            lastProgress = now
                            mutableState.update { it.copy(bytes = bytes) }
                        }
                    }
                    mutableState.update { it.copy(transfer = TransferPhase.Verifying, bytes = candidate.size) }
                    ApkVerifier.verify(context, partial, candidate)
                    currentCoroutineContext().ensureActive()
                    Files.move(partial.toPath(), verified.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    check(prefs.edit().putString("verified", candidate.sha256).commit()) { "Could not store verified APK state" }
                    mutableState.update { it.copy(transfer = TransferPhase.Ready) }
                } catch (cancelled: CancellationException) {
                    mutableState.update { it.copy(transfer = TransferPhase.Cancelled, bytes = 0) }
                    throw cancelled
                } catch (error: Exception) {
                    val problem = if (error is IOException && directory.usableSpace < 16 * 1024 * 1024) UpdateProblem.Storage else classify(error)
                    clearVerified()
                    mutableState.update { it.copy(transfer = TransferPhase.Failed, transferProblem = problem, bytes = 0) }
                } finally {
                    partial.delete()
                }
            }
        }
        if (!transferJob.compareAndSet(null, job)) { job.cancel(); return }
        job.invokeOnCompletion { transferJob.compareAndSet(job, null) }
        job.start()
    }

    fun cancelDownload() { transferJob.get()?.cancel() }

    fun install(savePlayback: suspend () -> Unit) {
        scope.launch {
            if (!operations.tryLock()) return@launch
            try {
                load()
                val current = state.value
                val candidate = current.candidate ?: return@launch
                if (current.transfer == TransferPhase.Ready && !sessionBusy()) {
                    installer.start(verified, candidate, savePlayback)
                }
            } finally {
                operations.unlock()
            }
        }
    }

    private fun sessionBusy(): Boolean = when (installer.state.value) {
        InstallState.Preparing, InstallState.AwaitingUserAction, InstallState.Installing -> true
        else -> false
    }

    private fun changeSettings(action: () -> Unit) {
        scope.launch { load(); action() }
    }

    private suspend fun load() = initialization.withLock {
        if (initialized) return@withLock
        val encoded = prefs.getString("candidate", null)
        val stored = encoded?.let {
            runCatching { decodeCandidate(it) }.getOrNull()
        }
        val candidate = stored?.takeIf { it.versionCode > currentCode }
        val latest = prefs.getString("latest", null)
        val checked = if (encoded == null || stored != null) prefs.getLong("checked", 0) else 0
        etag = prefs.getString("etag", null).takeIf { latest != null && checked > 0 }
        val ready = candidate != null && prefs.getString("verified", null) == candidate.sha256 && verified.length() == candidate.size
        partial.delete()
        if (!ready) clearVerified()
        mutableState.value = UpdateState(
            autoCheck = prefs.getBoolean("auto", false),
            notifications = prefs.getBoolean("notifications", false),
            wifiOnly = prefs.getBoolean("wifi", true),
            latestVersion = latest,
            lastChecked = checked,
            candidate = candidate,
            check = if (candidate != null) CheckPhase.Available else if (checked > 0) CheckPhase.UpToDate else CheckPhase.Idle,
            transfer = if (ready) TransferPhase.Ready else TransferPhase.Idle,
        )
        if (state.value.autoCheck) scheduleChecks()
        updateNotice()
        initialized = true
    }

    private fun scheduleChecks() {
        val check = PeriodicWorkRequestBuilder<UpdateCheckWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(UpdateCheckWorker.UniqueName, ExistingPeriodicWorkPolicy.KEEP, check)
    }

    @Synchronized
    private fun updateNotice() {
        val current = state.value
        val version = current.candidate?.versionName
        val show = version != null && prefs.getString("skipped", null) != version &&
            !(prefs.getString("postponed", null) == version && prefs.getLong("notice_after", 0) > System.currentTimeMillis())
        mutableState.update { it.copy(noticeVersion = version.takeIf { show }) }
        if (show && current.notifications && prefs.getString("notified", null) != version &&
            UpdateNotifier.show(context, version)
        ) prefs.edit().putString("notified", version).apply()
    }

    private fun setCheckFailure(problem: UpdateProblem) {
        mutableState.update { it.copy(check = CheckPhase.Failed, checkProblem = problem) }
    }

    private fun isWifi(): Boolean =
        connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

    private fun requireDownloadNetwork(mobileConfirmed: Boolean) {
        if (connectivity.activeNetwork == null) throw IOException("No active network")
        if (!isWifi() && (state.value.wifiOnly || !mobileConfirmed)) throw UpdateWifiException()
    }

    private fun clearVerified() {
        verified.delete()
        prefs.edit().remove("verified").apply()
    }

    private fun classify(error: Exception): UpdateProblem = when (error) {
        is UpdateStorageException -> UpdateProblem.Storage
        is UpdateWifiException -> UpdateProblem.Wifi
        is ReleaseIntegrityException, is ApkVerifier.Failure -> UpdateProblem.Integrity
        is ReleaseCompatibilityException -> UpdateProblem.Compatibility
        is ReleaseMetadataException -> UpdateProblem.Release
        is ReleaseHttpException -> if (error.status == 429 || error.status == 403) UpdateProblem.RateLimit else UpdateProblem.Network
        else -> UpdateProblem.Network
    }
}

private class UpdateStorageException : IOException()
private class UpdateWifiException : IOException()

private fun UpdateCandidate.encode(): String = JSONObject()
    .put("release", releaseId).put("version", versionName).put("code", versionCode).put("minSdk", minSdk)
    .put("abis", JSONArray(abis.toList())).put("name", assetName).put("size", size).put("sha256", sha256)
    .put("url", url).put("notes", notes).toString()

private fun decodeCandidate(encoded: String): UpdateCandidate = JSONObject(encoded).run {
    val abis = getJSONArray("abis")
    UpdateCandidate(getLong("release"), getString("version"), getLong("code"), getInt("minSdk"),
        (0 until abis.length()).map(abis::getString).toSet(), getString("name"), getLong("size"),
        getString("sha256"), getString("url"), getString("notes"))
}
