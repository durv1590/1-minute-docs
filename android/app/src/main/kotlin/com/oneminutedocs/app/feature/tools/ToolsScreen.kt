package com.oneminutedocs.app.feature.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.oneminutedocs.app.R
import com.oneminutedocs.app.common.AvailabilityPill
import com.oneminutedocs.app.common.Tools
import com.oneminutedocs.domain.ProcessingLimits
import com.oneminutedocs.domain.ToolCategory
import com.oneminutedocs.domain.ToolId
import com.oneminutedocs.domain.ToolSearch

/** Searchable catalogue: every tool with description, formats, limits and where it runs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(onBack: () -> Unit, onTool: (ToolId) -> Unit, categories: Set<ToolCategory>? = null) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    val pool = ToolId.entries.filter { categories == null || it.category in categories }
    val tools = ToolSearch.filter(query, pool, { context.getString(Tools.info(it).title) }, { context.getString(Tools.info(it).description) })
    val limitMb = ProcessingLimits.DEFAULT.maxPdfBytes / ProcessingLimits.MB
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tools_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    placeholder = { Text(stringResource(R.string.tools_search_hint)) },
                    singleLine = true,
                )
            }
            if (tools.isEmpty()) item { Text(stringResource(R.string.home_search_none)) }
            items(tools, key = { it.name }) { id ->
                val info = Tools.info(id)
                Card(onClick = { onTool(id) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(info.icon, null, tint = MaterialTheme.colorScheme.primary)
                            Text(stringResource(info.title), style = MaterialTheme.typography.titleMedium)
                        }
                        Text(stringResource(info.description), style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(info.formats), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (id.inputs.isNotEmpty()) {
                            Text(stringResource(R.string.tools_limit, limitMb), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        AvailabilityPill(id.availability)
                    }
                }
            }
        }
    }
}
