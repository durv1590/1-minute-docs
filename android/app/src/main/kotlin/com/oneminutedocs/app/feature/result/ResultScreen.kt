package com.oneminutedocs.app.feature.result

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.oneminutedocs.app.R
import com.oneminutedocs.app.common.PrimaryAction
import com.oneminutedocs.app.common.ToolScaffold
import com.oneminutedocs.app.common.formatSize
import com.oneminutedocs.app.feature.documents.rememberDocumentActions
import com.oneminutedocs.app.navigation.Routes
import com.oneminutedocs.core.data.DocumentRepository
import com.oneminutedocs.core.data.ManagedDocument
import com.oneminutedocs.core.designsystem.InfoBanner
import com.oneminutedocs.core.designsystem.PillTone
import com.oneminutedocs.core.designsystem.StatusPill
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ResultViewModel @Inject constructor(savedState: SavedStateHandle, val repository: DocumentRepository) : ViewModel() {
    private val route = savedState.toRoute<Routes.Result>()
    val notice: String? = route.notice
    private val _docs = MutableStateFlow<List<ManagedDocument>>(emptyList())
    val docs: StateFlow<List<ManagedDocument>> = _docs.asStateFlow()

    init {
        viewModelScope.launch {
            // Only documents that exist in managed storage are shown; nothing is claimed that was not produced.
            _docs.value = route.documentIds.split(',').filter { it.isNotBlank() }.mapNotNull { repository.get(it) }.filter { it.file.isFile }
        }
    }

    suspend fun exportAll(tree: Uri): Int = repository.exportToTree(_docs.value.map { it.id }, tree)
}

@Composable
fun ResultScreen(onBack: () -> Unit, onPreview: (String) -> Unit, onHome: () -> Unit, vm: ResultViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val docs by vm.docs.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val actions = rememberDocumentActions(vm.repository) { msg -> scope.launch { snackbar.showSnackbar(context.getString(msg)) } }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) scope.launch {
            val n = vm.exportAll(uri)
            snackbar.showSnackbar(context.getString(R.string.msg_exported_count, n, docs.size))
        }
    }

    ToolScaffold(title = stringResource(R.string.result_title), onBack = onBack) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
            Text(
                if (docs.size == 1) stringResource(R.string.result_saved_one) else stringResource(R.string.result_saved_many, docs.size),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        vm.notice?.let { InfoBanner(it) }
        docs.forEach { d ->
            Card {
                ListItem(
                    leadingContent = { Icon(Icons.Outlined.Description, null) },
                    headlineContent = { Text(d.displayName) },
                    supportingContent = {
                        val pages = d.pageCount?.let { " · " + stringResource(R.string.details_pages, it) }.orEmpty()
                        Text("${d.format.extension.uppercase()} · ${formatSize(context, d.sizeBytes)}$pages")
                    },
                    overlineContent = if (d.aiGenerated) ({ StatusPill(stringResource(R.string.label_ai_generated), PillTone.AI) }) else null,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    IconButton(onClick = { onPreview(d.id) }) { Icon(Icons.Outlined.Visibility, stringResource(R.string.action_preview)) }
                    IconButton(onClick = { actions.export(d) }) { Icon(Icons.Outlined.FileDownload, stringResource(R.string.action_export)) }
                    IconButton(onClick = { actions.share(listOf(d)) }) { Icon(Icons.Outlined.Share, stringResource(R.string.action_share)) }
                    IconButton(onClick = { actions.openWith(d) }) { Icon(Icons.AutoMirrored.Outlined.OpenInNew, stringResource(R.string.action_open_with)) }
                }
            }
        }
        if (docs.size > 1) OutlinedButton(onClick = { folderPicker.launch(null) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_export_all)) }
        androidx.compose.material3.SnackbarHost(snackbar)
        PrimaryAction(stringResource(R.string.action_another)) { onBack() }
        OutlinedButton(onClick = onHome, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_home)) }
    }
}
