package pixl.rec.ui.settings

import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pixl.rec.R
import pixl.rec.core.audio.ConcurrentMicrophoneTexts
import pixl.rec.core.keepalive.KeepAliveChecks
import pixl.rec.core.keepalive.KeepAliveIntents
import pixl.rec.core.keepalive.KeepAliveStatus
import pixl.rec.core.keepalive.KillCause
import pixl.rec.service.ConcurrentMicrophoneService
import pixl.rec.ui.components.SectionCard
import pixl.rec.ui.theme.CyberYellow
import pixl.rec.ui.theme.TextMuted
import pixl.rec.ui.theme.TextPrimary
import pixl.rec.ui.theme.TextSecondary
import pixl.rec.ui.theme.ToxicLime

private enum class CheckState { OK, ACTION, MANUAL }

/**
 * Checklist against the system closing REC. Items Android can report are checked again on every
 * return to this screen; vendor switches without an API are shown as steps to confirm by hand.
 */
@Composable
internal fun KeepAliveCard(concurrentMicrophone: Boolean) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumeCount by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeCount++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var status by remember { mutableStateOf<KeepAliveStatus?>(null) }
    LaunchedEffect(resumeCount) {
        if (resumeCount == 0) return@LaunchedEffect // The observer replays ON_RESUME at once on a visible screen.
        status = withContext(Dispatchers.IO) { KeepAliveChecks.read(context.applicationContext) }
    }
    val assistanceConnected by ConcurrentMicrophoneService.connected.collectAsState()
    val pkg = context.packageName

    SectionCard(
        title = stringResource(R.string.keep_alive_title),
        titleTag = stringResource(R.string.keep_alive_tag)
    ) {
        Text(stringResource(R.string.keep_alive_intro), color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        val current = status ?: return@SectionCard // First read takes a few milliseconds.

        CheckRow(
            state = if (current.batteryUnrestricted) CheckState.OK else CheckState.ACTION,
            title = stringResource(R.string.keep_alive_battery_title),
            actionLabel = stringResource(R.string.keep_alive_battery_fix).takeUnless { current.batteryUnrestricted },
            onAction = { open(context, KeepAliveIntents.battery(pkg)) }
        )
        CheckRow(
            state = if (current.backgroundRestricted) CheckState.ACTION else CheckState.OK,
            title = stringResource(R.string.keep_alive_background_title),
            actionLabel = stringResource(R.string.keep_alive_open_app_settings).takeIf { current.backgroundRestricted },
            onAction = { open(context, KeepAliveIntents.appSettings(pkg)) }
        )
        if (concurrentMicrophone) {
            CheckRow(
                state = if (assistanceConnected) CheckState.OK else CheckState.ACTION,
                title = stringResource(R.string.keep_alive_assistance_title, stringResource(R.string.concurrent_mic_title)),
                detail = if (assistanceConnected) null
                else ConcurrentMicrophoneTexts.disconnectedHint(context, current.assistanceEnabledInSettings),
                actionLabel = stringResource(R.string.concurrent_mic_open_settings).takeUnless { assistanceConnected },
                onAction = { open(context, KeepAliveIntents.accessibility()) }
            )
        }
        current.lastKill?.let { kill ->
            val (cause, advice) = when (kill.cause) {
                KillCause.RECENTS_CLEAN -> R.string.keep_alive_cause_recents to R.string.keep_alive_advice_recents
                KillCause.GAME_CLEAN -> R.string.keep_alive_cause_game to R.string.keep_alive_advice_game
                KillCause.BACKGROUND_CLEAN -> R.string.keep_alive_cause_background to R.string.keep_alive_advice_background
                KillCause.LOW_MEMORY -> R.string.keep_alive_cause_low_memory to R.string.keep_alive_advice_low_memory
            }
            val time = DateUtils.formatDateTime(
                context, kill.timestampMs,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH
            )
            CheckRow(
                state = CheckState.ACTION,
                stateLabel = stringResource(R.string.keep_alive_last_closed),
                title = stringResource(cause),
                detail = stringResource(R.string.keep_alive_last_kill_detail, time, stringResource(advice))
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        if (current.isXiaomi) {
            Text(stringResource(R.string.keep_alive_xiaomi_header), color = TextPrimary, style = MaterialTheme.typography.labelLarge)
            CheckRow(
                state = CheckState.MANUAL,
                title = stringResource(R.string.keep_alive_xiaomi_autostart),
                actionLabel = stringResource(R.string.keep_alive_open),
                onAction = { open(context, KeepAliveIntents.xiaomiAutostart(pkg)) }
            )
            CheckRow(
                state = CheckState.MANUAL,
                title = stringResource(R.string.keep_alive_xiaomi_battery_saver),
                actionLabel = stringResource(R.string.keep_alive_open),
                onAction = {
                    open(context, KeepAliveIntents.xiaomiBatterySaver(pkg, context.getString(R.string.app_name)))
                }
            )
            CheckRow(state = CheckState.MANUAL, title = stringResource(R.string.keep_alive_xiaomi_recents_lock))
            CheckRow(state = CheckState.MANUAL, title = stringResource(R.string.keep_alive_xiaomi_game_turbo))
        } else {
            CheckRow(state = CheckState.MANUAL, title = stringResource(R.string.keep_alive_other_makers))
        }
    }
}

@Composable
private fun CheckRow(
    state: CheckState,
    title: String,
    detail: String? = null,
    stateLabel: String? = null,
    actionLabel: String? = null,
    onAction: () -> Unit = {}
) {
    val (label, color) = when (state) {
        CheckState.OK -> stringResource(R.string.keep_alive_state_ok) to ToxicLime
        CheckState.ACTION -> stringResource(R.string.keep_alive_state_action) to CyberYellow
        CheckState.MANUAL -> stringResource(R.string.keep_alive_state_manual) to TextMuted
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stateLabel ?: label, color = color, style = MaterialTheme.typography.labelSmall)
            Text(title, color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
            detail?.let { Text(it, color = TextMuted, style = MaterialTheme.typography.bodySmall) }
        }
        actionLabel?.let { TextButton(onClick = onAction) { Text(it) } }
    }
}

private fun open(context: Context, candidates: List<Intent>) {
    if (!KeepAliveIntents.launch(context, candidates)) {
        Toast.makeText(context, R.string.keep_alive_open_failed, Toast.LENGTH_LONG).show()
    }
}
