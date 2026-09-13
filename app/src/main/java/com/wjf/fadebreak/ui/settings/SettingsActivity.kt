package com.wjf.fadebreak.ui.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.wjf.fadebreak.R
import com.wjf.fadebreak.core.BreakSettings
import com.wjf.fadebreak.core.DebugLog
import com.wjf.fadebreak.data.SettingsBridge
import com.wjf.fadebreak.diagnostics.ProcessExit
import com.wjf.fadebreak.track.ControlReceiver
import com.wjf.fadebreak.ui.apps.AppListActivity
import com.wjf.fadebreak.ui.image.AdjustBackgroundActivity
import com.wjf.fadebreak.ui.theme.FadeBreakTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Grace period before flagging a not-yet-connected service as broken. */
private const val SERVICE_RECHECK_MS = 1_200L

data class UiStatus(
    val overlay: Boolean = false,
    val accessibility: Boolean = false,
    val battery: Boolean = false
)

private data class DiagnosticIssue(
    val title: String,
    val actionLabel: String,
    val onAction: () -> Unit
)

class SettingsActivity : ComponentActivity() {

    private lateinit var repository: SettingsBridge
    private val status = mutableStateOf(UiStatus())
    private val diagnosticsOpen = mutableStateOf(false)
    private val issues = mutableStateOf<List<DiagnosticIssue>>(emptyList())
    private var exitReport: ProcessExit.Report? = null
    private var promptedOnLaunch = false

    private val imagePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                startActivity(
                    Intent(this, AdjustBackgroundActivity::class.java)
                        .putExtra(AdjustBackgroundActivity.EXTRA_URI, uri.toString())
                )
            }
        }

    private val folderPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                }
                lifecycleScope.launch {
                    val current = repository.current()
                    repository.set(
                        current.copy(
                            bgFolderUri = uri.toString(),
                            bgImageUri = "",
                            bgFolderIndex = 0,
                            bgImageEnabled = true
                        )
                    )
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = SettingsBridge(applicationContext)

        DebugLog.init(applicationContext)
        DebugLog.d("SettingsActivity created")
        exitReport = ProcessExit.consumeUnreported(applicationContext)

        setContent {
            FadeBreakTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        val settings by repository.settings.collectAsState(
                            initial = BreakSettings.defaults()
                        )
                        SettingsScreen(
                            settings = settings,
                            status = status.value,
                            onSettingsChange = { updated ->
                                lifecycleScope.launch { repository.set(updated) }
                            },
                            onToggleEnabled = {
                                lifecycleScope.launch {
                                    val current = repository.current()
                                    repository.set(current.copy(enabled = !current.enabled))
                                }
                            },
                            onPreview = ::previewOverlay,
                            onAccessibility = { openSystem(Settings.ACTION_ACCESSIBILITY_SETTINGS) },
                            onOverlay = ::openOverlaySettings,
                            onBattery = ::openBatterySettings,
                            onWhitelist = ::openWhitelist,
                            onPickImage = { imagePicker.launch(arrayOf("image/*")) },
                            onPickFolder = { folderPicker.launch(null) },
                            onStartupManager = ::openStartupManager,
                            onShowDiagnostics = { diagnosticsOpen.value = true }
                        )
                        if (diagnosticsOpen.value) {
                            DiagnosticsDialog(
                                issues = issues.value,
                                onDismiss = { diagnosticsOpen.value = false }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        status.value = readStatus()
        refreshDiagnostics()
    }

    /**
     * Recompute the issue list. Permissions can change after the user returns from a
     * system settings page, so this runs on every resume; the dialog itself only
     * appears once per launch, and only when something is actually wrong.
     */
    private fun refreshDiagnostics() {
        lifecycleScope.launch {
            // Only the accessibility service can be "dead"; when the permission is off
            // we already report that. Retry once so a service that is merely restarting
            // is not flagged as broken.
            val alive = if (!status.value.accessibility) {
                false
            } else if (repository.isServiceAlive()) {
                true
            } else {
                delay(SERVICE_RECHECK_MS)
                repository.isServiceAlive()
            }
            issues.value = buildIssues(alive)
            if (!promptedOnLaunch) {
                promptedOnLaunch = true
                diagnosticsOpen.value = issues.value.isNotEmpty()
            }
        }
    }

    private fun buildIssues(serviceAlive: Boolean): List<DiagnosticIssue> {
        val list = mutableListOf<DiagnosticIssue>()
        if (!status.value.accessibility) {
            list += DiagnosticIssue(
                title = getString(R.string.diag_accessibility_title),
                actionLabel = getString(R.string.diag_action_accessibility),
                onAction = { openSystem(Settings.ACTION_ACCESSIBILITY_SETTINGS) }
            )
        } else if (!serviceAlive) {
            list += DiagnosticIssue(
                title = exitReport?.let { getString(R.string.diag_service_killed, it.reason) }
                    ?: getString(R.string.diag_service_stopped),
                actionLabel = getString(R.string.diag_action_service),
                onAction = { openSystem(Settings.ACTION_ACCESSIBILITY_SETTINGS) }
            )
        }
        if (!status.value.overlay) {
            list += DiagnosticIssue(
                title = getString(R.string.diag_overlay_title),
                actionLabel = getString(R.string.diag_action_overlay),
                onAction = { openOverlaySettings() }
            )
        }
        return list
    }

    private fun previewOverlay() {
        when {
            !isAccessibilityEnabled() -> openSystem(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            !Settings.canDrawOverlays(this) -> openOverlaySettings()
            else -> runCatching {
                sendBroadcast(
                    Intent(ControlReceiver.ACTION_PREVIEW).setPackage(packageName)
                )
            }
        }
    }

    private fun readStatus(): UiStatus = UiStatus(
        overlay = Settings.canDrawOverlays(this),
        accessibility = isAccessibilityEnabled(),
        battery = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(packageName)
    )

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val component = ComponentName(
            this,
            "com.wjf.fadebreak.track.ActivityAccessibilityService"
        ).flattenToString()
        return enabled.split(':').any { it.equals(component, ignoreCase = true) }
    }

    private fun openSystem(action: String) {
        runCatching { startActivity(Intent(action)) }
    }

    private fun openOverlaySettings() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }

    private fun openBatterySettings() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }

    private fun openWhitelist() {
        runCatching { startActivity(Intent(this, AppListActivity::class.java)) }
    }

    /** Opens the ROM "app launch management" list; falls back to the app info page. */
    private fun openStartupManager() {
        val candidates = listOf(
            Intent().setComponent(
                ComponentName(
                    "com.hihonor.systemmanager",
                    "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                )
            ),
            Intent("hihonor.intent.action.HSM_STARTUPAPP_MANAGER"),
            Intent().setComponent(
                ComponentName(
                    "com.hihonor.systemmanager",
                    "com.hihonor.systemmanager.appcontrol.activity.StartupAppControlActivity"
                )
            ),
            Intent().setComponent(
                ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                )
            )
        )
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { startActivity(intent) }.isSuccess) return
        }
        openAppDetails()
    }

    private fun openAppDetails() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }
}

