package com.tgwsproxy.ui

import com.tgwsproxy.R
import com.tgwsproxy.S
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.tgwsproxy.update.UpdateChecker
import com.tgwsproxy.update.UpdateState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AboutScreen(
    version: String,
    state: UpdateState,
    notifyEnabled: Boolean,
    lastCheckMs: Long,
    onToggleNotify: (Boolean) -> Unit,
    onCheck: () -> Unit,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Шапка: название и версия
        Card(
            colors = CardDefaults.cardColors(containerColor = cs.primaryContainer, contentColor = cs.onPrimaryContainer),
            shape = RoundedCornerShape(32.dp),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(
                    Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(cs.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Shield, contentDescription = null, tint = cs.onPrimary)
                }
                Column {
                    Text("TG WS Proxy", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        if (version.isNotEmpty()) S.get(R.string.version_fmt, version) else S.get(R.string.version_unknown),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }

        // Обновления
        SectionCard(S.get(R.string.updates), Icons.Rounded.SystemUpdate) {
            when (state) {
                UpdateState.Idle -> StatusLine(S.get(R.string.update_idle))
                UpdateState.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(S.get(R.string.checking), style = MaterialTheme.typography.bodyMedium)
                }
                UpdateState.UpToDate -> StatusLine(S.get(R.string.up_to_date))
                UpdateState.NoReleases -> StatusLine(S.get(R.string.no_releases))
                is UpdateState.Error -> Text(state.message, style = MaterialTheme.typography.bodyMedium, color = cs.error)
                is UpdateState.Available -> {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = cs.tertiaryContainer, contentColor = cs.onTertiaryContainer),
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(S.get(R.string.update_available, state.info.version), style = MaterialTheme.typography.titleMedium)
                            if (state.info.notes.isNotEmpty()) {
                                Text(
                                    state.info.notes.take(600) + if (state.info.notes.length > 600) "…" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { onOpen(state.info.url) }, modifier = Modifier.weight(1f)) {
                                    Text(S.get(R.string.release_page))
                                }
                                val apk = state.info.apkUrl
                                if (apk != null) {
                                    OutlinedButton(onClick = { onOpen(apk) }, modifier = Modifier.weight(1f)) {
                                        Text(S.get(R.string.download_apk))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (lastCheckMs > 0) {
                Text(
                    S.get(R.string.last_check, SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(lastCheckMs))),
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
            }
            FilledTonalButton(
                onClick = onCheck,
                enabled = state !is UpdateState.Checking,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(S.get(R.string.check_updates))
            }
            SwitchRow(
                title = S.get(R.string.notify_updates),
                subtitle = S.get(R.string.notify_updates_sub),
                checked = notifyEnabled,
                onChecked = onToggleNotify,
            )
        }

        // Автор и ссылки
        SectionCard(S.get(R.string.about_project), Icons.Rounded.Info) {
            InfoRow(S.get(R.string.author), UpdateChecker.AUTHOR)
            InfoRow(S.get(R.string.version), if (version.isNotEmpty()) version else "—")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { onOpen(UpdateChecker.REPO_URL) }, modifier = Modifier.weight(1f)) {
                    Text("GitHub")
                }
                OutlinedButton(onClick = { onOpen(UpdateChecker.UPSTREAM_URL) }, modifier = Modifier.weight(1f)) {
                    Text(S.get(R.string.original))
                }
            }
            Text(
                S.get(R.string.based_on),
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun StatusLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(88.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
