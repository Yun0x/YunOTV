package com.yuno.tv.feature.source

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.yuno.tv.core.model.*
import com.yuno.tv.core.ui.*
import com.yuno.tv.core.ui.R

@Composable
fun SourceDiagnosticsScreen(vm: SourceViewModel, onBack: () -> Unit) {
    val stats by vm.statistics.collectAsStateWithLifecycle()
    val diagnostics by vm.diagnostics.collectAsStateWithLifecycle()
    val config by vm.config.collectAsStateWithLifecycle()
    val health by vm.health.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    var kind by remember { mutableStateOf<CapabilityKind?>(null) }
    val sites = remember(config.sites, kind) { config.sites.filter { kind == null || it.capability.kind == kind } }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TvAction(stringResource(R.string.back), onClick = onBack)
            TvAction(stringResource(R.string.refresh_all), onClick = { vm.refresh() })
            TvAction(stringResource(R.string.health_check), onClick = vm::checkHealth)
            if (state.busy) Text(stringResource(R.string.loading))
        }
        Text(stringResource(R.string.diagnostic_counts, stats.downloadedConfigCount, stats.downloadFailedConfigCount,
            stats.parsedSiteCount, stats.deduplicatedSiteCount))
        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(8.dp)) {
            item { TvAction(stringResource(R.string.all_channels), selected = kind == null) { kind = null } }
            CapabilityKind.entries.forEach { value -> item(key = value.name) {
                TvAction("${value.name} ${config.sites.count { it.capability.kind == value }}", selected = kind == value) { kind = value }
            } }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(8.dp)) {
            items(diagnostics, key = { it.id }) { diagnostic ->
                Column(Modifier.fillMaxWidth().background(YunoColors.Surface, YunoShapes.L).padding(16.dp)) {
                    Text(diagnostic.name + " · " + stringResource(R.string.priority_value, diagnostic.priority))
                    Text(redactedSourceUrl(diagnostic.url), color = CinemaColors.muted, maxLines = 1)
                    Text(stringResource(R.string.diagnostic_latency, diagnostic.latencyMs, diagnostic.siteCount, diagnostic.supported, diagnostic.unsupported))
                    diagnostic.error?.let { Text(errorText(it), color = YunoColors.Error) }
                    if (diagnostic.cached) Text(stringResource(R.string.cached_data), color = YunoColors.Warning)
                }
            }
            items(sites, key = { it.id }) { site ->
                TvAction(site.name + " · " + site.capability.kind.name + " · " + site.capability.status.name + " · " +
                    (health[site.id]?.status?.name ?: SourceHealthStatus.UNKNOWN.name), Modifier.fillMaxWidth()) { vm.showWarnings(site.origin.sourceId) }
                Text(site.origins.joinToString(" · ") { it.sourceName }, color = CinemaColors.muted, maxLines = 1)
            }
        }
    }
    state.warnings?.let { warnings -> androidx.compose.material3.AlertDialog(onDismissRequest = vm::dismissWarnings,
        title = { Text(stringResource(R.string.parse_warnings, warnings.size)) },
        text = { LazyColumn { items(warnings.take(200)) { Text(it.entry + " · " + errorText(it.code)) } } },
        confirmButton = { TvAction(stringResource(R.string.close), onClick = vm::dismissWarnings) }) }
}
