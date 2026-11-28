package jp.noppy.lumamusic.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import jp.noppy.lumamusic.R
import jp.noppy.lumamusic.update.AurioReleasesUrl
import jp.noppy.lumamusic.update.CheckPhase
import jp.noppy.lumamusic.update.InstallFailure
import jp.noppy.lumamusic.update.InstallState
import jp.noppy.lumamusic.update.TransferPhase
import jp.noppy.lumamusic.update.UpdateManager
import jp.noppy.lumamusic.update.UpdateNotifier
import jp.noppy.lumamusic.update.UpdateProblem
import java.text.DateFormat
import java.util.Date

@Composable
internal fun UpdateScreen(updates: UpdateManager, savePlayback: suspend () -> Unit, close: () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val state by updates.state.collectAsStateWithLifecycle()
    val installer = updates.installer
    val install by installer.state.collectAsStateWithLifecycle()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    var confirmMobile by rememberSaveable { mutableStateOf(false) }
    var confirmInstall by rememberSaveable { mutableStateOf(false) }
    var installingFromHere by remember { mutableStateOf(false) }
    var externalError by remember { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) updates.setNotifications(true)
    }
    val launchExternal = { intent: Intent ->
        try {
            activity?.startActivity(intent)
            externalError = activity == null
        } catch (_: ActivityNotFoundException) {
            externalError = true
        } catch (_: SecurityException) {
            externalError = true
        }
    }
    val openConfirmation = {
        installer.confirmationIntent()?.let(launchExternal)
        installingFromHere = false
    }
    LaunchedEffect(install, lifecycle, installingFromHere) {
        if (installingFromHere && install == InstallState.AwaitingUserAction && lifecycle == Lifecycle.State.RESUMED) {
            openConfirmation()
        }
    }
    val busy = install == InstallState.Preparing || install == InstallState.Installing || install == InstallState.AwaitingUserAction
    val candidate = state.candidate
    val lastChecked = remember(state.lastChecked) {
        state.lastChecked.takeIf { it > 0 }?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        PageHeading(stringResource(R.string.update_title), close)
        Column(Modifier.padding(horizontal = LumaSpacing.Page), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsGroup {
                Text(stringResource(R.string.update_current, updates.currentVersion), style = MaterialTheme.typography.titleMedium)
                val unknown = stringResource(R.string.update_not_checked)
                Text(stringResource(R.string.update_latest, state.latestVersion ?: unknown))
                Text(stringResource(R.string.update_last_checked, lastChecked ?: unknown), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                when (state.check) {
                    CheckPhase.Idle, CheckPhase.Available -> Unit
                    CheckPhase.Checking -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    CheckPhase.UpToDate -> Text(stringResource(R.string.update_up_to_date))
                    CheckPhase.Failed -> Text(stringResource(updateProblemText(state.checkProblem)), color = MaterialTheme.colorScheme.error)
                }
                OutlinedButton(updates::refresh, enabled = state.check != CheckPhase.Checking && !state.transferring && !busy) {
                    Text(stringResource(R.string.update_check))
                }
            }

            if (candidate != null) SettingsGroup {
                val size = Formatter.formatFileSize(context, candidate.size)
                Text(stringResource(R.string.update_notification_title, candidate.versionName), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.update_size, size))
                if (candidate.notes.isNotBlank()) {
                    Text(stringResource(R.string.update_notes), style = MaterialTheme.typography.titleSmall)
                    Text(candidate.notes, style = MaterialTheme.typography.bodyMedium)
                }
                when (state.transfer) {
                    TransferPhase.Downloading -> {
                        val progress = (state.bytes.toDouble() / candidate.size).toFloat().coerceIn(0f, 1f)
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        Text(stringResource(R.string.update_received, Formatter.formatFileSize(context, state.bytes), size))
                        TextButton(updates::cancelDownload) { Text(stringResource(R.string.update_cancel)) }
                    }
                    TransferPhase.Verifying -> {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(stringResource(R.string.update_verifying))
                        TextButton(updates::cancelDownload) { Text(stringResource(R.string.update_cancel)) }
                    }
                    TransferPhase.Ready -> {
                        Button({ confirmInstall = true }, enabled = !busy && state.check != CheckPhase.Checking) {
                            Text(stringResource(R.string.update_install))
                        }
                    }
                    TransferPhase.Idle, TransferPhase.Cancelled, TransferPhase.Failed -> {
                        if (state.transfer == TransferPhase.Failed) Text(stringResource(updateProblemText(state.transferProblem)),
                            color = MaterialTheme.colorScheme.error)
                        if (state.transfer == TransferPhase.Cancelled) Text(stringResource(R.string.update_download_cancelled))
                        Button(
                            onClick = {
                                if (updates.needsMobileConfirmation()) confirmMobile = true
                                else updates.download(mobileConfirmed = false)
                            },
                            enabled = state.check != CheckPhase.Checking && !busy,
                        ) {
                            Text(stringResource(if (state.transfer == TransferPhase.Idle) R.string.update_download else R.string.update_retry_download))
                        }
                    }
                }
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton({ updates.postpone(skip = false) }) { Text(stringResource(R.string.update_later)) }
                    TextButton({ updates.postpone(skip = true) }) { Text(stringResource(R.string.update_skip)) }
                }
            }

            if (install != InstallState.Idle) SettingsGroup {
                when (val result = install) {
                    InstallState.Idle -> Unit
                    InstallState.AwaitingPermission -> {
                        Text(stringResource(R.string.update_unknown_sources))
                        TextButton({ launchExternal(installer.permissionIntent()) }) { Text(stringResource(R.string.update_device_settings)) }
                        TextButton(installer::cancel) { Text(stringResource(R.string.update_cancel)) }
                    }
                    InstallState.Preparing -> Text(stringResource(R.string.update_preparing))
                    InstallState.AwaitingUserAction -> {
                        TextButton(openConfirmation) { Text(stringResource(R.string.update_open_confirmation)) }
                        TextButton(installer::cancel) { Text(stringResource(R.string.update_cancel)) }
                    }
                    InstallState.Installing -> {
                        Text(stringResource(R.string.update_installing))
                        if (externalError) TextButton(installer::cancel) { Text(stringResource(R.string.update_cancel)) }
                    }
                    InstallState.Cancelled -> Text(stringResource(R.string.update_install_cancelled))
                    is InstallState.Succeeded -> Text(stringResource(R.string.update_installed, updates.currentVersion))
                    is InstallState.Failed -> Text(stringResource(installFailureText(result.reason)), color = MaterialTheme.colorScheme.error)
                }
            }

            SettingsGroup {
                UpdateToggle(stringResource(R.string.update_auto_check), state.autoCheck, updates::setAutoCheck)
                UpdateToggle(stringResource(R.string.update_notifications), state.notifications) { enabled ->
                    updates.setNotifications(enabled)
                    if (enabled && Build.VERSION.SDK_INT >= 33 && !UpdateNotifier.allowed(context)) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                if (state.notifications && !UpdateNotifier.allowed(context)) {
                    TextButton({
                        launchExternal(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                    }) { Text(stringResource(R.string.update_notification_settings)) }
                }
                UpdateToggle(stringResource(R.string.update_wifi_only), state.wifiOnly, updates::setWifiOnly)
            }
            TextButton({ launchExternal(Intent(Intent.ACTION_VIEW, Uri.parse(AurioReleasesUrl))) }) {
                Text(stringResource(R.string.update_github))
            }
            if (externalError) Text(stringResource(R.string.update_cannot_open), color = MaterialTheme.colorScheme.error)
        }
    }

    if (candidate != null && confirmMobile) AlertDialog(
        onDismissRequest = { confirmMobile = false },
        title = { Text(stringResource(R.string.update_mobile_title)) },
        text = { Text(stringResource(R.string.update_mobile_explanation, Formatter.formatFileSize(context, candidate.size))) },
        confirmButton = {
            TextButton({ confirmMobile = false; updates.download(mobileConfirmed = true) }) {
                Text(stringResource(R.string.update_download))
            }
        },
        dismissButton = { TextButton({ confirmMobile = false }) { Text(stringResource(R.string.update_cancel)) } },
    )
    if (candidate != null && confirmInstall) AlertDialog(
        onDismissRequest = { confirmInstall = false },
        title = { Text(stringResource(R.string.update_install_title, candidate.versionName)) },
        text = { Text(stringResource(R.string.update_install_explanation)) },
        confirmButton = {
            TextButton({
                confirmInstall = false
                installingFromHere = true
                updates.install(savePlayback)
            }) { Text(stringResource(R.string.update_install)) }
        },
        dismissButton = { TextButton({ confirmInstall = false }) { Text(stringResource(R.string.update_cancel)) } },
    )
}

