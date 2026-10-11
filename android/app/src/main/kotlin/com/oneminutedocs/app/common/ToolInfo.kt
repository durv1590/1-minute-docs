package com.oneminutedocs.app.common

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.automirrored.outlined.CallMerge
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.automirrored.outlined.TextSnippet
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.ui.graphics.vector.ImageVector
import com.oneminutedocs.app.R
import com.oneminutedocs.domain.Availability
import com.oneminutedocs.domain.ToolId

/** Presentation metadata for each tool: localized texts, icon, and its route. */
data class ToolInfo(
    val id: ToolId,
    @StringRes val title: Int,
    @StringRes val description: Int,
    @StringRes val formats: Int,
    val icon: ImageVector,
)

object Tools {
    val all: List<ToolInfo> = listOf(
        ToolInfo(ToolId.CREATE_PDF, R.string.tool_create_pdf, R.string.tool_create_pdf_desc, R.string.formats_text_to_pdf, Icons.AutoMirrored.Outlined.NoteAdd),
        ToolInfo(ToolId.SCAN_DOCUMENT, R.string.tool_scan, R.string.tool_scan_desc, R.string.formats_camera_to_pdf, Icons.Outlined.DocumentScanner),
        ToolInfo(ToolId.HANDWRITTEN_NOTES, R.string.tool_handwriting, R.string.tool_handwriting_desc, R.string.formats_handwriting, Icons.Outlined.EditNote),
        ToolInfo(ToolId.AI_CREATOR, R.string.tool_ai, R.string.tool_ai_desc, R.string.formats_ai, Icons.Outlined.AutoAwesome),
        ToolInfo(ToolId.IMAGES_TO_PDF, R.string.tool_images_to_pdf, R.string.tool_images_to_pdf_desc, R.string.formats_images_to_pdf, Icons.Outlined.Collections),
        ToolInfo(ToolId.MERGE_PDF, R.string.tool_merge, R.string.tool_merge_desc, R.string.formats_pdf_to_pdf, Icons.AutoMirrored.Outlined.CallMerge),
        ToolInfo(ToolId.SPLIT_PDF, R.string.tool_split, R.string.tool_split_desc, R.string.formats_pdf_to_pdf, Icons.AutoMirrored.Outlined.CallSplit),
        ToolInfo(ToolId.COMPRESS_PDF, R.string.tool_compress, R.string.tool_compress_desc, R.string.formats_pdf_to_pdf, Icons.Outlined.Compress),
        ToolInfo(ToolId.PAGE_MANAGER, R.string.tool_pages, R.string.tool_pages_desc, R.string.formats_pdf_to_pdf, Icons.Outlined.ViewAgenda),
        ToolInfo(ToolId.ANNOTATE_PDF, R.string.tool_annotate, R.string.tool_annotate_desc, R.string.formats_pdf_to_pdf, Icons.Outlined.Draw),
        ToolInfo(ToolId.SIGN_PDF, R.string.tool_sign, R.string.tool_sign_desc, R.string.formats_pdf_to_pdf, Icons.Outlined.Draw),
        ToolInfo(ToolId.PDF_TO_WORD, R.string.tool_pdf_to_word, R.string.tool_pdf_to_word_desc, R.string.formats_pdf_to_docx, Icons.Outlined.Description),
        ToolInfo(ToolId.WORD_TO_PDF, R.string.tool_word_to_pdf, R.string.tool_word_to_pdf_desc, R.string.formats_office_to_pdf, Icons.Outlined.PictureAsPdf),
        ToolInfo(ToolId.PDF_TO_IMAGES, R.string.tool_pdf_to_images, R.string.tool_pdf_to_images_desc, R.string.formats_pdf_to_images, Icons.Outlined.Image),
        ToolInfo(ToolId.OCR, R.string.tool_ocr, R.string.tool_ocr_desc, R.string.formats_ocr, Icons.AutoMirrored.Outlined.TextSnippet),
        ToolInfo(ToolId.PROTECT_PDF, R.string.tool_protect, R.string.tool_protect_desc, R.string.formats_pdf_to_pdf, Icons.Outlined.Lock),
        ToolInfo(ToolId.UNLOCK_PDF, R.string.tool_unlock, R.string.tool_unlock_desc, R.string.formats_pdf_to_pdf, Icons.Outlined.LockOpen),
    )

    fun info(id: ToolId): ToolInfo = all.first { it.id == id }

    @StringRes
    fun availabilityLabel(a: Availability): Int = when (a) {
        Availability.OFFLINE -> R.string.availability_offline
        Availability.INTERNET_REQUIRED -> R.string.availability_internet
        Availability.OFFLINE_WITH_ONLINE_ENHANCEMENT -> R.string.availability_enhanced
    }
}
