package com.oneminutedocs.app.feature.scanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Camera
import androidx.compose.material.icons.outlined.FlashOff
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.oneminutedocs.app.R
import com.oneminutedocs.app.navigation.Routes
import com.oneminutedocs.core.designsystem.InfoBanner
import com.oneminutedocs.domain.FileSignature
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@HiltViewModel
class ScannerViewModel @Inject constructor(
    savedState: SavedStateHandle,
    @ApplicationContext private val context: android.content.Context,
    private val store: ScanSessionStore,
) : ViewModel() {
    private val route = savedState.toRoute<Routes.Scanner>()
    val sessionId = route.sessionId
    val purpose = route.purpose
    val pages = store.pages(sessionId)
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<Int?>(null)

    fun newCaptureFile(): File = store.newImageFile(sessionId)

    fun captured(file: File) {
        viewModelScope.launch {
            busy.value = true
            store.addPage(sessionId, file)
            busy.value = false
        }
    }

    /** Copies picked photos into the session (validated by content) and adds them as pages. */
    fun importImages(uris: List<Uri>) {
        viewModelScope.launch {
            busy.value = true
            for (uri in uris) {
                val dest = store.newImageFile(sessionId)
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use { input -> dest.outputStream().use { input.copyTo(it) } } != null &&
                            dest.length() in 1..(40L * 1024 * 1024) && isImage(dest)
                    }.getOrDefault(false)
                }
                if (ok) store.addPage(sessionId, dest) else { dest.delete(); message.value = R.string.error_unsupported_format }
            }
            busy.value = false
        }
    }

    private fun isImage(f: File): Boolean {
        val header = ByteArray(32)
        val n = f.inputStream().use { it.read(header) }
        return FileSignature.formatOf(FileSignature.sniff(header.copyOf(maxOf(n, 0))))?.isImage == true
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(onBack: () -> Unit, onReview: (String, String) -> Unit, vm: ScannerViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val pages by vm.pages.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var denied by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> granted = ok; denied = !ok }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(30)) { uris -> if (uris.isNotEmpty()) vm.importImages(uris) }
    val galleryRequest = androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.scanner_title, pages.size)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
                actions = {
                    if (pages.isNotEmpty()) Button(onClick = { onReview(vm.sessionId, vm.purpose) }, modifier = Modifier.padding(end = 8.dp)) { Text(stringResource(R.string.action_review_pages)) }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (granted) {
                CameraCapture(newFile = vm::newCaptureFile, busy = busy, onCaptured = vm::captured, onGallery = { gallery.launch(galleryRequest) }, onError = { vm.message.value = R.string.error_camera })
            } else {
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
                    InfoBanner(stringResource(if (denied) R.string.camera_denied else R.string.camera_rationale))
                    if (!denied) Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text(stringResource(R.string.action_allow_camera)) }
                    else OutlinedButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                    }) { Text(stringResource(R.string.action_open_settings)) }
                    OutlinedButton(onClick = { gallery.launch(galleryRequest) }) { Text(stringResource(R.string.action_import_photos)) }
                }
            }
            message?.let {
                InfoBanner(stringResource(it), modifier = Modifier.align(Alignment.TopCenter).padding(12.dp))
            }
        }
    }
}

@Composable
private fun CameraCapture(newFile: () -> File, busy: Boolean, onCaptured: (File) -> Unit, onGallery: () -> Unit, onError: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var flash by remember { mutableStateOf(false) }
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build() }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER } }
    DisposableEffect(lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
            } catch (e: Exception) {
                onError()
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose { runCatching { future.get().unbindAll() } }
    }
    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(24.dp),
            horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onGallery) { Icon(Icons.Outlined.PhotoLibrary, stringResource(R.string.action_import_photos), tint = MaterialTheme.colorScheme.inverseOnSurface) }
            FilledIconButton(
                onClick = {
                    val file = newFile()
                    capture.flashMode = if (flash) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
                    capture.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(output: ImageCapture.OutputFileResults) = onCaptured(file)
                        override fun onError(exception: ImageCaptureException) { file.delete(); onError() }
                    })
                },
                enabled = !busy,
                modifier = Modifier.size(76.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(),
            ) { Icon(Icons.Outlined.Camera, stringResource(R.string.action_capture), modifier = Modifier.size(36.dp)) }
            IconButton(onClick = { flash = !flash }) {
                Icon(if (flash) Icons.Outlined.FlashOn else Icons.Outlined.FlashOff, stringResource(R.string.action_toggle_flash), tint = MaterialTheme.colorScheme.inverseOnSurface)
            }
        }
    }
}
