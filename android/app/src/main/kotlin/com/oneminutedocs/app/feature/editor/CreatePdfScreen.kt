package com.oneminutedocs.app.feature.editor

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oneminutedocs.app.R
import com.oneminutedocs.app.common.PrimaryAction
import com.oneminutedocs.app.common.ToolScaffold
import com.oneminutedocs.core.data.drafts.DraftRepository
import com.oneminutedocs.core.designsystem.SectionHeader
import com.oneminutedocs.document.ContentLanguage
import com.oneminutedocs.document.DocumentTemplate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class CreatePdfViewModel @Inject constructor(private val drafts: DraftRepository) : ViewModel() {
    fun start(title: String, markdown: String, onCreated: (String) -> Unit) {
        viewModelScope.launch { onCreated(drafts.create(title, markdown, aiGenerated = false, sourceOperation = "create_pdf").id) }
    }
}

/** Blank document or a fill-in template; both continue in the editor, where text can be changed before export. */
@Composable
fun CreatePdfScreen(initialTemplate: String?, onBack: () -> Unit, onOpenEditor: (String) -> Unit, vm: CreatePdfViewModel = hiltViewModel()) {
    var template by rememberSaveable { mutableStateOf(initialTemplate?.let { runCatching { DocumentTemplate.valueOf(it) }.getOrNull() }) }
    val lang = if (Locale.getDefault().language == "hi") ContentLanguage.HINDI else ContentLanguage.ENGLISH
    val values = remember(template) { mutableStateMapOf<String, String>() }
    var contentLang by rememberSaveable { mutableStateOf(lang) }
    val blankTitle = stringResource(R.string.create_blank)

    ToolScaffold(stringResource(R.string.tool_create_pdf), onBack = { if (template != null) template = null else onBack() }, availability = com.oneminutedocs.domain.Availability.OFFLINE) {
        val t = template
        if (t == null) {
            Card(onClick = { vm.start(blankTitle, "", onOpenEditor) }, modifier = Modifier.fillMaxWidth()) {
                ListItem(leadingContent = { Icon(Icons.Outlined.Description, null) }, headlineContent = { Text(blankTitle) }, supportingContent = { Text(stringResource(R.string.create_blank_desc)) })
            }
            SectionHeader(stringResource(R.string.create_templates))
            DocumentTemplate.entries.forEach { tpl ->
                Card(onClick = { template = tpl }, modifier = Modifier.fillMaxWidth()) {
                    ListItem(leadingContent = { Icon(Icons.AutoMirrored.Outlined.Article, null) }, headlineContent = { Text(tpl.title(lang)) })
                }
            }
        } else {
            Text(t.title(contentLang), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.create_language))
            androidx.compose.foundation.layout.Row {
                ContentLanguage.entries.forEach { l ->
                    androidx.compose.material3.FilterChip(selected = contentLang == l, onClick = { contentLang = l }, label = { Text(if (l == ContentLanguage.HINDI) "हिन्दी" else "English") })
                }
            }
            t.fields.forEach { f ->
                OutlinedTextField(
                    value = values[f.key].orEmpty(),
                    onValueChange = { values[f.key] = it },
                    label = { Text(f.label(lang) + if (f.required) " *" else "") },
                    singleLine = !f.multiline,
                    minLines = if (f.multiline) 3 else 1,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            val missing = t.missingRequired(values)
            if (missing.isNotEmpty()) Text(stringResource(R.string.create_required_hint), style = MaterialTheme.typography.bodySmall)
            PrimaryAction(stringResource(R.string.action_continue_editor), enabled = missing.isEmpty()) {
                vm.start(t.title(contentLang), t.render(contentLang, values.toMap()), onOpenEditor)
            }
        }
    }
}
