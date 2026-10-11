@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.oneminutedocs.app.feature.ai

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.oneminutedocs.app.R
import com.oneminutedocs.app.common.CloudConsentDialog
import com.oneminutedocs.app.common.CloudGate
import com.oneminutedocs.app.common.OpState
import com.oneminutedocs.app.common.OperationStatus
import com.oneminutedocs.app.common.OperationViewModel
import com.oneminutedocs.app.common.PrimaryAction
import com.oneminutedocs.app.common.ToolScaffold
import com.oneminutedocs.core.data.drafts.DraftRepository
import com.oneminutedocs.core.data.files.WorkspaceManager
import com.oneminutedocs.core.designsystem.InfoBanner
import com.oneminutedocs.core.network.AiRequestDto
import com.oneminutedocs.core.network.BackendClient
import com.oneminutedocs.domain.Availability
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

enum class AiDocType(val api: String, @StringRes val label: Int) {
    LETTER("letter", R.string.doc_letter),
    LEAVE("leave_application", R.string.doc_leave),
    RESUME("resume", R.string.doc_resume),
    COVER_LETTER("cover_letter", R.string.doc_cover_letter),
    BUSINESS_REPORT("business_report", R.string.doc_business_report),
    MINUTES("meeting_minutes", R.string.doc_minutes),
    STUDY_NOTES("study_notes", R.string.doc_study_notes),
    SUMMARY("summary", R.string.doc_summary),
    PROJECT_REPORT("project_report", R.string.doc_project_report),
    PROPOSAL("proposal", R.string.doc_proposal),
    INVOICE("invoice", R.string.doc_invoice),
    APPLICATION("formal_application", R.string.doc_application),
    NOTICE("notice", R.string.doc_notice),
    GENERAL("general", R.string.doc_general),
}

enum class AiTone(val api: String, @StringRes val label: Int) {
    FORMAL("formal", R.string.tone_formal), FRIENDLY("friendly", R.string.tone_friendly), CONCISE("concise", R.string.tone_concise),
    DETAILED("detailed", R.string.tone_detailed), PERSUASIVE("persuasive", R.string.tone_persuasive),
}

data class AiForm(
    val type: AiDocType = AiDocType.LETTER,
    val language: String = if (Locale.getDefault().language == "hi") "hi" else "en",
    val tone: AiTone = AiTone.FORMAL,
    val subject: String = "",
    val recipient: String = "",
    val details: String = "",
    val prompt: String = "",
)

@HiltViewModel
class AiCreatorViewModel @Inject constructor(
    workspaces: WorkspaceManager,
    private val backend: BackendClient,
    private val drafts: DraftRepository,
    val gate: CloudGate,
) : OperationViewModel(workspaces) {
    val form = MutableStateFlow(AiForm())

    fun generate(title: String, onDraft: (String) -> Unit) {
        viewModelScope.launch { gate.request { run(title, onDraft) } }
    }

    private fun run(title: String, onDraft: (String) -> Unit) = runOperation(R.string.ai_generating) {
        val f = form.value
        val fields = buildMap {
            if (f.subject.isNotBlank()) put("subject", f.subject.trim())
            if (f.recipient.isNotBlank()) put("recipient", f.recipient.trim())
            if (f.details.isNotBlank()) put("details", f.details.trim())
        }
        val result = backend.aiGenerate(
            AiRequestDto(
                task = "generate_document",
                language = f.language,
                documentType = f.type.api,
                tone = f.tone.api,
                instructions = f.prompt.trim().ifBlank { null },
                fields = fields.ifEmpty { null },
            ),
        )
        // The draft is a real provider response; it is labelled AI-generated and must be reviewed before export.
        val draft = drafts.create(f.subject.ifBlank { title }, result.markdown, aiGenerated = true, sourceOperation = "ai_creator")
        onDraft(draft.id)
        OpState.Done(emptyList())
    }
}

@Composable
fun AiCreatorScreen(onBack: () -> Unit, onEditor: (String) -> Unit, vm: AiCreatorViewModel = hiltViewModel()) {
    val form by vm.form.collectAsStateWithLifecycle()
    val op by vm.op.collectAsStateWithLifecycle()
    val pending by vm.gate.pending.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val typeTitle = stringResource(form.type.label)
    ToolScaffold(stringResource(R.string.tool_ai), onBack, Availability.INTERNET_REQUIRED) {
        if (OperationStatus(op, vm::cancel, vm::dismissResult) { vm.dismissResult() }) return@ToolScaffold
        InfoBanner(stringResource(R.string.ai_disclaimer))
        if (!vm.gate.serverConfigured) InfoBanner(stringResource(R.string.error_server_not_configured))
        Text(stringResource(R.string.ai_doc_type), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AiDocType.entries.forEach { t -> FilterChip(selected = form.type == t, onClick = { vm.form.value = form.copy(type = t) }, label = { Text(stringResource(t.label)) }) }
        }
        Text(stringResource(R.string.label_language), style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = form.language == "en", onClick = { vm.form.value = form.copy(language = "en") }, label = { Text("English") })
            FilterChip(selected = form.language == "hi", onClick = { vm.form.value = form.copy(language = "hi") }, label = { Text("हिन्दी") })
        }
        Text(stringResource(R.string.ai_tone), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AiTone.entries.forEach { t -> FilterChip(selected = form.tone == t, onClick = { vm.form.value = form.copy(tone = t) }, label = { Text(stringResource(t.label)) }) }
        }
        OutlinedTextField(value = form.subject, onValueChange = { vm.form.value = form.copy(subject = it) }, label = { Text(stringResource(R.string.ai_subject)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = form.recipient, onValueChange = { vm.form.value = form.copy(recipient = it) }, label = { Text(stringResource(R.string.ai_recipient)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = form.details, onValueChange = { vm.form.value = form.copy(details = it) }, label = { Text(stringResource(R.string.ai_details)) }, minLines = 4, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = form.prompt, onValueChange = { vm.form.value = form.copy(prompt = it) }, label = { Text(stringResource(R.string.ai_prompt)) }, minLines = 2, modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.ai_privacy_note), style = MaterialTheme.typography.bodySmall)
        PrimaryAction(stringResource(R.string.action_generate_draft), enabled = vm.gate.serverConfigured && (form.details.isNotBlank() || form.prompt.isNotBlank() || form.subject.isNotBlank())) {
            vm.generate(typeTitle, onEditor)
        }
    }
    if (pending != null) CloudConsentDialog(stringResource(R.string.consent_upload_ai), onAccept = { r -> scope.launch { vm.gate.accept(r) } }, onDecline = vm.gate::decline)
}
