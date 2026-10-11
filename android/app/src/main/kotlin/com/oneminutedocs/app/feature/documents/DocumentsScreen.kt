package com.oneminutedocs.app.feature.documents

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oneminutedocs.app.R
import com.oneminutedocs.app.common.formatDate
import com.oneminutedocs.app.common.formatSize
import com.oneminutedocs.core.data.ManagedDocument
import com.oneminutedocs.core.designsystem.ConfirmDialog
import com.oneminutedocs.core.designsystem.EmptyState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DocumentsScreen(onBack: () -> Unit, onOpen: (String) -> Unit, vm: DocumentsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val docs by vm.documents.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val actions = rememberDocumentActions(vm.repository) { msg -> scope.launch { snackbar.showSnackbar(context.getString(msg)) } }
    var confirmDelete by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<ManagedDocument?>(null) }
    var details by remember { mutableStateOf<ManagedDocument?>(null) }
    val selectedDocs = docs.filter { it.id in selection }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (selection.isEmpty()) stringResource(R.string.documents_title) else stringResource(R.string.documents_selected, selection.size)) },
                navigationIcon = {
                    IconButton(onClick = { if (selection.isEmpty()) onBack() else vm.clearSelection() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (selection.isNotEmpty()) {
                        IconButton(onClick = { actions.share(selectedDocs) }) { Icon(Icons.Outlined.Share, stringResource(R.string.action_share)) }
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, stringResource(R.string.action_delete)) }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                OutlinedTextField(
                    value = query.text,
                    onValueChange = { vm.query.value = query.copy(text = it) },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    placeholder = { Text(stringResource(R.string.documents_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(TypeFilter.entries) { t ->
                        FilterChip(selected = query.type == t, onClick = { vm.query.value = query.copy(type = t) }, label = { Text(stringResource(typeLabel(t))) })
                    }
                }
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(DateFilter.entries) { d ->
                        FilterChip(selected = query.date == d, onClick = { vm.query.value = query.copy(date = d) }, label = { Text(stringResource(dateLabel(d))) })
                    }
                    items(SortOrder.entries) { s ->
                        FilterChip(selected = query.sort == s, onClick = { vm.query.value = query.copy(sort = s) }, label = { Text(stringResource(sortLabel(s))) })
                    }
                }
            }
            if (docs.isEmpty()) {
                item { EmptyState(Icons.Outlined.FolderOpen, stringResource(R.string.documents_empty_title), stringResource(R.string.documents_empty_body)) }
            }
            items(docs, key = { it.id }) { doc ->
                val selected = doc.id in selection
                ListItem(
                    modifier = Modifier.combinedClickable(
                        onClick = { if (selection.isEmpty()) onOpen(doc.id) else vm.toggle(doc.id) },
                        onLongClick = { vm.toggle(doc.id) },
                        onLongClickLabel = stringResource(R.string.action_select),
                    ),
                    leadingContent = { Icon(if (selected) Icons.Outlined.CheckCircle else Icons.Outlined.Description, null, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) },
                    headlineContent = { Text(doc.displayName, maxLines = 2) },
                    supportingContent = { Text("${doc.format.extension.uppercase()} · ${formatSize(context, doc.sizeBytes)} · ${formatDate(context, doc.modifiedAt)}") },
                    trailingContent = {
                        if (selection.isEmpty()) {
                            androidx.compose.foundation.layout.Row {
                                IconButton(onClick = { renaming = doc }) { Icon(Icons.Outlined.DriveFileRenameOutline, stringResource(R.string.action_rename)) }
                                IconButton(onClick = { details = doc }) { Icon(Icons.Outlined.Info, stringResource(R.string.action_details)) }
                            }
                        }
                    },
                )
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.documents_delete_title),
            message = stringResource(R.string.documents_delete_body, selection.size),
            confirmLabel = stringResource(R.string.action_delete),
            dismissLabel = stringResource(R.string.action_cancel),
            onConfirm = { confirmDelete = false; vm.deleteSelected() },
            onDismiss = { confirmDelete = false },
            destructive = true,
        )
    }
    renaming?.let { doc ->
        RenameDialog(doc.displayName, onDismiss = { renaming = null }) { name ->
            vm.rename(doc.id, name) { ok -> scope.launch { snackbar.showSnackbar(context.getString(if (ok) R.string.msg_renamed else R.string.error_unknown)) } }
            renaming = null
        }
    }
    details?.let { doc -> DetailsDialog(doc) { details = null } }
}

@Composable
fun RenameDialog(current: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var name by remember { mutableStateOf(current.substringBeforeLast('.')) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_rename)) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text(stringResource(R.string.label_file_name)) }) },
        confirmButton = { TextButton(onClick = { onRename(name) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
fun DetailsDialog(doc: ManagedDocument, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(doc.displayName) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.details_type, doc.format.extension.uppercase()))
                Text(stringResource(R.string.details_size, formatSize(context, doc.sizeBytes)))
                doc.pageCount?.let { Text(stringResource(R.string.details_pages, it)) }
                Text(stringResource(R.string.details_created, formatDate(context, doc.createdAt)))
                Text(stringResource(R.string.details_modified, formatDate(context, doc.modifiedAt)))
                if (doc.aiGenerated) Text(stringResource(R.string.details_ai_generated))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) } },
    )
}

private fun typeLabel(t: TypeFilter) = when (t) {
    TypeFilter.ALL -> R.string.filter_all
    TypeFilter.PDF -> R.string.filter_pdf
    TypeFilter.WORD -> R.string.filter_word
    TypeFilter.IMAGES -> R.string.filter_images
    TypeFilter.TEXT -> R.string.filter_text
}

private fun dateLabel(d: DateFilter) = when (d) {
    DateFilter.ANY -> R.string.filter_any_date
    DateFilter.TODAY -> R.string.filter_today
    DateFilter.WEEK -> R.string.filter_week
    DateFilter.MONTH -> R.string.filter_month
}

private fun sortLabel(s: SortOrder) = when (s) {
    SortOrder.NEWEST -> R.string.sort_newest
    SortOrder.OLDEST -> R.string.sort_oldest
    SortOrder.NAME -> R.string.sort_name
    SortOrder.SIZE -> R.string.sort_size
}
