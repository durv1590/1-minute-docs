package com.oneminutedocs.app.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oneminutedocs.core.data.DocumentRepository
import com.oneminutedocs.core.data.ManagedDocument
import com.oneminutedocs.core.data.StorageStats
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(private val documents: DocumentRepository) : ViewModel() {
    val recent: StateFlow<List<ManagedDocument>> = documents.observeDocuments().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _stats = MutableStateFlow<StorageStats?>(null)
    val stats: StateFlow<StorageStats?> = _stats.asStateFlow()

    private val _freed = MutableStateFlow<Long?>(null)
    val freed: StateFlow<Long?> = _freed.asStateFlow()

    fun refreshStats() {
        viewModelScope.launch { _stats.value = documents.storageStats() }
    }

    fun cleanTemporary() {
        viewModelScope.launch {
            _freed.value = documents.clearTemporaryFiles(maxAgeMs = 0L)
            refreshStats()
        }
    }

    fun consumeFreed() {
        _freed.value = null
    }
}