@Composable
private fun UpdateToggle(label: String, checked: Boolean, set: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(checked, role = Role.Switch, onValueChange = set),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onCheckedChange = null)
    }
}

private fun updateProblemText(problem: UpdateProblem?): Int = when (problem) {
    UpdateProblem.RateLimit -> R.string.update_problem_rate
    UpdateProblem.Release -> R.string.update_problem_release
    UpdateProblem.Compatibility -> R.string.update_problem_compatibility
    UpdateProblem.Storage -> R.string.update_problem_storage
    UpdateProblem.Integrity -> R.string.update_problem_integrity
    UpdateProblem.Wifi -> R.string.update_problem_wifi
    UpdateProblem.Network, null -> R.string.update_problem_network
}

private fun installFailureText(failure: InstallFailure): Int = when (failure) {
    InstallFailure.INVALID_APK -> R.string.update_problem_integrity
    InstallFailure.SAVE_PLAYBACK -> R.string.update_problem_playback
    InstallFailure.STORAGE -> R.string.update_problem_storage
    InstallFailure.BLOCKED -> R.string.update_unknown_sources
    InstallFailure.CONFLICT -> R.string.update_problem_conflict
    InstallFailure.INCOMPATIBLE -> R.string.update_problem_compatibility
    InstallFailure.INTERRUPTED, InstallFailure.SYSTEM -> R.string.update_problem_install
}
