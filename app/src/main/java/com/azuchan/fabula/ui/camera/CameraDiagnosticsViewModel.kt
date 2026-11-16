package com.azuchan.fabula.ui.camera

import android.app.Application
import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.azuchan.fabula.camera.CameraCapabilityReport
import com.azuchan.fabula.camera.CameraDiagnosticsRepository
import com.azuchan.fabula.camera.toDiagnosticText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface CameraDiagnosticsUiState {
    data object Idle : CameraDiagnosticsUiState
    data object Loading : CameraDiagnosticsUiState
    data class Loaded(val reports: List<CameraCapabilityReport>) : CameraDiagnosticsUiState
    data class Failed(val message: String) : CameraDiagnosticsUiState
}

sealed interface CameraDiagnosticsExportState {
    data object Idle : CameraDiagnosticsExportState
    data object Saving : CameraDiagnosticsExportState
    data class Saved(val fileName: String) : CameraDiagnosticsExportState
    data class Failed(val message: String) : CameraDiagnosticsExportState
}

class CameraDiagnosticsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = CameraDiagnosticsRepository(application)
    private val mutableUiState = MutableStateFlow<CameraDiagnosticsUiState>(CameraDiagnosticsUiState.Idle)
    val uiState: StateFlow<CameraDiagnosticsUiState> = mutableUiState.asStateFlow()
    private val mutableExportState = MutableStateFlow<CameraDiagnosticsExportState>(CameraDiagnosticsExportState.Idle)
    val exportState: StateFlow<CameraDiagnosticsExportState> = mutableExportState.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            mutableUiState.value = CameraDiagnosticsUiState.Loading
            try {
                val reports = withContext(Dispatchers.IO) {
                    repository.loadReports()
                }
                mutableUiState.value = CameraDiagnosticsUiState.Loaded(reports)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (exception: Exception) {
                mutableUiState.value = CameraDiagnosticsUiState.Failed(
                    exception.message ?: "Could not read camera characteristics.",
                )
            }
        }
    }

    fun exportToDownloads() {
        val reports = (mutableUiState.value as? CameraDiagnosticsUiState.Loaded)?.reports
        if (reports == null) {
            mutableExportState.value = CameraDiagnosticsExportState.Failed(
                "No camera diagnostics are available to export.",
            )
            return
        }

        viewModelScope.launch {
            mutableExportState.value = CameraDiagnosticsExportState.Saving
            val fileName = "Fabula_Camera2_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.txt"
            try {
                withContext(Dispatchers.IO) {
                    saveTextToDownloads(fileName, reports.toDiagnosticText())
                }
                mutableExportState.value = CameraDiagnosticsExportState.Saved(fileName)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (exception: Exception) {
                mutableExportState.value = CameraDiagnosticsExportState.Failed(
                    exception.message ?: "Could not write to Downloads.",
                )
            }
        }
    }

    private fun saveTextToDownloads(fileName: String, text: String): Uri {
        val resolver = getApplication<Application>().contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore could not create the diagnostic file.")

        try {
            val output = resolver.openOutputStream(uri, "w")
                ?: throw IOException("Could not open the diagnostic file.")
            output.use { it.write(text.toByteArray(Charsets.UTF_8)) }

            val publishValues = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            if (resolver.update(uri, publishValues, null, null) <= 0) {
                throw IOException("Could not publish the file in Downloads.")
            }
            return uri
        } catch (exception: Exception) {
            resolver.delete(uri, null, null)
            throw exception
        }
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(CameraDiagnosticsViewModel::class.java)) {
                return CameraDiagnosticsViewModel(application) as T
            }
            throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
        }
    }
}
