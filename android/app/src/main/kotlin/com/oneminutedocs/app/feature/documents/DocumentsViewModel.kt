package com.oneminutedocs.app.feature.documents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oneminutedocs.core.data.DocumentRepository
import com.oneminutedocs.core.data.ManagedDocument
import com.oneminutedocs.domain.DocumentFormat
import com.oneminutedocs.domain.Outcome
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SortOrder { NEWEST, OLDEST, NAME, SIZE }
enum class TypeFilter { ALL, PDF, WORD, IMAGES, TEXT }
enum class DateFilter { ANY, TODAY, WEEK, MONTH }

data class DocumentsQuery(
    val text: String = "",
    val sort: SortOrder = SortOrder.NEWEST,
    val type: TypeFilter = TypeFilter.ALL,
    val date: DateFilter = DateFilter.ANY,
)

@HiltViewModel
class DocumentsViewModel @Inject constructor(val repository: DocumentRepository) : ViewModel() {
    val query = MutableStateFlow(DocumentsQuery())
    val selection = MutableStateFlow<Set<String>>(emptySet())

    val documents: StateFlow<List<ManagedDocument>> = combine(repository.observeDocuments(), query) { docs, q -> apply(docs, q, System.currentTimeMillis()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun toggle(id: String) {
        selection.value = selection.value.let { if (id in it) it - id else it + id }
    }

    fun clearSelection() {
        selection.value = emptySet()
    }

    fun deleteSelected() {
        val ids = selection.value.toList()
        viewModelScope.launch {
            repository.delete(ids)
            clearSelection()
        }
    }

    fun rename(id: String, name: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch { onResult(repository.rename(id, name) is Outcome.Success) }
    }

    companion object {
        fun apply(docs: List<ManagedDocument>, q: DocumentsQuery, now: Long): List<ManagedDocument> {
            val day = 86_400_000L
            val minTime = when (q.date) {
                DateFilter.ANY -> Long.MIN_VALUE
                DateFilter.TODAY -> now - day
                DateFilter.WEEK -> now - 7 * day
                DateFilter.MONTH -> now - 30 * day
            }
            val filtered = docs.filter { d ->
                d.modifiedAt >= minTime &&
                    (q.text.isBlank() || d.displayName.contains(q.text.trim(), ignoreCase = true)) &&
                    when (q.type) {
                        TypeFilter.ALL -> true
                        TypeFilter.PDF -> d.format == DocumentFormat.PDF
                        TypeFilter.WORD -> d.format.isOfficeDocument
                        TypeFilter.IMAGES -> d.format.isImage
                        TypeFilter.TEXT -> d.format == DocumentFormat.TXT
                    }
            }
            return when (q.sort) {
                SortOrder.NEWEST -> filtered.sortedByDescending { it.modifiedAt }
                SortOrder.OLDEST -> filtered.sortedBy { it.modifiedAt }
                SortOrder.NAME -> filtered.sortedBy { it.displayName.lowercase() }
                SortOrder.SIZE -> filtered.sortedByDescending { it.sizeBytes }
            }
        }
    }
}