@Composable
private fun SettingsScreen(
    settings: BreakSettings,
    status: UiStatus,
    onSettingsChange: (BreakSettings) -> Unit,
    onToggleEnabled: () -> Unit,
    onPreview: () -> Unit,
    onAccessibility: () -> Unit,
    onOverlay: () -> Unit,
    onBattery: () -> Unit,
    onWhitelist: () -> Unit,
    onPickImage: () -> Unit,
    onPickFolder: () -> Unit,
    onStartupManager: () -> Unit,
    onShowDiagnostics: () -> Unit
) {
    var advancedExpanded by remember { mutableStateOf(false) }
    var pickDialog by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.titleLarge
            )
            Switch(
                checked = settings.enabled,
                onCheckedChange = { onToggleEnabled() }
            )
        }

        StatusBanner(
            healthy = status.accessibility && status.overlay,
            onClick = onShowDiagnostics
        )

        Text(
            text = stringResource(R.string.settings_section_params),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 12.dp)
        )

        SettingSlider(
            label = stringResource(R.string.param_timeout),
            valueText = { v -> "${v.toInt()} 分钟" },
            value = (settings.timeoutMs / 60_000).toFloat(),
            range = 0f..60f,
            step = 1f,
            onChange = { onSettingsChange(settings.copy(timeoutMs = it.toLong() * 60_000)) }
        )
        SettingSlider(
            label = stringResource(R.string.param_min_break),
            valueText = { v -> "${v.toInt()} 秒" },
            value = (settings.minBreakMs / 1000).toFloat(),
            range = 0f..120f,
            step = 5f,
            onChange = { onSettingsChange(settings.copy(minBreakMs = it.toLong() * 1000)) }
        )
        SettingSlider(
            label = stringResource(R.string.param_opacity),
            valueText = { v -> "${v.toInt()}%" },
            value = settings.maxOpacity * 100,
            range = 0f..100f,
            step = 1f,
            onChange = { onSettingsChange(settings.copy(maxOpacity = it / 100f)) }
        )
        SettingSwitch(
            label = stringResource(R.string.param_bg_image),
            checked = settings.bgImageEnabled,
            onChange = { onSettingsChange(settings.copy(bgImageEnabled = it)) }
        )
        if (settings.bgImageEnabled) {
            ActionButton(R.string.settings_pick_media) { pickDialog = true }
        }
        ActionButton(R.string.settings_preview, onPreview)

        SectionHeader(
            text = stringResource(R.string.settings_section_advanced),
            expanded = advancedExpanded,
            onClick = { advancedExpanded = !advancedExpanded }
        )
        if (advancedExpanded) {
            SettingSlider(
                label = stringResource(R.string.param_cooldown),
                valueText = { v -> "${v.toInt()} 秒" },
                value = (settings.retryMs / 1000).toFloat(),
                range = 10f..300f,
                step = 10f,
                description = stringResource(R.string.desc_cooldown),
                onChange = { onSettingsChange(settings.copy(retryMs = it.toLong() * 1000)) }
            )
            SettingSlider(
                label = stringResource(R.string.param_fade),
                valueText = { v -> String.format("%.1f 秒", v) },
                value = settings.fadeMs / 1000f,
                range = 0.5f..10f,
                step = 0.5f,
                onChange = { onSettingsChange(settings.copy(fadeMs = (it * 1000).toLong())) }
            )
            SettingSlider(
                label = stringResource(R.string.param_fade_out),
                valueText = { v -> String.format("%.1f 秒", v) },
                value = settings.fadeOutMs / 1000f,
                range = 0.5f..10f,
                step = 0.5f,
                onChange = { onSettingsChange(settings.copy(fadeOutMs = (it * 1000).toLong())) }
            )
        }

        Text(
            text = stringResource(R.string.settings_section_status),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 12.dp)
        )
        StatusRow("悬浮窗权限", status.overlay, "去授权", onOverlay)
        StatusRow("无障碍服务", status.accessibility, "去开启", onAccessibility)
        StatusRow("忽略电池优化", status.battery, "去设置", onBattery)
        ActionButton(R.string.settings_startup_manager, onStartupManager)
        ActionButton(R.string.settings_whitelist, onWhitelist)

        if (pickDialog) {
            AlertDialog(
                onDismissRequest = { pickDialog = false },
                title = { Text(stringResource(R.string.pick_media_title)) },
                text = { Text(stringResource(R.string.pick_media_message)) },
                confirmButton = {
                    TextButton(onClick = {
                        pickDialog = false
                        onPickImage()
                    }) { Text(stringResource(R.string.pick_image_option)) }
                },
                dismissButton = {
                    TextButton(onClick = {
                        pickDialog = false
                        onPickFolder()
                    }) { Text(stringResource(R.string.pick_folder_option)) }
                }
            )
        }
    }
}

