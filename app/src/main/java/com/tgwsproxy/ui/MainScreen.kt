package com.tgwsproxy.ui

import com.tgwsproxy.R
import com.tgwsproxy.S
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tgwsproxy.ProxyState
import com.tgwsproxy.ProxyStats
import com.tgwsproxy.SpeedPoint
import com.tgwsproxy.stats.ClientInfo
import com.tgwsproxy.update.UpdateChecker
import com.tgwsproxy.util.BatteryTip
import com.tgwsproxy.update.UpdateState
import kotlinx.coroutines.Dispatchers
import com.tgwsproxy.stats.ClientRegistry
import com.tgwsproxy.stats.DailyStats
import com.tgwsproxy.stats.DayStats
import com.tgwsproxy.util.Fmt
import com.tgwsproxy.ProxyStatus
import com.tgwsproxy.data.Draft
import com.tgwsproxy.data.Field
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel = viewModel()) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val draft by vm.draft.collectAsStateWithLifecycle()
    val status by ProxyState.status.collectAsStateWithLifecycle()
    val stats by ProxyState.stats.collectAsStateWithLifecycle()
    val logs by ProxyState.logs.collectAsStateWithLifecycle()
    val runningCfg by ProxyState.runningConfig.collectAsStateWithLifecycle()
    val crash by ProxyState.lastCrash.collectAsStateWithLifecycle()
    val speed by ProxyState.speed.collectAsStateWithLifecycle()
    val lanIp by ProxyState.lanIp.collectAsStateWithLifecycle()
    val network by ProxyState.network.collectAsStateWithLifecycle()
    val days by DailyStats.days.collectAsStateWithLifecycle()
    val clients by ClientRegistry.clients.collectAsStateWithLifecycle()
    val blocked by ClientRegistry.blockedIps.collectAsStateWithLifecycle()
    val updateState by UpdateChecker.state.collectAsStateWithLifecycle()
    val notifyUpdates by UpdateChecker.notifyEnabled.collectAsStateWithLifecycle()
    val lastCheck by UpdateChecker.lastCheckMs.collectAsStateWithLifecycle()
    val appVersion = remember { UpdateChecker.currentVersion(ctx) }
    val lang by S.language.collectAsStateWithLifecycle()
    var batteryOk by remember { mutableStateOf(BatteryTip.isIgnoring(ctx)) }
    var batteryDismissed by remember { mutableStateOf(BatteryTip.dismissed(ctx)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { batteryOk = BatteryTip.isIgnoring(ctx) }
    val openUrl: (String) -> Unit = { url ->
        runCatching {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            scope.launch { snackbar.showSnackbar(S.get(R.string.err_open_link)) }
        }
    }

    var tab by rememberSaveable { mutableIntStateOf(0) }

    val validation = remember(draft, S.revision) { draft.validate() }
    val errors = validation.errors
    val changed = runningCfg?.let { it.runtimeKey() != draft.runtimeKey() } ?: false

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.start()
    }

    fun onStart() {
        val needPermission = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needPermission) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.start()
    }

    val cs = MaterialTheme.colorScheme

    Scaffold(
        containerColor = cs.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("TG WS Proxy", style = MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = cs.background,
                    titleContentColor = cs.onBackground,
                ),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = cs.surfaceContainer) {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Rounded.PowerSettingsNew, contentDescription = null) },
                    label = { Text(S.get(R.string.tab_start)) },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
                    label = { Text(S.get(R.string.tab_settings)) },
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Icon(Icons.Rounded.Terminal, contentDescription = null) },
                    label = { Text(S.get(R.string.tab_log)) },
                )
                NavigationBarItem(
                    selected = tab == 3,
                    onClick = { tab = 3 },
                    icon = { Icon(Icons.Rounded.Info, contentDescription = null) },
                    label = { Text(S.get(R.string.tab_about), fontSize = 11.sp, maxLines = 1) },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when (tab) {
            0 -> Column(
                modifier = Modifier
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                StatusCard(
                    status = status,
                    stats = stats,
                    canStart = errors.isEmpty(),
                    settingsChanged = changed,
                    network = network,
                    onStart = ::onStart,
                    onStop = vm::stop,
                    onRestart = ::onStart,
                    onOpen = { link ->
                        runCatching {
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
                        }.onFailure {
                            scope.launch { snackbar.showSnackbar(S.get(R.string.err_open_telegram)) }
                        }
                    },
                    onCopy = { link ->
                        clipboard.setText(AnnotatedString(link))
                        scope.launch { snackbar.showSnackbar(S.get(R.string.link_copied)) }
                    },
                )
                if (!batteryOk && !batteryDismissed) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = cs.secondaryContainer,
                            contentColor = cs.onSecondaryContainer,
                        ),
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(S.get(R.string.battery_tip), style = MaterialTheme.typography.bodyMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { BatteryTip.request(ctx) }) { Text(S.get(R.string.battery_allow)) }
                                TextButton(onClick = {
                                    BatteryTip.dismiss(ctx)
                                    batteryDismissed = true
                                }) { Text(S.get(R.string.later)) }
                            }
                            TextButton(onClick = { BatteryTip.openAppSettings(ctx) }) {
                                Text(S.get(R.string.battery_app_settings))
                            }
                        }
                    }
                }
                if (updateState is UpdateState.Available) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = cs.tertiaryContainer,
                            contentColor = cs.onTertiaryContainer,
                        ),
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Row(
                            Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                S.get(R.string.banner_update, (updateState as UpdateState.Available).info.version),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            TextButton(onClick = { tab = 3 }) { Text(S.get(R.string.details)) }
                        }
                    }
                }
                if (status is ProxyStatus.Running) SpeedCard(speed)
                val runningStatus = status as? ProxyStatus.Running
                if (runningStatus != null && runningStatus.host == "0.0.0.0") {
                    ShareCard(
                        lanIp = lanIp,
                        port = runningStatus.port,
                        link = runningStatus.link,
                        onCopy = { text ->
                            clipboard.setText(AnnotatedString(text))
                            scope.launch { snackbar.showSnackbar(S.get(R.string.link_copied)) }
                        },
                    )
                }
                if (runningStatus != null && runningStatus.host == "0.0.0.0") {
                    ClientsCard(
                        clients = clients,
                        blocked = blocked.size,
                        onDisconnect = vm::disconnectClient,
                        onBlock = vm::blockClient,
                        onUnblockAll = vm::unblockAll,
                    )
                }
                if (days.isNotEmpty()) DailyStatsCard(days)
                if (status is ProxyStatus.Running && stats.rejected >= 20) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = cs.tertiaryContainer,
                            contentColor = cs.onTertiaryContainer,
                        ),
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Text(
                            S.get(R.string.rejected_hint, stats.rejected),
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                if (crash != null) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = cs.errorContainer,
                            contentColor = cs.onErrorContainer,
                        ),
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Row(
                            Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                S.get(R.string.crash_banner),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            TextButton(onClick = { tab = 2 }) { Text(S.get(R.string.open)) }
                        }
                    }
                }
                if (!errors.isEmpty() && status !is ProxyStatus.Running) {
                    TextButton(onClick = { tab = 1 }) { Text(S.get(R.string.go_settings)) }
                }
            }

            1 -> Column(
                modifier = Modifier
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (changed) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = cs.tertiaryContainer,
                            contentColor = cs.onTertiaryContainer,
                        ),
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Row(
                            Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                S.get(R.string.settings_changed),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            TextButton(onClick = ::onStart, enabled = errors.isEmpty()) { Text(S.get(R.string.apply)) }
                        }
                    }
                }

                SectionCard(S.get(R.string.sec_connection), Icons.Rounded.Lan) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AppField(
                            value = draft.host,
                            onChange = { v -> vm.update { it.copy(host = v) } },
                            label = S.get(R.string.ip_address),
                            modifier = Modifier.weight(1.6f),
                            error = errors[Field.HOST],
                            keyboardType = KeyboardType.Uri,
                        )
                        AppField(
                            value = draft.port,
                            onChange = { v -> vm.update { it.copy(port = v.filter(Char::isDigit).take(5)) } },
                            label = S.get(R.string.port),
                            modifier = Modifier.weight(1f),
                            error = errors[Field.PORT]?.let { "1024–65535" },
                            keyboardType = KeyboardType.Number,
                        )
                    }
                    AppField(
                        value = draft.secret,
                        onChange = { v -> vm.update { it.copy(secret = v.trim()) } },
                        label = S.get(R.string.secret_label),
                        error = errors[Field.SECRET],
                        supporting = if (draft.fakeTlsDomain.isBlank()) S.get(R.string.secret_hint_dd) else S.get(R.string.secret_hint_ee),
                        trailing = {
                            Row {
                                IconButton(onClick = {
                                    clipboard.setText(AnnotatedString(draft.secret))
                                    scope.launch { snackbar.showSnackbar(S.get(R.string.key_copied)) }
                                }) { Icon(Icons.Rounded.ContentCopy, contentDescription = S.get(R.string.copy_key)) }
                                IconButton(onClick = vm::regenerateSecret) {
                                    Icon(Icons.Rounded.Refresh, contentDescription = S.get(R.string.generate_new))
                                }
                            }
                        },
                    )
                    AppField(
                        value = draft.fakeTlsDomain,
                        onChange = { v -> vm.update { it.copy(fakeTlsDomain = v) } },
                        label = S.get(R.string.fake_tls_label),
                        error = errors[Field.FAKE_TLS],
                        supporting = S.get(R.string.fake_tls_hint),
                        keyboardType = KeyboardType.Uri,
                    )
                    SwitchRow(
                        title = S.get(R.string.lan_access),
                        subtitle = S.get(R.string.lan_access_sub),
                        checked = draft.host.trim() == "0.0.0.0",
                        onChecked = { v -> vm.update { it.copy(host = if (v) "0.0.0.0" else "127.0.0.1") } },
                    )
                    if (draft.host.trim() == "0.0.0.0") {
                        Text(
                            S.get(R.string.lan_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                        AppField(
                            value = draft.allowedIps,
                            onChange = { v -> vm.update { it.copy(allowedIps = v) } },
                            label = S.get(R.string.allowed_label),
                            error = errors[Field.ALLOWED],
                            supporting = S.get(R.string.allowed_hint),
                            singleLine = false,
                            minLines = 2,
                            keyboardType = KeyboardType.Ascii,
                        )
                    }
                }

                SectionCard(S.get(R.string.sec_routing), Icons.Rounded.Tune) {
                    AppField(
                        value = draft.dcIp,
                        onChange = { v -> vm.update { it.copy(dcIp = v) } },
                        label = "DC → IP",
                        error = errors[Field.DC_IP],
                        supporting = S.get(R.string.dc_hint),
                        singleLine = false,
                        minLines = 3,
                        keyboardType = KeyboardType.Ascii,
                    )
                }

                SectionCard("Cloudflare", Icons.Rounded.Cloud) {
                    SwitchRow(
                        title = S.get(R.string.cf_proxy),
                        subtitle = S.get(R.string.cf_proxy_sub),
                        checked = draft.cfProxy,
                        onChecked = { v -> vm.update { it.copy(cfProxy = v) } },
                    )
                    AnimatedVisibility(draft.cfProxy) {
                        AppField(
                            value = draft.cfProxyDomain,
                            onChange = { v -> vm.update { it.copy(cfProxyDomain = v) } },
                            label = S.get(R.string.cf_domain_label),
                            error = errors[Field.CF_DOMAIN],
                            supporting = S.get(R.string.cf_domain_hint),
                            keyboardType = KeyboardType.Uri,
                        )
                    }
                    AnimatedVisibility(draft.cfProxy) {
                        SwitchRow(
                            title = S.get(R.string.h2_media),
                            subtitle = S.get(R.string.h2_media_sub),
                            checked = draft.cfH2,
                            onChecked = { v -> vm.update { it.copy(cfH2 = v) } },
                        )
                    }
                    SwitchRow(
                        title = "Cloudflare Worker",
                        subtitle = S.get(R.string.worker_sub),
                        checked = draft.cfWorker,
                        onChecked = { v -> vm.update { it.copy(cfWorker = v) } },
                    )
                    AnimatedVisibility(draft.cfWorker) {
                        AppField(
                            value = draft.cfWorkerDomain,
                            onChange = { v -> vm.update { it.copy(cfWorkerDomain = v) } },
                            label = S.get(R.string.worker_domain_label),
                            error = errors[Field.WORKER_DOMAIN],
                            supporting = S.get(R.string.worker_domain_hint),
                            keyboardType = KeyboardType.Uri,
                        )
                    }
                }

                SectionCard(S.get(R.string.sec_system), Icons.Rounded.Settings) {
                    SwitchRow(
                        title = S.get(R.string.autostart),
                        subtitle = S.get(R.string.autostart_sub),
                        checked = draft.autostart,
                        onChecked = { v -> vm.update { it.copy(autostart = v) } },
                    )
                    SwitchRow(
                        title = S.get(R.string.restart_net),
                        subtitle = S.get(R.string.restart_net_sub),
                        checked = draft.restartOnNetwork,
                        onChecked = { v -> vm.update { it.copy(restartOnNetwork = v) } },
                    )
                    if (Build.VERSION.SDK_INT >= 31) {
                        SwitchRow(
                            title = S.get(R.string.dynamic_colors),
                            subtitle = S.get(R.string.dynamic_colors_sub),
                            checked = draft.dynamicColors,
                            onChecked = { v -> vm.update { it.copy(dynamicColors = v) } },
                        )
                    }
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(S.get(R.string.language), style = MaterialTheme.typography.bodyLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                "system" to R.string.lang_system,
                                "ru" to R.string.lang_ru,
                                "en" to R.string.lang_en,
                            ).forEach { (code, label) ->
                                FilterChip(
                                    selected = lang == code,
                                    onClick = { S.setLanguage(code) },
                                    label = { Text(S.get(label)) },
                                )
                            }
                        }
                    }
                    if (Build.VERSION.SDK_INT >= 33) {
                        TextButton(onClick = {
                            requestAddTile(ctx) { msg -> scope.launch { snackbar.showSnackbar(msg) } }
                        }) { Text(S.get(R.string.add_tile)) }
                    } else {
                        Text(
                            S.get(R.string.tile_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
            }

            2 -> LogScreen(
                logs = logs,
                crash = crash,
                onCopyCrash = {
                    clipboard.setText(AnnotatedString(crash.orEmpty()))
                    scope.launch { snackbar.showSnackbar(S.get(R.string.crash_copied)) }
                },
                onDismissCrash = vm::dismissCrash,
                onClear = ProxyState::clearLogs,
                onCopy = {
                    clipboard.setText(AnnotatedString(logs.joinToString("\n")))
                    scope.launch { snackbar.showSnackbar(S.get(R.string.log_copied)) }
                },
                modifier = Modifier.padding(padding),
            )

            else -> AboutScreen(
                version = appVersion,
                state = updateState,
                notifyEnabled = notifyUpdates,
                lastCheckMs = lastCheck,
                onToggleNotify = UpdateChecker::setNotifyEnabled,
                onCheck = {
                    scope.launch(Dispatchers.IO) { UpdateChecker.check(ctx.applicationContext, notify = false) }
                },
                onOpen = openUrl,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun StatusCard(
    status: ProxyStatus,
    stats: ProxyStats,
    canStart: Boolean,
    settingsChanged: Boolean,
    network: String?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onOpen: (String) -> Unit,
    onCopy: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val running = status is ProxyStatus.Running
    val starting = status is ProxyStatus.Starting
    val container = if (running) cs.primaryContainer else cs.surfaceContainerHigh
    val onContainer = if (running) cs.onPrimaryContainer else cs.onSurface

    val title = when (status) {
        is ProxyStatus.Running -> S.get(R.string.proxy_running)
        ProxyStatus.Starting -> S.get(R.string.starting)
        is ProxyStatus.Error -> S.get(R.string.error)
        ProxyStatus.Stopped -> S.get(R.string.stopped)
    }
    val subtitle = when (status) {
        is ProxyStatus.Running -> "${status.host}:${status.port}"
        ProxyStatus.Starting -> S.get(R.string.please_wait)
        is ProxyStatus.Error -> status.message
        ProxyStatus.Stopped -> S.get(R.string.press_start)
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = container, contentColor = onContainer),
        shape = RoundedCornerShape(32.dp),
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(if (running) cs.primary else cs.secondaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (running) Icons.Rounded.Shield else Icons.Rounded.PowerSettingsNew,
                        contentDescription = null,
                        tint = if (running) cs.onPrimary else cs.onSecondaryContainer,
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (status is ProxyStatus.Error) cs.error else onContainer.copy(alpha = 0.8f),
                    )
                }
            }

            Button(
                onClick = if (running || starting) onStop else onStart,
                enabled = running || starting || canStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(20.dp),
            ) {
                Icon(if (running || starting) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (running || starting) S.get(R.string.stop) else S.get(R.string.start), style = MaterialTheme.typography.titleMedium)
            }

            if (!canStart && !running && !starting) {
                Text(
                    S.get(R.string.fix_errors),
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.error,
                )
            }

            if (running && settingsChanged) {
                OutlinedButton(onClick = onRestart, enabled = canStart, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(S.get(R.string.apply_restart))
                }
            }

            if (status is ProxyStatus.Running) {
                Surface(shape = RoundedCornerShape(16.dp), color = cs.surface.copy(alpha = 0.6f)) {
                    SelectionContainer {
                        Text(
                            status.link,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { onOpen(status.link) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(S.get(R.string.add_to_telegram))
                    }
                    OutlinedButton(onClick = { onCopy(status.link) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(S.get(R.string.copy))
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        S.get(R.string.stats_line, stats.active, stats.total, stats.failed, stats.rejected),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "WS ${stats.ws} · CF ${stats.cf} · Worker ${stats.worker} · TCP ${stats.tcp}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "↑ ${formatBytes(stats.bytesUp)}  ↓ ${formatBytes(stats.bytesDown)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (network != null) {
                        Text(S.get(R.string.network_fmt, network), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
internal fun SectionCard(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(
        colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow),
        shape = RoundedCornerShape(28.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(icon, contentDescription = null, tint = cs.primary)
                Text(title, style = MaterialTheme.typography.titleMedium, color = cs.primary)
            }
            content()
        }
    }
}

@Composable
private fun AppField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    supporting: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    minLines: Int = 1,
    trailing: (@Composable () -> Unit)? = null,
) {
    val text = error ?: supporting
    val support: (@Composable () -> Unit)? = if (text != null) {
        { Text(text) }
    } else {
        null
    }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        isError = error != null,
        supportingText = support,
        trailingIcon = trailing,
        singleLine = singleLine,
        minLines = minLines,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
internal fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChecked) },
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable { onChecked(!checked) },
    )
}

@Composable
private fun LogScreen(
    logs: List<String>,
    crash: String?,
    onCopyCrash: () -> Unit,
    onDismissCrash: () -> Unit,
    onClear: () -> Unit,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val listState = rememberLazyListState()
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.scrollToItem(logs.lastIndex)
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (crash != null) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = cs.errorContainer,
                    contentColor = cs.onErrorContainer,
                ),
                shape = RoundedCornerShape(20.dp),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(S.get(R.string.last_crash), style = MaterialTheme.typography.titleSmall)
                    Text(
                        crash.lines().take(10).joinToString("\n"),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                    )
                    Row {
                        TextButton(onClick = onCopyCrash) { Text(S.get(R.string.copy_all)) }
                        TextButton(onClick = onDismissCrash) { Text(S.get(R.string.hide)) }
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                S.get(R.string.entries, logs.size),
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onCopy, enabled = logs.isNotEmpty()) { Text(S.get(R.string.copy)) }
            TextButton(onClick = onClear, enabled = logs.isNotEmpty()) { Text(S.get(R.string.clear)) }
        }
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = cs.surfaceContainerLow,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            if (logs.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(S.get(R.string.empty_log), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(logs) { line ->
                        Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = cs.onSurface)
                    }
                }
            }
        }
    }
}

