package com.oneminutedocs.app.feature.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oneminutedocs.app.R
import com.oneminutedocs.app.common.Tools
import com.oneminutedocs.app.common.formatDate
import com.oneminutedocs.app.common.formatSize
import com.oneminutedocs.core.designsystem.BrandMark
import com.oneminutedocs.core.designsystem.SectionHeader
import com.oneminutedocs.core.designsystem.ToolTile
import com.oneminutedocs.domain.ToolId
import com.oneminutedocs.domain.ToolSearch

enum class Category { PDF_TOOLS, SCAN, HANDWRITING, AI, CONVERSION, DOCUMENTS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onTool: (ToolId) -> Unit,
    onCategory: (Category) -> Unit,
    onOpenDocument: (String) -> Unit,
    onSettings: () -> Unit,
    onHelp: () -> Unit,
    vm: HomeViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val recent by vm.recent.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val freed by vm.freed.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.refreshStats() }
    LaunchedEffect(recent.size) { vm.refreshStats() }
    val freedMessage = freed?.let { stringResource(R.string.home_cleaned, formatSize(context, it)) }
    LaunchedEffect(freedMessage) {
        if (freedMessage != null) {
            snackbar.showSnackbar(freedMessage)
            vm.consumeFreed()
        }
    }

    val toolTitle: (ToolId) -> String = { context.getString(Tools.info(it).title) }
    val toolDesc: (ToolId) -> String = { context.getString(Tools.info(it).description) }
    val matchingTools = if (query.isBlank()) emptyList() else ToolSearch.filter(query, title = toolTitle, description = toolDesc)
    val matchingDocs = if (query.isBlank()) emptyList() else recent.filter { it.displayName.contains(query.trim(), ignoreCase = true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { BrandMark(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onHelp) { Icon(Icons.AutoMirrored.Outlined.HelpOutline, contentDescription = stringResource(R.string.action_help)) }
                    IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.settings_title)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 150.dp),
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            fullWidth {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    placeholder = { Text(stringResource(R.string.home_search_hint)) },
                    singleLine = true,
                )
            }
            if (query.isNotBlank()) {
                fullWidth { SectionHeader(stringResource(R.string.home_search_tools)) }
                if (matchingTools.isEmpty() && matchingDocs.isEmpty()) {
                    fullWidth { Text(stringResource(R.string.home_search_none), style = MaterialTheme.typography.bodyMedium) }
                }
                items(matchingTools) { id ->
                    val info = Tools.info(id)
                    ToolTile(title = stringResource(info.title), icon = info.icon, onClick = { onTool(id) }, subtitle = stringResource(info.description))
                }
                if (matchingDocs.isNotEmpty()) fullWidth { SectionHeader(stringResource(R.string.home_search_documents)) }
                matchingDocs.forEach { doc ->
                    fullWidth {
                        ListItem(
                            headlineContent = { Text(doc.displayName) },
                            supportingContent = { Text(formatSize(context, doc.sizeBytes)) },
                            leadingContent = { Icon(Icons.Outlined.Description, null) },
                            modifier = Modifier.clickable { onOpenDocument(doc.id) },
                        )
                    }
                }
                return@LazyVerticalGrid
            }

            fullWidth { SectionHeader(stringResource(R.string.home_quick_actions)) }
            fullWidth {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf(ToolId.CREATE_PDF, ToolId.SCAN_DOCUMENT, ToolId.COMPRESS_PDF, ToolId.MERGE_PDF, ToolId.PDF_TO_WORD, ToolId.HANDWRITTEN_NOTES, ToolId.AI_CREATOR)) { id ->
                        val info = Tools.info(id)
                        AssistChip(onClick = { onTool(id) }, label = { Text(stringResource(info.title)) }, leadingIcon = { Icon(info.icon, null) })
                    }
                }
            }

            fullWidth { SectionHeader(stringResource(R.string.home_categories)) }
            val categories = listOf(
                Triple(Category.PDF_TOOLS, R.string.category_pdf_tools, Icons.Outlined.Build),
                Triple(Category.SCAN, R.string.category_scan, Icons.Outlined.DocumentScanner),
                Triple(Category.HANDWRITING, R.string.category_handwriting, Icons.Outlined.EditNote),
                Triple(Category.AI, R.string.category_ai, Icons.Outlined.AutoAwesome),
                Triple(Category.CONVERSION, R.string.category_conversion, Icons.Outlined.SwapHoriz),
                Triple(Category.DOCUMENTS, R.string.category_documents, Icons.Outlined.Folder),
            )
            items(categories) { (cat, label, icon) ->
                ToolTile(title = stringResource(label), icon = icon, onClick = { onCategory(cat) })
            }

            fullWidth { SectionHeader(stringResource(R.string.home_recent)) }
            if (recent.isEmpty()) {
                fullWidth { Text(stringResource(R.string.home_recent_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            recent.take(5).forEach { doc ->
                fullWidth {
                    Card(onClick = { onOpenDocument(doc.id) }) {
                        ListItem(
                            leadingContent = { Icon(Icons.Outlined.Description, null) },
                            headlineContent = { Text(doc.displayName, maxLines = 1) },
                            supportingContent = {
                                Text("${doc.format.extension.uppercase()} · ${formatSize(context, doc.sizeBytes)} · ${formatDate(context, doc.modifiedAt)}")
                            },
                        )
                    }
                }
            }

            fullWidth {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.home_storage), style = MaterialTheme.typography.titleMedium)
                        val s = stats
                        if (s == null) {
                            Text(stringResource(R.string.home_storage_measuring))
                        } else {
                            Text(stringResource(R.string.home_storage_documents, s.documentCount, formatSize(context, s.documentBytes)))
                            Text(stringResource(R.string.home_storage_temp, formatSize(context, s.temporaryBytes)))
                            Text(stringResource(R.string.home_storage_free, formatSize(context, s.freeBytes)))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(onClick = vm::cleanTemporary) {
                                Icon(Icons.Outlined.CleaningServices, null)
                                Text(stringResource(R.string.home_storage_clean), modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.grid.LazyGridScope.fullWidth(content: @Composable () -> Unit) {
    item(span = { GridItemSpan(maxLineSpan) }) { content() }
}