@Composable
private fun SectionHeader(
    text: String,
    expanded: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = text, style = MaterialTheme.typography.titleMedium)
        Text(text = if (expanded) "▲" else "▼", style = MaterialTheme.typography.titleMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingSlider(
    label: String,
    valueText: (Float) -> String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    description: String? = null,
    onChange: (Float) -> Unit
) {
    // Keep a local draft while dragging so the label follows the finger, and only
    // persist once the user releases (avoids a DataStore write per pixel).
    var draft by remember(value) { mutableFloatStateOf(value) }
    var dragging by remember { mutableStateOf(false) }
    val shown = if (dragging) draft else value

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Text(text = valueText(shown), style = MaterialTheme.typography.bodyMedium)
        }
        Slider(
            value = shown.coerceIn(range.start, range.endInclusive),
            onValueChange = { raw ->
                dragging = true
                draft = roundToStep(raw, range.start, step, range.endInclusive)
            },
            onValueChangeFinished = {
                dragging = false
                onChange(draft)
            },
            valueRange = range,
            steps = 0,
            track = { sliderState ->
                SliderDefaults.Track(
                    sliderState = sliderState,
                    drawStopIndicator = {}
                )
            }
        )
        if (description != null) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF888888)
            )
        }
    }
}

private fun roundToStep(value: Float, start: Float, step: Float, end: Float): Float {
    if (step <= 0f) return value
    val stepsFromStart = Math.round((value - start) / step)
    return (start + stepsFromStart * step).coerceIn(start, end)
}

@Composable
private fun SettingSwitch(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun StatusRow(
    label: String,
    ok: Boolean,
    actionLabel: String,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = if (ok) "✓ $label" else "✗ $label",
            color = if (ok) Color(0xFF2E7D32) else Color(0xFFC62828),
            style = MaterialTheme.typography.bodyMedium
        )
        if (!ok) {
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
private fun ActionButton(labelRes: Int, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(text = stringResource(labelRes))
    }
}

@Composable
private fun StatusBanner(healthy: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (healthy) Color(0xFFE8F5E9) else Color(0xFFFFEBEE))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(if (healthy) R.string.diag_ok else R.string.diag_bad),
            color = if (healthy) Color(0xFF2E7D32) else Color(0xFFC62828),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun DiagnosticsDialog(
    issues: List<DiagnosticIssue>,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (issues.isEmpty()) {
                    Text(
                        text = stringResource(R.string.diag_all_ok),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                issues.forEach { issue ->
                    Text(
                        text = issue.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFC62828)
                    )
                }
            }
        },
        confirmButton = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.diag_ignore))
                }
                issues.forEach { issue ->
                    TextButton(onClick = {
                        onDismiss()
                        issue.onAction()
                    }) {
                        Text(issue.actionLabel)
                    }
                }
            }
        }
    )
}
