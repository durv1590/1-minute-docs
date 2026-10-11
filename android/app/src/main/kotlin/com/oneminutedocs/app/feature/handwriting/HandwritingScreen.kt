@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.oneminutedocs.app.feature.handwriting

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oneminutedocs.app.R
import com.oneminutedocs.app.common.AvailabilityPill
import com.oneminutedocs.app.common.CloudConsentDialog
import com.oneminutedocs.app.common.Mime
import com.oneminutedocs.app.common.OpState
import com.oneminutedocs.app.common.OperationStatus
import com.oneminutedocs.app.common.PrimaryAction
import com.oneminutedocs.app.common.ToolScaffold
import com.oneminutedocs.app.common.errorMessage
import com.oneminutedocs.app.common.rememberPickers
import com.oneminutedocs.core.designsystem.ErrorBlock
import com.oneminutedocs.core.designsystem.InfoBanner
import com.oneminutedocs.core.designsystem.PillTone
import com.oneminutedocs.core.designsystem.StatusPill
import com.oneminutedocs.domain.Availability
import com.oneminutedocs.processing.image.BitmapLoader
import kotlinx.coroutines.launch

@Composable
fun HandwritingScreen(
    onBack: () -> Unit,
    onCamera: (sessionId: String) -> Unit,
    onReview: (sessionId: String) -> Unit,
    onEditor: (draftId: String) -> Unit,
    onDone: (OpState.Done) -> Unit,
    vm: HandwritingViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val step by vm.step.collectAsStateWithLifecycle()
    val pages by vm.pages.collectAsStateWithLifecycle()
    val op by vm.op.collectAsStateWithLifecycle()
    val texts by vm.pageTexts.collectAsStateWithLifecycle()
    val language by vm.language.collectAsStateWithLifecycle()
    val output by vm.output.collectAsStateWithLifecycle()
    val importError by vm.importError.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val pending by vm.gate.pending.collectAsStateWithLifecycle()
    val recognizedBy by vm.recognizedBy.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val imagePickers = rememberPickers(vm::importImages)
    val pdfPicker = rememberPickers { uris -> uris.firstOrNull()?.let(vm::importPdf) }

    ToolScaffold(stringResource(R.string.tool_handwriting), onBack = { if (step == HwStep.PAGES) onBack() else vm.back() }, availability = Availability.OFFLINE_WITH_ONLINE_ENHANCEMENT) {
        if (OperationStatus(op, vm::cancel, vm::dismissResult) { if (it.documentIds.isNotEmpty()) onDone(it) else vm.dismissResult() }) return@ToolScaffold
        importError?.let { ErrorBlock(stringResource(R.string.error_title), stringResource(errorMessage(it.code)), retryLabel = stringResource(R.string.action_ok), onRetry = { vm.importError.value = null }) }
        when (step) {
            HwStep.PAGES -> {
                Text(stringResource(R.string.hw_intro))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onCamera(vm.sessionId) }) { Text(stringResource(R.string.action_capture)) }
                    OutlinedButton(onClick = imagePickers.pickImages) { Text(stringResource(R.string.action_import_photos)) }
                    OutlinedButton(onClick = { pdfPicker.pickOne(Mime.PDF) }) { Text("PDF") }
                }
                if (busy) CircularProgressIndicator()
                Text(stringResource(R.string.hw_pages_count, pages.size), style = MaterialTheme.typography.titleSmall)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(pages, key = { _, p -> p.id }) { i, p ->
                        val thumb by produceState<Bitmap?>(null, p) { value = runCatching { vm.store.render(p, 400) }.getOrNull() }
                        Box(Modifier.width(80.dp).height(110.dp), contentAlignment = Alignment.Center) {
                            thumb?.let { Image(it.asImageBitmap(), stringResource(R.string.viewer_page_of, i + 1, pages.size), Modifier.fillMaxSize()) } ?: CircularProgressIndicator()
                        }
                    }
                }
                if (pages.isNotEmpty()) {
                    OutlinedButton(onClick = { onReview(vm.sessionId) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_review_enhance)) }
                    PrimaryAction(stringResource(R.string.action_continue)) { vm.step.value = HwStep.OUTPUT }
                }
            }
            HwStep.OUTPUT -> {
                Text(stringResource(R.string.hw_choose_output), style = MaterialTheme.typography.titleMedium)
                OutputCard(Icons.Outlined.PictureAsPdf, R.string.hw_output_scanned, R.string.hw_output_scanned_desc) { vm.chooseOutput(HwOutput.SCANNED_PDF) }
                OutputCard(Icons.AutoMirrored.Outlined.Article, R.string.hw_output_editable, R.string.hw_output_editable_desc) { vm.chooseOutput(HwOutput.EDITABLE_PDF) }
                OutputCard(Icons.Outlined.Description, R.string.hw_output_word, R.string.hw_output_word_desc) { vm.chooseOutput(HwOutput.WORD) }
                OutputCard(Icons.Outlined.AutoAwesome, R.string.hw_output_ai, R.string.hw_output_ai_desc) { vm.chooseOutput(HwOutput.AI_NOTES) }
            }
            HwStep.RECOGNIZE -> {
                Text(stringResource(R.string.label_language), style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HwLanguage.entries.forEach { l ->
                        FilterChip(selected = language == l, onClick = { vm.language.value = l }, label = { Text(if (l == HwLanguage.HINDI) "हिन्दी" else "English") })
                    }
                }
                InfoBanner(stringResource(R.string.hw_engine_device_note))
                PrimaryAction(stringResource(R.string.action_recognize_device)) { vm.recognize(HwEngine.DEVICE) }
                AvailabilityPill(Availability.OFFLINE)
                InfoBanner(stringResource(R.string.hw_engine_cloud_note))
                OutlinedButton(onClick = { vm.recognize(HwEngine.CLOUD) }, enabled = vm.gate.serverConfigured, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_recognize_cloud_hw)) }
                AvailabilityPill(Availability.INTERNET_REQUIRED)
                if (!vm.gate.serverConfigured) Text(stringResource(R.string.error_server_not_configured), style = MaterialTheme.typography.bodySmall)
            }
            HwStep.REVIEW -> {
                if (recognizedBy == HwEngine.CLOUD) StatusPill(stringResource(R.string.label_ai_recognized), PillTone.AI)
                InfoBanner(stringResource(R.string.hw_review_hint))
                texts.forEachIndexed { i, t ->
                    var showOriginal by rememberSaveable(i) { mutableStateOf(false) }
                    Card {
                        androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().heightIn(min = 80.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.viewer_page_of, i + 1, texts.size), modifier = Modifier.weight(1f).heightIn(min = 48.dp), style = MaterialTheme.typography.titleSmall)
                                FilterChip(selected = showOriginal, onClick = { showOriginal = !showOriginal }, label = { Text(stringResource(R.string.review_original)) })
                            }
                            if (showOriginal) pages.getOrNull(i)?.let { p ->
                                val bmp by produceState<Bitmap?>(null, p) { value = runCatching { BitmapLoader.decode(p.original, 1200) }.getOrNull() }
                                bmp?.let { Image(it.asImageBitmap(), stringResource(R.string.review_original), Modifier.fillMaxWidth().aspectRatio(it.width.toFloat() / it.height)) }
                            }
                            OutlinedTextField(value = t, onValueChange = { vm.editPage(i, it) }, modifier = Modifier.fillMaxWidth(), minLines = 4,
                                placeholder = { Text(stringResource(R.string.hw_empty_page)) })
                        }
                    }
                }
                when (output) {
                    HwOutput.EDITABLE_PDF -> PrimaryAction(stringResource(R.string.action_create_editable_pdf)) { vm.createEditablePdf { context.getString(R.string.page_number, it) } }
                    HwOutput.WORD -> PrimaryAction(stringResource(R.string.action_create_word)) { vm.createWord() }
                    HwOutput.AI_NOTES -> {
                        Text(stringResource(R.string.hw_ai_choose), style = MaterialTheme.typography.titleSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NotesAction.entries.forEach { a ->
                                OutlinedButton(onClick = { vm.aiNotes(a, onEditor) }) { Text(stringResource(notesLabel(a))) }
                            }
                        }
                        Text(stringResource(R.string.hw_ai_note), style = MaterialTheme.typography.bodySmall)
                    }
                    HwOutput.SCANNED_PDF -> Unit
                }
                OutlinedButton(onClick = { vm.openInEditor(onEditor) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_open_editor)) }
            }
        }
    }
    if (pending != null) {
        CloudConsentDialog(
            stringResource(if (step == HwStep.RECOGNIZE) R.string.consent_upload_notes else R.string.consent_upload_text),
            onAccept = { r -> scope.launch { vm.gate.accept(r) } },
            onDecline = vm.gate::decline,
        )
    }
}

@Composable
private fun OutputCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: Int, desc: Int, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        ListItem(leadingContent = { Icon(icon, null) }, headlineContent = { Text(stringResource(title)) }, supportingContent = { Text(stringResource(desc)) })
    }
}

private fun notesLabel(a: NotesAction) = when (a) {
    NotesAction.ORGANIZE -> R.string.notes_organize
    NotesAction.SUMMARY -> R.string.notes_summary
    NotesAction.REVISION -> R.string.notes_revision
    NotesAction.QUESTIONS -> R.string.notes_questions
    NotesAction.FLASHCARDS -> R.string.notes_flashcards
    NotesAction.GRAMMAR -> R.string.notes_grammar
    NotesAction.TRANSLATE -> R.string.notes_translate
}