@Composable
private fun ClientsCard(
    clients: List<ClientInfo>,
    blocked: Int,
    onDisconnect: (String) -> Unit,
    onBlock: (String) -> Unit,
    onUnblockAll: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val online = clients.count { it.active > 0 }
    Card(
        colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow),
        shape = RoundedCornerShape(28.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Rounded.Devices, contentDescription = null, tint = cs.primary)
                Text(
                    S.get(R.string.clients_title, online),
                    style = MaterialTheme.typography.titleMedium,
                    color = cs.primary,
                )
            }
            if (clients.isEmpty()) {
                Text(S.get(R.string.no_clients), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            }
            clients.forEach { c ->
                Surface(shape = RoundedCornerShape(16.dp), color = cs.surfaceContainerHigh) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (c.active > 0) cs.primary else cs.outline),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (c.isLocal) S.get(R.string.this_device, c.ip) else c.ip,
                                style = MaterialTheme.typography.titleSmall,
                            )
                        }
                        Text(
                            S.get(R.string.client_stats, c.active, c.total, Fmt.bytes(c.down), Fmt.bytes(c.up)),
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                        )
                        if (!c.isLocal) {
                            Row {
                                TextButton(onClick = { onDisconnect(c.ip) }, enabled = c.active > 0) { Text(S.get(R.string.disconnect)) }
                                TextButton(onClick = { onBlock(c.ip) }) { Text(S.get(R.string.block)) }
                            }
                        }
                    }
                }
            }
            if (blocked > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        S.get(R.string.blocked_count, blocked),
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onUnblockAll) { Text(S.get(R.string.reset)) }
                }
            }
        }
    }
}

