@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.oneminutedocs.app.feature.editor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oneminutedocs.app.R
import com.oneminutedocs.app.common.CloudConsentDialog
import com.oneminutedocs.app.common.OpState
import com.oneminutedocs.app.common.OperationStatus
import com.oneminutedocs.app.common.PrimaryAction
import com.oneminutedocs.app.common.ToolScaffold
import com.oneminutedocs.app.common.errorMessage
import com.oneminutedocs.app.feature.pdf.PageOptions
import com.oneminutedocs.core.designsystem.ErrorBlock
import com.oneminutedocs.core.designsystem.InfoBanner
import com.oneminutedocs.core.designsystem.PillTone
import com.oneminutedocs.core.designsystem.StatusPill
import com.oneminutedocs.document.Block
import com.oneminutedocs.document.MarkdownLite
import com.oneminutedocs.document.TextRun
import com.oneminutedocs.domain.DocumentFormat
import com.oneminutedocs.processing.pdf.FontFamily
import kotlinx.coroutines.launch

@Composable
fun EditorScreen(onBack: () -> Unit, onDone: (OpState.Done) -> Unit, vm: EditorViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val text by vm.text.collectAsStateWithLifecycle()
    val title by vm.title.collectAsStateWithLifecycle()
    val ai by vm.aiGenerated.collectAsStateWithLifecycle()
    val export by vm.export.collectAsStateWithLifecycle()
    val op by vm.op.collectAsStateWithLifecycle()
    val missing by vm.missing.collectAsStateWithLifecycle()
    val aiBusy by vm.aiBusy.collectAsStateWithLifecycle()
    val aiError by vm.aiError.collectAsStateWithLifecycle()
    val pendingCloud by vm.gate.pending.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var preview by rememberSaveable { mutableStateOf(false) }
    var aiMenu by remember { mutableStateOf(false) }

    ToolScaffold(
        stringResource(R.string.editor_title), onBack,
        actions = {
            IconButton(onClick = vm::undo) { Icon(Icons.AutoMirrored.Outlined.Undo, stringResource(R.string.action_undo)) }
            IconButton(onClick = vm::redo) { Icon(Icons.AutoMirrored.Outlined.Redo, stringResource(R.string.action_redo)) }
            IconButton(onClick = { preview = !preview }) {
                Icon(if (preview) Icons.Outlined.Edit else Icons.Outlined.Visibility, stringResource(if (preview) R.string.action_edit else R.string.action_preview))
            }
        },
    ) {
        if (OperationStatus(op, vm::cancel, vm::dismissResult, onDone)) return@ToolScaffold
        if (missing) {
            ErrorBlock(stringResource(R.string.error_title), stringResource(R.string.editor_missing))
            return@ToolScaffold
        }
        if (ai) {
            StatusPill(stringResource(R.string.label_ai_generated), PillTone.AI)
            InfoBanner(stringResource(R.string.ai_review_notice))
        }
        OutlinedTextField(value = title, onValueChange = { vm.title.value = it }, label = { Text(stringResource(R.string.label_title)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (!preview) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistChip(onClick = { vm.applyLinePrefix("# ") }, label = { Text(stringResource(R.string.fmt_h1)) })
                AssistChip(onClick = { vm.applyLinePrefix("## ") }, label = { Text(stringResource(R.string.fmt_h2)) })
                AssistChip(onClick = { vm.applyLinePrefix("- ") }, label = { Text(stringResource(R.string.fmt_bullet)) })
                AssistChip(onClick = { vm.applyLinePrefix("1. ") }, label = { Text(stringResource(R.string.fmt_numbered)) })
                AssistChip(onClick = { vm.wrapSelection("**") }, label = { Text(stringResource(R.string.fmt_bold)) })
                AssistChip(onClick = { vm.wrapSelection("*") }, label = { Text(stringResource(R.string.fmt_italic)) })
                Row {
                    AssistChip(onClick = { aiMenu = true }, label = { Text(stringResource(R.string.editor_ai_actions)) }, leadingIcon = { Icon(Icons.Outlined.AutoAwesome, null) }, enabled = !aiBusy)
                    DropdownMenu(expanded = aiMenu, onDismissRequest = { aiMenu = false }) {
                        AiAction.entries.forEach { a ->
                            DropdownMenuItem(text = { Text(stringResource(aiLabel(a))) }, onClick = { aiMenu = false; vm.runAi(a) })
                        }
                    }
                }
            }
            if (aiBusy) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.padding(4.dp))
                Text(stringResource(R.string.ai_working))
            }
            aiError?.let { ErrorBlock(stringResource(R.string.error_title), stringResource(errorMessage(it.code)), retryLabel = stringResource(R.string.action_ok), onRetry = vm::clearAiError) }
            OutlinedTextField(
                value = text,
                onValueChange = vm::onTextChange,
                modifier = Modifier.fillMaxWidth().heightIn(min = 280.dp),
                label = { Text(stringResource(R.string.editor_content)) },
                supportingText = { Text(stringResource(R.string.editor_syntax_help) + " · " + stringResource(R.string.editor_words, vm.wordCount)) },
            )
        } else {
            DocumentPreview(MarkdownLite.parse(text.text).blocks)
        }
        HorizontalDivider()
        Text(stringResource(R.string.export_title), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = export.format == DocumentFormat.PDF, onClick = { vm.export.value = export.copy(format = DocumentFormat.PDF) }, label = { Text("PDF") })
            FilterChip(selected = export.format == DocumentFormat.DOCX, onClick = { vm.export.value = export.copy(format = DocumentFormat.DOCX) }, label = { Text("Word (DOCX)") })
        }
        PageOptions(export.pageSize, export.orientation, export.margins,
            onSize = { vm.export.value = export.copy(pageSize = it) },
            onOrientation = { vm.export.value = export.copy(orientation = it) },
            onMargins = { vm.export.value = export.copy(margins = it) },
        )
        if (export.format == DocumentFormat.PDF) {
            Text(stringResource(R.string.label_font), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FontFamily.entries.forEach { f ->
                    FilterChip(selected = export.font == f, onClick = { vm.export.value = export.copy(font = f) }, label = {
                        Text(stringResource(when (f) { FontFamily.SANS -> R.string.font_sans; FontFamily.SERIF -> R.string.font_serif; FontFamily.MONO -> R.string.font_mono }))
                    })
                }
                listOf(10f, 11f, 12f, 14f).forEach { size ->
                    FilterChip(selected = export.fontSize == size, onClick = { vm.export.value = export.copy(fontSize = size) }, label = { Text("${size.toInt()} pt") })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.label_page_numbers), modifier = Modifier.weight(1f))
                Switch(checked = export.pageNumbers, onCheckedChange = { vm.export.value = export.copy(pageNumbers = it) })
            }
            OutlinedTextField(value = export.header, onValueChange = { vm.export.value = export.copy(header = it) }, label = { Text(stringResource(R.string.label_header)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = export.footer, onValueChange = { vm.export.value = export.copy(footer = it) }, label = { Text(stringResource(R.string.label_footer)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        OutlinedTextField(value = export.fileName, onValueChange = { vm.export.value = export.copy(fileName = it) }, label = { Text(stringResource(R.string.label_output_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        PrimaryAction(stringResource(R.string.action_export_document), enabled = text.text.isNotBlank()) {
            vm.exportDocument { n -> context.getString(R.string.page_number, n) }
        }
    }
    if (pendingCloud != null) {
        CloudConsentDialog(stringResource(R.string.consent_upload_text), onAccept = { r -> scope.launch { vm.gate.accept(r) } }, onDecline = vm.gate::decline)
    }
}

private fun aiLabel(a: AiAction) = when (a) {
    AiAction.REWRITE -> R.string.ai_rewrite
    AiAction.GRAMMAR -> R.string.ai_grammar
    AiAction.SUMMARIZE -> R.string.ai_summarize
    AiAction.TRANSLATE_HI -> R.string.ai_translate_hi
    AiAction.TRANSLATE_EN -> R.string.ai_translate_en
    AiAction.FORMAL -> R.string.ai_formal
    AiAction.ORGANIZE -> R.string.ai_organize
}

/** Read-only rendering of the block model, matching what export will produce. */
@Composable
fun DocumentPreview(blocks: List<Block>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        var number = 0
        blocks.forEach { b ->
            if (b !is Block.ListItem || !b.ordered) number = 0
            when (b) {
                is Block.Heading -> Text(
                    runs(b.runs),
                    style = when (b.level) { 1 -> MaterialTheme.typography.headlineMedium; 2 -> MaterialTheme.typography.titleLarge; else -> MaterialTheme.typography.titleMedium },
                    modifier = Modifier.semantics { heading() },
                )
                is Block.Paragraph -> Text(runs(b.runs), style = MaterialTheme.typography.bodyLarge)
                is Block.ListItem -> {
                    val marker = if (b.ordered) "${++number}." else "•"
                    Row(Modifier.padding(start = (16 * (b.level + 1)).dp)) {
                        Text("$marker ", style = MaterialTheme.typography.bodyLarge)
                        Text(runs(b.runs), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                is Block.Table -> Column {
                    b.rows.forEachIndexed { i, row ->
                        Row(Modifier.fillMaxWidth()) {
                            row.forEach { cell ->
                                Text(cell, modifier = Modifier.weight(1f).padding(4.dp), fontWeight = if (i == 0 && b.hasHeader) FontWeight.Bold else null)
                            }
                        }
                        HorizontalDivider()
                    }
                }
                Block.PageBreak -> HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

private fun runs(runs: List<TextRun>): AnnotatedString = buildAnnotatedString {
    runs.forEach { r ->
        withStyle(SpanStyle(
            fontWeight = if (r.bold) FontWeight.Bold else null,
            fontStyle = if (r.italic) FontStyle.Italic else null,
            textDecoration = if (r.underline) TextDecoration.Underline else null,
        )) { append(r.text) }
    }
}
