package com.oneminutedocs.app.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.oneminutedocs.app.common.OpState
import com.oneminutedocs.app.feature.ai.AiCreatorScreen
import com.oneminutedocs.app.feature.documents.DocumentsScreen
import com.oneminutedocs.app.feature.documents.ViewerScreen
import com.oneminutedocs.app.feature.editor.CreatePdfScreen
import com.oneminutedocs.app.feature.editor.EditorScreen
import com.oneminutedocs.app.feature.handwriting.HandwritingScreen
import com.oneminutedocs.app.feature.home.Category
import com.oneminutedocs.app.feature.home.HomeScreen
import com.oneminutedocs.app.feature.ocr.OcrScreen
import com.oneminutedocs.app.feature.onboarding.OnboardingScreen
import com.oneminutedocs.app.feature.pdf.AnnotateScreen
import com.oneminutedocs.app.feature.pdf.CompressScreen
import com.oneminutedocs.app.feature.pdf.ConvertScreen
import com.oneminutedocs.app.feature.pdf.ImagesToPdfScreen
import com.oneminutedocs.app.feature.pdf.MergeScreen
import com.oneminutedocs.app.feature.pdf.PageManagerScreen
import com.oneminutedocs.app.feature.pdf.PdfToImagesScreen
import com.oneminutedocs.app.feature.pdf.SecurityScreen
import com.oneminutedocs.app.feature.pdf.SplitScreen
import com.oneminutedocs.app.feature.result.ResultScreen
import com.oneminutedocs.app.feature.scanner.ScanReviewScreen
import com.oneminutedocs.app.feature.scanner.ScanSessionStore
import com.oneminutedocs.app.feature.scanner.ScannerScreen
import com.oneminutedocs.app.feature.settings.LegalScreen
import com.oneminutedocs.app.feature.settings.SettingsScreen
import com.oneminutedocs.app.feature.tools.ToolsScreen
import com.oneminutedocs.domain.ToolCategory
import com.oneminutedocs.domain.ToolId