@Composable
private fun ShareCard(lanIp: String?, port: Int, link: String, onCopy: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val lanLink = lanIp?.let { link.replace("server=127.0.0.1", "server=$it") }
    Card(
        colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow),
        shape = RoundedCornerShape(28.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Rounded.Wifi, contentDescription = null, tint = cs.primary)
                Text(S.get(R.string.share_title), style = MaterialTheme.typography.titleMedium, color = cs.primary)
            }
            if (lanLink == null) {
                Text(
                    S.get(R.string.share_no_ip),
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                )
            } else {
                Text(
                    S.get(R.string.share_hint, lanIp, port),
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                )
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    QrCode(lanLink, Modifier.size(240.dp))
                }
                OutlinedButton(onClick = { onCopy(lanLink) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(S.get(R.string.copy_share_link))
                }
            }
        }
    }
}

@Composable
private fun DailyStatsCard(days: List<DayStats>) {
    val cs = MaterialTheme.colorScheme
    val today = days.firstOrNull { it.date == DailyStats.today() } ?: DayStats(DailyStats.today())
    val history = days.filter { it.date != today.date }.take(6)
    val colors = listOf(cs.primary, cs.tertiary, cs.secondary, cs.outline)
    val values = listOf(today.ws, today.cf, today.worker, today.tcp)
    val names = listOf("WS", "CF", "Worker", "TCP")

    Card(
        colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow),
        shape = RoundedCornerShape(28.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Rounded.BarChart, contentDescription = null, tint = cs.primary)
                Text(S.get(R.string.routes_today), style = MaterialTheme.typography.titleMedium, color = cs.primary)
            }
            Text(
                S.get(R.string.today_stats, today.total, today.failed, Fmt.bytes(today.down), Fmt.bytes(today.up)),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(cs.surfaceContainerHighest),
            ) {
                values.forEachIndexed { i, v ->
                    if (v > 0) Box(
                        Modifier
                            .weight(v.toFloat())
                            .fillMaxHeight()
                            .background(colors[i]),
                    )
                }
            }
            Row(Modifier.fillMaxWidth()) {
                values.forEachIndexed { i, v ->
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(colors[i]),
                            )
                            Text(names[i], style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                        }
                        Text("$v", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            if (history.isNotEmpty()) {
                Text(S.get(R.string.previous_days), style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                history.forEach { d ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            d.date.substring(8, 10) + "." + d.date.substring(5, 7),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.width(48.dp),
                        )
                        Text(
                            "WS ${d.ws} · CF ${d.cf} · Worker ${d.worker} · TCP ${d.tcp}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        Text("↓ ${Fmt.bytes(d.down)}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun SpeedCard(points: List<SpeedPoint>) {
    val cs = MaterialTheme.colorScheme
    val down = points.lastOrNull()?.down ?: 0L
    val up = points.lastOrNull()?.up ?: 0L
    val peak = maxOf(points.maxOfOrNull { maxOf(it.up, it.down) } ?: 0L, 10L * 1024)
    val downColor = cs.primary
    val upColor = cs.tertiary
    val gridColor = cs.outlineVariant

    Card(
        colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow),
        shape = RoundedCornerShape(28.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Rounded.Speed, contentDescription = null, tint = cs.primary)
                Text(S.get(R.string.speed), style = MaterialTheme.typography.titleMedium, color = cs.primary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column {
                    Text(S.get(R.string.download), style = MaterialTheme.typography.labelMedium, color = downColor)
                    Text(formatSpeed(down), style = MaterialTheme.typography.titleLarge)
                }
                Column {
                    Text(S.get(R.string.upload), style = MaterialTheme.typography.labelMedium, color = upColor)
                    Text(formatSpeed(up), style = MaterialTheme.typography.titleLarge)
                }
            }
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(120.dp),
            ) {
                val w = size.width
                val h = size.height
                val n = ProxyState.SPEED_POINTS
                val pad = 4.dp.toPx()
                val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)

                for (i in 0..3) {
                    val y = h * i / 3f
                    drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1.dp.toPx())
                }
                if (points.size >= 2) {
                    val xAt = { i: Int -> w * (i + (n - points.size)) / (n - 1f) }
                    val yAt = { v: Long -> h - pad - (v.toFloat() / peak) * (h - 2 * pad) }

                    val downLine = Path()
                    val upLine = Path()
                    points.forEachIndexed { i, p ->
                        if (i == 0) {
                            downLine.moveTo(xAt(i), yAt(p.down))
                            upLine.moveTo(xAt(i), yAt(p.up))
                        } else {
                            downLine.lineTo(xAt(i), yAt(p.down))
                            upLine.lineTo(xAt(i), yAt(p.up))
                        }
                    }
                    val fill = Path()
                    fill.addPath(downLine)
                    fill.lineTo(xAt(points.lastIndex), h)
                    fill.lineTo(xAt(0), h)
                    fill.close()

                    drawPath(fill, downColor.copy(alpha = 0.18f))
                    drawPath(downLine, downColor, style = stroke)
                    drawPath(upLine, upColor, style = stroke)
                }
            }
            Row {
                Text(
                    S.get(R.string.last_seconds, ProxyState.SPEED_POINTS),
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    S.get(R.string.peak, formatSpeed(peak)),
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
            }
        }
    }
}

private fun formatSpeed(bytesPerSec: Long): String = Fmt.speed(bytesPerSec)

private fun formatBytes(b: Long): String = Fmt.bytes(b)
