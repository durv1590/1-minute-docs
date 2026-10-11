package com.oneminutedocs.processing.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.oneminutedocs.domain.DocException
import com.oneminutedocs.domain.ErrorCode
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Script model to run. Devanagari also recognises Latin characters, so it suits mixed Hindi/English pages. */
enum class OcrScript { LATIN, DEVANAGARI }

data class OcrLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int)

data class OcrPage(val text: String, val lines: List<OcrLine>) {
    val isEmpty: Boolean get() = text.isBlank()
}

/** Engine-neutral OCR contract so ML Kit can be replaced without touching features. */
interface OcrEngine {
    suspend fun recognize(bitmap: Bitmap, script: OcrScript): OcrPage
}

/**
 * On-device OCR with ML Kit Text Recognition v2 (bundled Latin and Devanagari models — works offline, nothing is
 * uploaded). It is designed for printed text: handwriting recognition is best-effort and the UI says so.
 */
@Singleton
class MlKitOcrEngine @Inject constructor() : OcrEngine {
    private val latin: TextRecognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val devanagari: TextRecognizer by lazy { TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build()) }

    override suspend fun recognize(bitmap: Bitmap, script: OcrScript): OcrPage {
        val recognizer = if (script == OcrScript.DEVANAGARI) devanagari else latin
        val result: Text = suspendCancellableCoroutine { cont ->
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                .addOnFailureListener { e ->
                    if (cont.isActive) cont.resumeWithException(DocException(ErrorCode.OCR_FAILED, e.javaClass.simpleName, e))
                }
        }
        return toPage(result)
    }

    /** Lines in reading order; blocks separated by a blank line so the note structurer sees paragraphs. */
    private fun toPage(result: Text): OcrPage {
        val lines = mutableListOf<OcrLine>()
        val sb = StringBuilder()
        val blocks = result.textBlocks.sortedWith(compareBy({ it.boundingBox?.top ?: 0 }, { it.boundingBox?.left ?: 0 }))
        for ((i, block) in blocks.withIndex()) {
            if (i > 0) sb.append("\n\n")
            block.lines.forEachIndexed { j, line ->
                if (j > 0) sb.append('\n')
                sb.append(line.text)
                val b = line.boundingBox
                lines += OcrLine(line.text, b?.left ?: 0, b?.top ?: 0, b?.right ?: 0, b?.bottom ?: 0)
            }
        }
        return OcrPage(sb.toString(), lines)
    }
}
