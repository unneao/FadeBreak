package com.wjf.fadebreak.ui.apps

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.lifecycleScope
import com.wjf.fadebreak.core.BreakSettings
import com.wjf.fadebreak.data.SettingsBridge
import com.wjf.fadebreak.ui.theme.FadeBreakTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppEntry(
    val packageName: String,
    val label: String,
    val component: ComponentName
)

class AppListActivity : ComponentActivity() {

    private lateinit var repository: SettingsBridge

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = SettingsBridge(applicationContext)
        setContent {
            FadeBreakTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val settings by repository.settings.collectAsState(
                        initial = BreakSettings.defaults()
                    )
                    val apps = rememberApps()
                    AppListScreen(
                        apps = apps,
                        selected = settings.whitelist,
                        onToggle = { pkg, checked ->
                            lifecycleScope.launch {
                                val current = repository.current()
                                val updated = if (checked) {
                                    current.whitelist + pkg
                                } else {
                                    current.whitelist - pkg
                                }
                                repository.set(current.copy(whitelist = updated))
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun rememberApps(): List<AppEntry> {
    val context = LocalContext.current
    val apps by produceState(initialValue = emptyList<AppEntry>()) {
        value = withContext(Dispatchers.IO) { queryApps(context) }
    }
    return apps
}

private fun queryApps(context: Context): List<AppEntry> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(intent, 0)
    }
    return resolveInfos
        .mapNotNull { info ->
            val activityInfo = info.activityInfo ?: return@mapNotNull null
            val pkg = activityInfo.packageName
            if (pkg == context.packageName) return@mapNotNull null
            AppEntry(
                packageName = pkg,
                label = info.loadLabel(pm).toString(),
                component = ComponentName(pkg, activityInfo.name)
            )
        }
        .distinctBy { it.packageName }
        .sortedBy { it.label }
}

@Composable
private fun AppListScreen(
    apps: List<AppEntry>,
    selected: Set<String>,
    onToggle: (String, Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
    ) {
        Text(
            text = "选择白名单应用(前台运行时不提醒)",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(16.dp)
        )
        if (apps.isEmpty()) {
            Text(
                text = "正在加载应用列表…",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp)
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(apps, key = { it.packageName }) { app ->
                    AppRow(
                        app = app,
                        checked = app.packageName in selected,
                        onToggle = onToggle
                    )
                }
            }
        }
    }
}

@Composable
private fun AppRow(
    app: AppEntry,
    checked: Boolean,
    onToggle: (String, Boolean) -> Unit
) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(initialValue = null, app.packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.packageManager.getActivityIcon(app.component)
                    .toBitmap(96, 96)
                    .asImageBitmap()
            }.getOrNull()
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(app.packageName, !checked) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                modifier = Modifier
                    .size(36.dp)
                    .padding(end = 12.dp)
            )
        }
        Text(
            text = app.label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Checkbox(
            checked = checked,
            onCheckedChange = { onToggle(app.packageName, it) }
        )
    }
}
