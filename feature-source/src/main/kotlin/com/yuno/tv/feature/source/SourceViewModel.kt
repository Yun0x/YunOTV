package com.yuno.tv.feature.source

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yuno.tv.core.*
import com.yuno.tv.core.config.ConfigRepository
import com.yuno.tv.core.model.*
import com.yuno.tv.core.network.NetworkClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class SourceUiState(val busy: Boolean = false, val error: ErrorCode? = null, val imported: Int = 0,
    val warnings: List<ConfigWarning>? = null)
class SourceViewModel(private val repository: ConfigRepository, context: Context, private val registry: com.yuno.tv.core.network.ProviderRegistry? = null, runtime: com.yuno.tv.core.runtime.CompatibilityRuntime? = null) : ViewModel() {
    private val resolver = context.applicationContext.contentResolver
    val sources = repository.sources
    val config = repository.config
    val diagnostics = repository.diagnostics
    val statistics = repository.statistics
    val runtimeDiagnostics = runtime?.diagnostics ?: MutableStateFlow<Map<String,com.yuno.tv.core.runtime.RuntimeDiagnostic>>(emptyMap())
    val health = registry?.health ?: MutableStateFlow<Map<String, SourceHealth>>(emptyMap())
    fun checkHealth() = action { registry?.healthChecker?.check(repository.config.value.sites, registry::provider) }
    private val _state = MutableStateFlow(SourceUiState())
    val state = _state.asStateFlow()
    fun add(name: String, url: String, format: String, priority: Int) = action { repository.addRemote(name, url, format, priority) }
    fun update(source: SourceRecord) = action { repository.update(source) }
    fun refresh(id: String? = null) = action { if (id == null) repository.refreshAll() else repository.refresh(id) }
    fun delete(id: String) = action { repository.delete(id) }
    fun showWarnings(id: String) = action { _state.update { it.copy(warnings = repository.warnings(id)) } }
    fun dismissWarnings() { _state.update { it.copy(warnings = null) } }
    fun missingFilePicker() { _state.update { it.copy(error = ErrorCode.FILE_PICKER) } }
    fun import(uris: List<Uri>) = action {
        var success = 0; var failure: ErrorCode? = null
        for (uri in uris) {
            try {
                withContext(Dispatchers.IO) {
                    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    } ?: "import"
                    val bytes = resolver.openInputStream(uri)?.use { stream ->
                        val buffer = java.io.ByteArrayOutputStream(); val chunk = ByteArray(8192)
                        while (true) {
                            val n = stream.read(chunk); if (n < 0) break
                            if (buffer.size() + n > NetworkClient.MAX_CONFIG_BYTES) throw SourceException(ErrorCode.CONFIG_FORMAT)
                            buffer.write(chunk, 0, n)
                        }; buffer.toByteArray()
                    } ?: throw SourceException(ErrorCode.STORAGE)
                    repository.importLocal(name, bytes)
                }; success++
            } catch (error: Exception) { error.rethrowCancellation(); failure = error.friendlyCode() }
        }
        _state.update { it.copy(imported = success, error = failure) }
    }
    private fun action(block: suspend () -> Unit) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try { block() } catch (error: Exception) { error.rethrowCancellation(); _state.update { it.copy(error = error.friendlyCode()) } }
            finally { _state.update { it.copy(busy = false) } }
        }
    }
}
