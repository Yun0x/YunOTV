package com.yuno.tv.feature.source

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.yuno.tv.core.model.*
import com.yuno.tv.core.ui.*
import com.yuno.tv.core.ui.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun SourceScreen(vm: SourceViewModel) {
    var diagnosticPage by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    if (diagnosticPage) {
        androidx.activity.compose.BackHandler { diagnosticPage = false }
        SourceDiagnosticsScreen(vm) { diagnosticPage = false }; return
    }
    val statistics by vm.statistics.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<SourceRecord?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<SourceRecord?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    var localPermission by remember { mutableStateOf(android.os.Build.VERSION.SDK_INT < 37 || androidx.core.content.ContextCompat.checkSelfPermission(context, "android.permission.ACCESS_LOCAL_NETWORK") == android.content.pm.PackageManager.PERMISSION_GRANTED) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> localPermission = granted; if (granted) vm.refresh() }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) vm.import(it) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(stringResource(R.string.source_statistics, statistics.enabledConfigCount, statistics.deduplicatedSiteCount,
            statistics.supportedSiteCount, statistics.unsupportedSiteCount), color = CinemaColors.muted)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TvAction(stringResource(R.string.source_diagnostics)) { diagnosticPage = true }
            TvAction(stringResource(R.string.add_url)) { editing = null; showEditor = true }
            TvAction(stringResource(R.string.import_files)) {
                try { importer.launch(arrayOf("*/*")) }
                catch (_: android.content.ActivityNotFoundException) { vm.missingFilePicker() }
            }
            TvAction(stringResource(R.string.refresh_all)) { vm.refresh() }
            if (state.busy) Text(stringResource(R.string.loading))
        }
        if (!localPermission && android.os.Build.VERSION.SDK_INT >= 37) {
            Text(stringResource(R.string.lan_permission_hint), color = CinemaColors.muted)
            TvAction(stringResource(R.string.lan_permission)) { permission.launch("android.permission.ACCESS_LOCAL_NETWORK") }
        }
        state.error?.let { Text(errorText(it), color = CinemaColors.accent) }
        if (state.imported > 0) Text(stringResource(R.string.import_count, state.imported))
        if (sources.isEmpty()) Text(stringResource(R.string.empty_guide), color = CinemaColors.muted)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items(sources, key = { it.id }) { source ->
                Column(Modifier.fillMaxWidth().background(YunoColors.Surface, YunoShapes.L).padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(source.name + " · " + source.format + " · " + stringResource(R.string.priority_value, source.priority))
                    Text(redactedSourceUrl(source.url), color = CinemaColors.muted, maxLines = 2)
                    val time = if (source.lastSuccessTime > 0) DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())
                        .format(Instant.ofEpochMilli(source.lastSuccessTime)) else stringResource(R.string.never_updated)
                    Text(stringResource(R.string.last_update, time) + " · " + when {
                        source.error != null -> errorText(source.error!!)
                        source.status == "REFRESHING" -> stringResource(R.string.loading)
                        source.status == "OK" -> stringResource(R.string.status_ok)
                        else -> stringResource(R.string.status_new)
                    })
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TvAction(stringResource(if (source.enabled) R.string.disable else R.string.enable)) { vm.update(source.copy(enabled = !source.enabled)) }
                        TvAction(stringResource(R.string.edit)) { editing = source; showEditor = true }
                        TvAction(stringResource(R.string.refresh)) { vm.refresh(source.id) }
                        TvAction(stringResource(R.string.delete)) { deleting = source }
                        if (source.warningCount > 0) TvAction(stringResource(R.string.parse_warnings, source.warningCount)) { vm.showWarnings(source.id) }
                    }
                }
            }
        }
    }
    if (showEditor) SourceEditor(editing, onDismiss = { showEditor = false }) { name, url, format, priority ->
        editing?.let { vm.update(it.copy(name = name, url = url, format = format, priority = priority)) }
            ?: vm.add(name, url, format, priority)
        showEditor = false
    }
    deleting?.let { source -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text(stringResource(R.string.delete_source)) },
        text = { Text(source.name) }, confirmButton = { TvAction(stringResource(R.string.delete)) { vm.delete(source.id); deleting = null } },
        dismissButton = { TvAction(stringResource(R.string.cancel)) { deleting = null } }) }
    state.warnings?.let { warnings -> AlertDialog(onDismissRequest = vm::dismissWarnings,
        title = { Text(stringResource(R.string.parse_warnings, warnings.size)) },
        text = { LazyColumn { items(warnings) { Text(it.entry + " " + errorText(it.code)) } } },
        confirmButton = { TvAction(stringResource(R.string.close), onClick = vm::dismissWarnings) }) }
}

@Composable
private fun SourceEditor(source: SourceRecord?, onDismiss: () -> Unit,
    onSave: (String, String, String, Int) -> Unit) {
    var name by remember { mutableStateOf(source?.name.orEmpty()) }
    var url by remember { mutableStateOf(source?.url.orEmpty()) }
    var priority by remember { mutableStateOf((source?.priority ?: 100).toString()) }
    var format by remember { mutableStateOf(source?.format ?: "AUTO") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.source_management)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.source_name)) }, singleLine = true)
            if (source?.local != true) OutlinedTextField(url, { url = it }, label = { Text(stringResource(R.string.source_url)) }, singleLine = true)
            OutlinedTextField(priority, { priority = it.filter(Char::isDigit).take(5) }, label = { Text(stringResource(R.string.priority)) }, singleLine = true)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(8.dp)) {
                items(listOf("AUTO", "JSON", "TXT", "M3U", "IPTV")) { type -> TvAction(if (format == type) "✓ $type" else type) { format = type } }
            }
        } }, confirmButton = { TvAction(stringResource(R.string.save)) { onSave(name, url, format, priority.toIntOrNull() ?: 100) } },
        dismissButton = { TvAction(stringResource(R.string.cancel), onClick = onDismiss) })
}
