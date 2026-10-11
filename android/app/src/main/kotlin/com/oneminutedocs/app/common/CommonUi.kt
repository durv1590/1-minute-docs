package com.oneminutedocs.app.common

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.oneminutedocs.app.R
import com.oneminutedocs.core.data.LocalInput
import com.oneminutedocs.core.designsystem.ErrorBlock
import com.oneminutedocs.core.designsystem.PillTone
import com.oneminutedocs.core.designsystem.ProgressBlock
import com.oneminutedocs.core.designsystem.StatusPill
import com.oneminutedocs.domain.Availability

/** Standard screen frame for tools: top bar with back navigation, centred content limited in width on tablets. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolScaffold(
    title: String,
    onBack: () -> Unit,
    availability: Availability? = null,
    actions: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
                },
                actions = { actions() },
            )
        },
        bottomBar = bottomBar,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier.fillMaxHeight())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (availability != null) AvailabilityPill(availability)
                content()
            }
        }
    }
}

@Composable
fun AvailabilityPill(a: Availability) {
    StatusPill(
        text = stringResource(Tools.availabilityLabel(a)),
        tone = if (a == Availability.OFFLINE) PillTone.OFFLINE else PillTone.ONLINE,
    )
}

/**
 * Renders the running/failed state of an operation over the screen, and calls [onDone] once when outputs are ready.
 * Returns true when the caller should hide its own form (operation running).
 */
@Composable
fun OperationStatus(state: OpState, onCancel: () -> Unit, onDismissError: () -> Unit, onDone: (OpState.Done) -> Unit): Boolean {
    LaunchedEffect(state) { if (state is OpState.Done) onDone(state) }
    return when (state) {
        is OpState.Running -> {
            ProgressBlock(
                title = stringResource(state.title),
                detail = state.detail?.let { stringResource(it) } ?: stringResource(if (state.progress == null) R.string.progress_unmeasured else R.string.progress_measured),
                progress = state.progress,
                onCancel = onCancel,
                cancelLabel = stringResource(R.string.action_cancel),
            )
            true
        }
        is OpState.Failed -> {
            ErrorBlock(
                title = stringResource(R.string.error_title),
                message = stringResource(errorMessage(state.error.code)) + "\n" + stringResource(R.string.error_original_safe),
                retryLabel = stringResource(R.string.action_ok),
                onRetry = onDismissError,
            )
            false
        }
        else -> false
    }
}

@Composable
fun InputFileList(inputs: List<LocalInput>, modifier: Modifier = Modifier, trailing: @Composable (Int) -> Unit = {}) {
    val context = LocalContext.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        inputs.forEachIndexed { i, input ->
            Surface(tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium) {
                ListItem(
                    leadingContent = { Icon(Icons.Outlined.Description, contentDescription = null) },
                    headlineContent = { Text(input.displayName, maxLines = 2) },
                    supportingContent = { Text("${input.format.extension.uppercase()} · ${formatSize(context, input.sizeBytes)}") },
                    trailingContent = { trailing(i) },
                )
            }
        }
    }
}

/** Launchers for the Storage Access Framework and the photo picker (no storage permission required). */
class Pickers(
    val pickOne: (Array<String>) -> Unit,
    val pickMany: (Array<String>) -> Unit,
    val pickImages: () -> Unit,
)

@Composable
fun rememberPickers(onPicked: (List<Uri>) -> Unit): Pickers {
    val one = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) onPicked(listOf(uri)) }
    val many = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) onPicked(uris) }
    val images = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris -> if (uris.isNotEmpty()) onPicked(uris) }
    return remember(one, many, images) {
        Pickers(
            pickOne = { one.launch(it) },
            pickMany = { many.launch(it) },
            pickImages = { images.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        )
    }
}

object Mime {
    val PDF = arrayOf("application/pdf")
    val OFFICE = arrayOf(
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/msword",
        "application/vnd.oasis.opendocument.text",
        "application/rtf",
        "text/rtf",
    )
    val IMAGES = arrayOf("image/jpeg", "image/png", "image/webp", "image/heif", "image/heic")
    val PDF_OR_IMAGES = PDF + IMAGES
}

/**
 * Explains exactly what will be uploaded before any cloud processing (privacy requirement), with an option to stop
 * asking. Declining keeps everything on the device.
 */
@Composable
fun CloudConsentDialog(whatIsUploaded: String, onAccept: (remember: Boolean) -> Unit, onDecline: () -> Unit) {
    var rememberChoice by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDecline,
        icon = { Icon(Icons.Outlined.CloudUpload, contentDescription = null) },
        title = { Text(stringResource(R.string.consent_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.consent_what, whatIsUploaded))
                Text(stringResource(R.string.consent_how))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = rememberChoice, onCheckedChange = { rememberChoice = it })
                    Text(stringResource(R.string.consent_remember))
                }
            }
        },
        confirmButton = { Button(onClick = { onAccept(rememberChoice) }) { Text(stringResource(R.string.consent_accept)) } },
        dismissButton = { TextButton(onClick = onDecline) { Text(stringResource(R.string.consent_decline)) } },
    )
}

@Composable
fun PrimaryAction(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 14.dp)) { Text(text) }
}
