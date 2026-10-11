package com.oneminutedocs.app.common

import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.oneminutedocs.core.data.DocumentRepository
import com.oneminutedocs.core.data.LocalInput
import com.oneminutedocs.core.data.files.WorkspaceManager
import com.oneminutedocs.domain.DocError
import com.oneminutedocs.domain.DocumentFormat
import com.oneminutedocs.domain.ErrorCode
import com.oneminutedocs.domain.Outcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Tool ViewModel with validated inputs picked via SAF (copied privately) or taken from My Documents. */
abstract class InputsViewModel(workspaces: WorkspaceManager, protected val repository: DocumentRepository) : OperationViewModel(workspaces) {
    protected abstract val allowed: Set<DocumentFormat>
    protected open val maxInputs: Int = 1

    protected val _inputs = MutableStateFlow<List<LocalInput>>(emptyList())
    val inputs: StateFlow<List<LocalInput>> = _inputs.asStateFlow()

    private val _inputError = MutableStateFlow<DocError?>(null)
    val inputError: StateFlow<DocError?> = _inputError.asStateFlow()

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    fun addUris(uris: List<Uri>) {
        viewModelScope.launch {
            _importing.value = true
            _inputError.value = null
            val added = mutableListOf<LocalInput>()
            for (uri in uris) {
                if (_inputs.value.size + added.size >= maxInputs && maxInputs > 1) {
                    _inputError.value = DocError(ErrorCode.TOO_MANY_FILES)
                    break
                }
                when (val r = repository.importToWorkspace(uri, workspace, allowed)) {
                    is Outcome.Success -> added += r.value
                    is Outcome.Failure -> _inputError.value = r.error
                }
            }
            _inputs.value = if (maxInputs == 1) added.takeLast(1).ifEmpty { _inputs.value } else _inputs.value + added
            _importing.value = false
            onInputsChanged()
        }
    }

    /** Uses a document already in My Documents (opened read-only, not copied). */
    fun preload(documentId: String?) {
        if (documentId == null || _inputs.value.isNotEmpty()) return
        viewModelScope.launch {
            val doc = repository.get(documentId) ?: return@launch
            if (doc.format in allowed) {
                _inputs.value = listOf(repository.asInput(doc))
                onInputsChanged()
            }
        }
    }

    fun remove(index: Int) {
        _inputs.value = _inputs.value.filterIndexed { i, _ -> i != index }
        onInputsChanged()
    }

    fun move(index: Int, delta: Int) {
        val list = _inputs.value.toMutableList()
        val target = index + delta
        if (index !in list.indices || target !in list.indices) return
        list.add(target, list.removeAt(index))
        _inputs.value = list
        onInputsChanged()
    }

    fun clearInputError() {
        _inputError.value = null
    }

    protected open fun onInputsChanged() {}
}