@Composable
fun OmdNavHost(nav: NavHostController, startOnboarding: Boolean, scanSessions: ScanSessionStore, onOnboardingDone: () -> Unit) {
    val back: () -> Unit = { nav.popBackStack() }
    /** Replaces the tool screen with the result screen; back from results returns to the tool's parent. */
    val toResult: (OpState.Done) -> Unit = { done ->
        if (done.documentIds.isNotEmpty()) nav.navigate(Routes.Result(done.documentIds.joinToString(","), done.notice))
    }
    val openTool: (ToolId) -> Unit = { id ->
        nav.navigate(
            when (id) {
                ToolId.CREATE_PDF -> Routes.CreatePdf()
                ToolId.IMAGES_TO_PDF -> Routes.ImagesToPdf
                ToolId.MERGE_PDF -> Routes.Merge
                ToolId.SPLIT_PDF -> Routes.Split()
                ToolId.COMPRESS_PDF -> Routes.Compress()
                ToolId.PAGE_MANAGER -> Routes.PageManager()
                ToolId.ANNOTATE_PDF -> Routes.Annotate()
                ToolId.SIGN_PDF -> Routes.Annotate(signatureMode = true)
                ToolId.PDF_TO_IMAGES -> Routes.PdfToImages()
                ToolId.PDF_TO_WORD -> Routes.Convert(toWord = true)
                ToolId.WORD_TO_PDF -> Routes.Convert(toWord = false)
                ToolId.PROTECT_PDF -> Routes.Security(unlock = false)
                ToolId.UNLOCK_PDF -> Routes.Security(unlock = true)
                ToolId.SCAN_DOCUMENT -> Routes.Scanner(scanSessions.newSession(), ScanPurpose.DOCUMENT)
                ToolId.OCR -> Routes.Ocr()
                ToolId.HANDWRITTEN_NOTES -> Routes.Handwriting
                ToolId.AI_CREATOR -> Routes.AiCreator()
            },
        )
    }

    NavHost(navController = nav, startDestination = if (startOnboarding) Routes.Onboarding else Routes.Home) {
        composable<Routes.Onboarding> {
            OnboardingScreen(onFinish = {
                onOnboardingDone()
                nav.navigate(Routes.Home) { popUpTo(Routes.Onboarding) { inclusive = true } }
            })
        }
        composable<Routes.Home> {
            HomeScreen(
                onTool = openTool,
                onCategory = { c ->
                    when (c) {
                        Category.PDF_TOOLS -> nav.navigate(Routes.Tools)
                        Category.SCAN -> openTool(ToolId.SCAN_DOCUMENT)
                        Category.HANDWRITING -> openTool(ToolId.HANDWRITTEN_NOTES)
                        Category.AI -> openTool(ToolId.AI_CREATOR)
                        Category.CONVERSION -> nav.navigate(Routes.Tools)
                        Category.DOCUMENTS -> nav.navigate(Routes.Documents)
                    }
                },
                onOpenDocument = { nav.navigate(Routes.Viewer(it)) },
                onSettings = { nav.navigate(Routes.Settings) },
                onHelp = { nav.navigate(Routes.Legal("help")) },
            )
        }
        composable<Routes.Tools> { ToolsScreen(onBack = back, onTool = openTool, categories = null as Set<ToolCategory>?) }
        composable<Routes.Documents> { DocumentsScreen(onBack = back, onOpen = { nav.navigate(Routes.Viewer(it)) }) }
        composable<Routes.Viewer> { ViewerScreen(onBack = back) }
        composable<Routes.Result> {
            ResultScreen(
                onBack = back,
                onPreview = { nav.navigate(Routes.Viewer(it)) },
                onHome = { nav.navigate(Routes.Home) { popUpTo(Routes.Home) { inclusive = true } } },
            )
        }
        composable<Routes.Settings> { SettingsScreen(onBack = back, onLegal = { nav.navigate(Routes.Legal(it)) }) }
        composable<Routes.Legal> { entry -> LegalScreen(entry.toRoute<Routes.Legal>().page, onBack = back) }

        composable<Routes.CreatePdf> { entry ->
            CreatePdfScreen(entry.toRoute<Routes.CreatePdf>().template, onBack = back, onOpenEditor = { nav.navigate(Routes.Editor(it)) })
        }
        composable<Routes.Editor> { EditorScreen(onBack = back, onDone = toResult) }
        composable<Routes.ImagesToPdf> { ImagesToPdfScreen(onBack = back, onDone = toResult) }
        composable<Routes.Merge> { MergeScreen(onBack = back, onDone = toResult) }
        composable<Routes.Split> { SplitScreen(onBack = back, onDone = toResult) }
        composable<Routes.Compress> { CompressScreen(onBack = back, onDone = toResult, onSavedSingle = { id -> nav.navigate(Routes.Result(id)) }) }
        composable<Routes.PageManager> { PageManagerScreen(onBack = back, onDone = toResult) }
        composable<Routes.Annotate> { AnnotateScreen(onBack = back, onDone = toResult) }
        composable<Routes.PdfToImages> { PdfToImagesScreen(onBack = back, onDone = toResult) }
        composable<Routes.Security> { SecurityScreen(onBack = back, onDone = toResult) }
        composable<Routes.Convert> { ConvertScreen(onBack = back, onDone = toResult) }
        composable<Routes.Ocr> { OcrScreen(onBack = back, onDone = toResult, onEditor = { nav.navigate(Routes.Editor(it)) }) }
        composable<Routes.Scanner> {
            ScannerScreen(onBack = back, onReview = { session, purpose ->
                nav.navigate(Routes.ScanReview(session, purpose)) { popUpTo<Routes.Scanner> { inclusive = purpose == ScanPurpose.HANDWRITING } }
            })
        }
        composable<Routes.ScanReview> {
            ScanReviewScreen(
                onBack = back,
                onRetake = { _ -> back() },
                onContinueHandwriting = back,
                onDone = toResult,
            )
        }
        composable<Routes.Handwriting> {
            HandwritingScreen(
                onBack = back,
                onCamera = { session -> nav.navigate(Routes.Scanner(session, ScanPurpose.HANDWRITING)) },
                onReview = { session -> nav.navigate(Routes.ScanReview(session, ScanPurpose.HANDWRITING)) },
                onEditor = { nav.navigate(Routes.Editor(it)) },
                onDone = toResult,
            )
        }
        composable<Routes.AiCreator> { AiCreatorScreen(onBack = back, onEditor = { nav.navigate(Routes.Editor(it)) }) }
    }
}
