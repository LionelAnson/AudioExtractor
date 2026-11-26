package com.local.audioextractor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ExtractionUiState(
    val isRunning: Boolean = false,
    val currentFileName: String = "",
    val progress: Int = 0,
    val currentIndex: Int = 0,
    val totalCount: Int = 0,
    val statusMessage: String = "",
    val isError: Boolean = false,
    val history: List<HistoryItem> = emptyList()
)

object ExtractionStateHolder {
    private val _state = MutableStateFlow(ExtractionUiState())
    val state: StateFlow<ExtractionUiState> = _state.asStateFlow()

    fun update(transform: (ExtractionUiState) -> ExtractionUiState) {
        _state.value = transform(_state.value)
    }
}
