package com.oneminutedocs.app.feature.documents

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.oneminutedocs.app.R
import com.oneminutedocs.core.data.DocumentRepository
import com.oneminutedocs.core.data.ManagedDocument
import com.oneminutedocs.domain.Outcome
import kotlinx.coroutines.launch

/** Share / open-with / export helpers for managed documents; outcomes are reported via [onMessage]. */
class DocumentActions(
    val share: (List<ManagedDocument>) -> Unit,
    val openWith: (ManagedDocument) -> Unit,
    val export: (ManagedDocument) -> Unit,
)

@Composable
fun rememberDocumentActions(repository: DocumentRepository, onMessage: (Int) -> Unit): DocumentActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingExport by remember { mutableStateOf<ManagedDocument?>(null) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri: Uri? ->
        val doc = pendingExport
        pendingExport = null
        if (uri != null && doc != null) scope.launch {
            onMessage(if (repository.exportTo(doc.id, uri) is Outcome.Success) R.string.msg_exported else R.string.msg_export_failed)
        }
    }
    return remember(repository) {
        DocumentActions(
            share = { docs ->
                scope.launch {
                    val uris = docs.mapNotNull { repository.shareUri(it.id) }
                    if (uris.isEmpty()) return@launch
                    val intent = if (uris.size == 1) {
                        Intent(Intent.ACTION_SEND).setType(docs.first().format.mimeType).putExtra(Intent.EXTRA_STREAM, uris.first())
                    } else {
                        Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                    }
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startChooser(intent, context.getString(R.string.action_share), onMessage)
                }
            },
            openWith = { doc ->
                scope.launch {
                    val uri = repository.shareUri(doc.id) ?: return@launch
                    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, doc.format.mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startChooser(intent, context.getString(R.string.action_open_with), onMessage)
                }
            },
            export = { doc ->
                pendingExport = doc
                exporter.launch(doc.displayName)
            },
        )
    }
}

private fun Context.startChooser(intent: Intent, title: String, onMessage: (Int) -> Unit) {
    try {
        startActivity(Intent.createChooser(intent, title))
    } catch (_: ActivityNotFoundException) {
        onMessage(R.string.msg_no_app)
    }
}
