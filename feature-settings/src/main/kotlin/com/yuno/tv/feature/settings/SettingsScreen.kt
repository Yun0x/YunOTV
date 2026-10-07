package com.yuno.tv.feature.settings

import android.content.Context
import coil.imageLoader
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.yuno.tv.core.*
import com.yuno.tv.core.config.*
import com.yuno.tv.core.database.LibraryRepository
import com.yuno.tv.core.model.*
import com.yuno.tv.core.ui.*
import com.yuno.tv.core.ui.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class SettingsViewModel(private val repository: SettingsRepository, private val configs: ConfigRepository,
    private val library: LibraryRepository, private val logger: AppLogger, private val runtime: com.yuno.tv.core.runtime.CompatibilityRuntime? = null) : ViewModel() {
    val settings = repository.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlayerSettings())
    private val _error = MutableStateFlow<ErrorCode?>(null)
    val error = _error.asStateFlow()
    private val _pluginStats = MutableStateFlow(Triple(0,0,0L))
    val pluginStats = _pluginStats.asStateFlow()
    init { viewModelScope.launch(Dispatchers.IO) { refreshPluginStats() } }
    private fun refreshPluginStats() { val jars=runtime?.jars?.stats() ?: (0 to 0L);val js=runtime?.scripts?.stats() ?: (0 to 0L);_pluginStats.value=Triple(jars.first,js.first,(jars.second+js.second)/(1024*1024)) }
    fun clearPlugins() { action { runtime?.clear();refreshPluginStats() } }
    fun missingFilePicker() { _error.value = ErrorCode.FILE_PICKER }
    fun update(transform: (PlayerSettings) -> PlayerSettings) { action { repository.update(transform) } }
    fun clearHistory() { action { library.clearHistory() } }
    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    fun clearCache(context: Context) { action {
        withContext(Dispatchers.IO) { context.imageLoader.memoryCache?.clear(); context.imageLoader.diskCache?.clear(); context.filesDir.resolve("media-cache").listFiles()?.filter { it.isFile }?.forEach { it.delete() } }
        runtime?.clear();refreshPluginStats();configs.clearRemoteCache()
    } }
    fun export(context: Context, uri: android.net.Uri) { action { withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri)?.use { it.write(logger.export()) } ?: throw SourceException(ErrorCode.STORAGE)
    } } }
    private fun action(block: suspend () -> Unit) { viewModelScope.launch {
        try { _error.value = null; block() }
        catch (error: Exception) { error.rethrowCancellation(); _error.value = error.friendlyCode() }
    } }
}
@Composable
fun SettingsScreen(vm: SettingsViewModel, context: Context, onSources: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val pluginStats by vm.pluginStats.collectAsStateWithLifecycle()
    var clearHistory by remember { mutableStateOf(false) }
    var clearCache by remember { mutableStateOf(false) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri -> if (uri != null) vm.export(context, uri) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(22.dp), contentPadding = PaddingValues(8.dp)) {
        item { YunoBrand(large = true) }
        item { Text(stringResource(R.string.settings_data), style = androidx.tv.material3.MaterialTheme.typography.titleLarge) }
        item { TvAction(stringResource(R.string.source_management), Modifier.fillMaxWidth(), onClick = onSources) }
        item {
            Text(stringResource(R.string.player_settings))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvAction(stringResource(R.string.speed_value, settings.speed)) {
                    val speeds = listOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f)
                    vm.update { it.copy(speed = speeds[(speeds.indexOf(it.speed) + 1) % speeds.size]) }
                }
                TvAction(stringResource(R.string.seek_seconds, settings.seekSeconds)) {
                    val increments = listOf(5, 10, 15, 30, 60); vm.update { it.copy(seekSeconds = increments[(increments.indexOf(it.seekSeconds) + 1) % increments.size]) }
                }
                TvAction(stringResource(R.string.default_aspect, aspectLabel(settings.aspect))) {
                    val values = listOf("FIT", "ORIGINAL", "16:9", "FILL", "CROP"); vm.update { it.copy(aspect = values[(values.indexOf(it.aspect) + 1) % values.size]) }
                }
            }
        }
        item { TvAction(stringResource(R.string.plugin_cache, pluginStats.first,pluginStats.second,pluginStats.third),onClick=vm::clearPlugins) }
        item { SettingToggle(stringResource(R.string.auto_next), settings.autoNext) { vm.update { it.copy(autoNext = !it.autoNext) } } }
        item { SettingToggle(stringResource(R.string.record_history), settings.historyEnabled) { vm.update { it.copy(historyEnabled = !it.historyEnabled) } } }
        item { SettingToggle(stringResource(R.string.auto_refresh), settings.autoRefresh) { vm.update { it.copy(autoRefresh = !it.autoRefresh) } } }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TvAction(stringResource(R.string.clear_history)) { clearHistory = true }
                TvAction(stringResource(R.string.clear_cache)) { clearCache = true }
                TvAction(stringResource(R.string.export_logs)) {
                    try { exporter.launch("yuno-tv-diagnostics.txt") }
                    catch (_: android.content.ActivityNotFoundException) { vm.missingFilePicker() }
                }
            }
        }
        item {
            Text(stringResource(R.string.interface_settings))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvAction(stringResource(if (settings.theme == "DARK") R.string.theme_dark else R.string.theme_oled)) { vm.update { it.copy(theme = if (it.theme == "DARK") "OLED" else "DARK") } }
                TvAction(stringResource(if (settings.homeCategory == "recent") R.string.home_recent else R.string.home_continue)) { vm.update { it.copy(homeCategory = if (it.homeCategory == "recent") "continue" else "recent") } }
            }
        }
        item { Text(stringResource(R.string.settings_about), style = androidx.tv.material3.MaterialTheme.typography.titleLarge); Text(stringResource(R.string.about_text), color = CinemaColors.muted) }
        error?.let { code -> item { Text(errorText(code)) } }
    }
    if (clearHistory || clearCache) AlertDialog(onDismissRequest = { clearHistory = false; clearCache = false },
        title = { Text(stringResource(if (clearHistory) R.string.clear_history_confirm else R.string.clear_cache_confirm)) },
        confirmButton = { TvAction(stringResource(R.string.confirm)) {
            if (clearHistory) vm.clearHistory() else vm.clearCache(context)
            clearHistory = false; clearCache = false
        } }, dismissButton = { TvAction(stringResource(R.string.cancel)) { clearHistory = false; clearCache = false } })
}
@Composable private fun SettingToggle(label: String, enabled: Boolean, onClick: () -> Unit) {
    TvAction(label + " · " + stringResource(if (enabled) R.string.enabled else R.string.disabled), onClick = onClick)
}
@Composable private fun aspectLabel(value: String): String = stringResource(when (value) {
    "ORIGINAL" -> R.string.aspect_original; "16:9" -> R.string.aspect_16_9; "FILL" -> R.string.aspect_fill;
    "CROP" -> R.string.aspect_crop; else -> R.string.aspect_fit
})
