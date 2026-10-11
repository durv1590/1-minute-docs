package com.oneminutedocs.app.navigation

import kotlinx.serialization.Serializable

/** Type-safe navigation destinations. Large payloads (text, page lists) travel through repositories, not arguments. */
object Routes {
    @Serializable data object Onboarding
    @Serializable data object Home
    @Serializable data object Tools
    @Serializable data object Documents
    @Serializable data class Viewer(val documentId: String)
    /** [documentIds] is a comma-separated list of produced documents. */
    @Serializable data class Result(val documentIds: String, val notice: String? = null)
    @Serializable data object Settings
    @Serializable data class Legal(val page: String)

    @Serializable data class CreatePdf(val template: String? = null)
    @Serializable data class Editor(val draftId: String)
    @Serializable data object ImagesToPdf
    @Serializable data object Merge
    @Serializable data class Split(val documentId: String? = null)
    @Serializable data class Compress(val documentId: String? = null)
    @Serializable data class PageManager(val documentId: String? = null)
    @Serializable data class Annotate(val documentId: String? = null, val signatureMode: Boolean = false)
    @Serializable data class PdfToImages(val documentId: String? = null)
    @Serializable data class Security(val unlock: Boolean)
    @Serializable data class Convert(val toWord: Boolean)
    @Serializable data class Ocr(val documentId: String? = null)
    @Serializable data class Scanner(val sessionId: String, val purpose: String)
    @Serializable data class ScanReview(val sessionId: String, val purpose: String)
    @Serializable data object Handwriting
    @Serializable data class AiCreator(val draftId: String? = null)
}

/** Why a scan session was started; decides where "Done" leads. */
object ScanPurpose {
    const val DOCUMENT = "document"
    const val HANDWRITING = "handwriting"
}
