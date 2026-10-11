package com.oneminutedocs.processing.pdf

import android.content.Context
import android.graphics.Bitmap
import com.oneminutedocs.domain.CompressionPreset
import com.oneminutedocs.domain.DocException
import com.oneminutedocs.domain.ErrorCode
import com.oneminutedocs.domain.SizeReport
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.text.PDFTextStripper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.IdentityHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

typealias Progress = (Float) -> Unit

data class PdfInfo(val pageCount: Int, val needsPassword: Boolean, val restricted: Boolean)

/** One output page: source page index (0-based) and extra clockwise rotation in degrees (multiple of 90). */
data class PageSpec(val index: Int, val extraRotation: Int = 0)

data class PdfPermissions(val allowPrinting: Boolean = true, val allowCopying: Boolean = true, val allowEditing: Boolean = true)

/**
 * Object-level PDF manipulation with PdfBox-Android. Text and vector content are never rasterised; outputs are written
 * to the given file only (inputs are opened read-only and never modified), and each result is re-opened to verify it.
 *
 * Documents protected by an open password, or whose permissions forbid the operation, are rejected rather than
 * bypassed; the Unlock tool removes protection only with the correct password and permission.
 */
@Singleton
class PdfEngine @Inject constructor(@ApplicationContext context: Context) {
    private val tempDir = File(context.cacheDir, "pdfbox").apply { mkdirs() }

    /** PDFBox work is memory-hungry; at most two operations run concurrently. */
    private val gate = Semaphore(2)

    private fun memory(): MemoryUsageSetting = MemoryUsageSetting.setupMixed(32L * 1024 * 1024).setTempDir(tempDir)

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        gate.withPermit {
            try {
                block()
            } catch (e: DocException) {
                throw e
            } catch (e: InvalidPasswordException) {
                throw DocException(ErrorCode.WRONG_PASSWORD, null, e)
            } catch (e: OutOfMemoryError) {
                throw DocException(ErrorCode.OUT_OF_MEMORY, null, e)
            } catch (e: IOException) {
                throw DocException(ErrorCode.CORRUPT_FILE, e.javaClass.simpleName, e)
            }
        }
    }

    private fun load(file: File, password: String? = null): PDDocument = try {
        PDDocument.load(file, password ?: "", memory())
    } catch (e: InvalidPasswordException) {
        throw DocException(if (password == null) ErrorCode.ENCRYPTED else ErrorCode.WRONG_PASSWORD, null, e)
    }

    /** Opens for modification; refuses encrypted documents (they must be unlocked first with the Unlock tool). */
    private fun loadForEdit(file: File): PDDocument {
        val doc = load(file)
        if (doc.isEncrypted) {
            doc.close()
            throw DocException(ErrorCode.ENCRYPTED, "encrypted")
        }
        return doc
    }

    suspend fun inspect(file: File): PdfInfo = io {
        try {
            load(file).use { doc -> PdfInfo(doc.numberOfPages, needsPassword = false, restricted = doc.isEncrypted) }
        } catch (e: DocException) {
            if (e.error.code == ErrorCode.ENCRYPTED) PdfInfo(0, needsPassword = true, restricted = true) else throw e
        }
    }

    /** Verifies [file] is a readable PDF and returns its page count. */
    suspend fun validate(file: File): Int = io { verify(file) }

    private fun verify(file: File): Int {
        if (!file.isFile || file.length() == 0L) throw DocException(ErrorCode.CONVERSION_FAILED, "missing output")
        return try {
            load(file).use { doc ->
                if (doc.numberOfPages < 1) throw DocException(ErrorCode.CONVERSION_FAILED, "no pages")
                doc.numberOfPages
            }
        } catch (e: DocException) {
            // Our own encrypted outputs are valid; anything else unreadable is a failure.
            if (e.error.code == ErrorCode.ENCRYPTED) 1 else throw DocException(ErrorCode.CONVERSION_FAILED, "output unreadable")
        }
    }

    /** Merges [inputs] in order into [out]. Page sizes and orientation are preserved. */
    suspend fun merge(inputs: List<File>, out: File, progress: Progress = {}): Int = io {
        require(inputs.size >= 2)
        val sources = mutableListOf<PDDocument>()
        try {
            PDDocument(memory()).use { dest ->
                inputs.forEachIndexed { i, f ->
                    coroutineContext.ensureActive()
                    val src = loadForEdit(f).also { sources += it }
                    for (page in src.pages) {
                        dest.importPage(page).apply {
                            mediaBox = page.mediaBox
                            cropBox = page.cropBox
                            resources = page.resources
                            rotation = page.rotation
                        }
                    }
                    progress((i + 1f) / inputs.size * 0.9f)
                }
                dest.save(out)
            }
        } finally {
            sources.forEach { runCatching { it.close() } }
        }
        verify(out).also { progress(1f) }
    }

    /**
     * Writes a new PDF containing [pages] (in that order, with extra rotation). Covers reorder, rotate, delete, extract.
     */
    suspend fun rebuild(input: File, pages: List<PageSpec>, out: File): Int = io {
        require(pages.isNotEmpty())
        require(pages.map { it.index }.toSet().size == pages.size) { "duplicate pages" }
        loadForEdit(input).use { src ->
            PDDocument(memory()).use { dest ->
                for (spec in pages) {
                    if (spec.index !in 0 until src.numberOfPages) throw DocException(ErrorCode.INVALID_PAGE_RANGE)
                    val page = src.getPage(spec.index)
                    dest.importPage(page).apply {
                        mediaBox = page.mediaBox
                        cropBox = page.cropBox
                        resources = page.resources
                        rotation = ((page.rotation + spec.extraRotation) % 360 + 360) % 360
                    }
                }
                dest.documentInformation = src.documentInformation
                dest.save(out)
            }
        }
        verify(out)
    }

    /** Splits [input] into one file per group of 0-based page indices. Files are named by [nameFor]. */
    suspend fun split(input: File, groups: List<List<Int>>, outDir: File, nameFor: (Int) -> String, progress: Progress = {}): List<File> {
        val outputs = mutableListOf<File>()
        groups.forEachIndexed { i, group ->
            coroutineContext.ensureActive()
            val out = File(outDir, nameFor(i))
            rebuild(input, group.map { PageSpec(it) }, out)
            outputs += out
            progress((i + 1f) / groups.size)
        }
        return outputs
    }

    /** AES-256 encryption with an open password; [ownerPassword] defaults to the open password. */
    suspend fun protect(input: File, out: File, userPassword: String, ownerPassword: String?, permissions: PdfPermissions): Int = io {
        require(userPassword.isNotEmpty())
        loadForEdit(input).use { doc ->
            val ap = AccessPermission().apply {
                setCanPrint(permissions.allowPrinting)
                setCanPrintFaithful(permissions.allowPrinting)
                setCanExtractContent(permissions.allowCopying)
                setCanExtractForAccessibility(true)
                setCanModify(permissions.allowEditing)
                setCanModifyAnnotations(permissions.allowEditing)
                setCanAssembleDocument(permissions.allowEditing)
                setCanFillInForm(true)
            }
            val policy = StandardProtectionPolicy(ownerPassword?.takeIf { it.isNotEmpty() } ?: userPassword, userPassword, ap)
            policy.encryptionKeyLength = 256
            doc.protect(policy)
            doc.save(out)
        }
        // Re-open with the password to prove the output is usable.
        load(out, userPassword).use { it.numberOfPages }
    }

    /**
     * Removes protection when the correct [password] is supplied and the user is entitled to change the document
     * (owner password, or permissions that allow modification). Restrictions are never bypassed.
     */
    suspend fun unlock(input: File, password: String, out: File): Int = io {
        load(input, password).use { doc ->
            if (!doc.isEncrypted) throw DocException(ErrorCode.NOTHING_TO_DO, "not encrypted")
            val perm = doc.currentAccessPermission
            if (!perm.isOwnerPermission && !perm.canModify()) throw DocException(ErrorCode.ENCRYPTED, "restricted")
            doc.isAllSecurityToBeRemoved = true
            doc.save(out)
        }
        verify(out)
    }

    /**
     * Recompresses and downsamples embedded raster images according to [preset]. Text, fonts and vector graphics are
     * untouched. Images with transparency, masks, 1-bit or non-RGB/Gray colour spaces are left as they are.
     * Returns the measured size change; the caller keeps the original if the output is not smaller.
     */
    suspend fun compress(input: File, out: File, preset: CompressionPreset, progress: Progress = {}): SizeReport = io {
        loadForEdit(input).use { doc ->
            val replaced = IdentityHashMap<COSBase, PDImageXObject>()
            val visited = IdentityHashMap<COSBase, Boolean>()
            val n = doc.numberOfPages
            for (i in 0 until n) {
                coroutineContext.ensureActive()
                val page = doc.getPage(i)
                val box = page.cropBox
                page.resources?.let { recompress(doc, it, box.width, box.height, preset, replaced, visited, 0) }
                progress((i + 1f) / n * 0.9f)
            }
            doc.save(out)
        }
        verify(out)
        progress(1f)
        SizeReport(input.length(), out.length())
    }

    private fun recompress(
        doc: PDDocument,
        res: PDResources,
        pageW: Float,
        pageH: Float,
        preset: CompressionPreset,
        replaced: IdentityHashMap<COSBase, PDImageXObject>,
        visited: IdentityHashMap<COSBase, Boolean>,
        depth: Int,
    ) {
        if (depth > 8) return
        for (name in res.xObjectNames.toList()) {
            val xo = try { res.getXObject(name) } catch (_: IOException) { null } ?: continue
            when (xo) {
                is PDImageXObject -> {
                    val key = xo.cosObject
                    val existing = replaced[key]
                    if (existing != null) {
                        res.put(name, existing)
                        continue
                    }
                    if (visited.put(key, true) != null) continue
                    val newImage = recompressImage(doc, xo, pageW, pageH, preset) ?: continue
                    replaced[key] = newImage
                    res.put(name, newImage)
                }
                is PDFormXObject -> xo.resources?.let { recompress(doc, it, pageW, pageH, preset, replaced, visited, depth + 1) }
            }
        }
    }

    private fun recompressImage(doc: PDDocument, xo: PDImageXObject, pageW: Float, pageH: Float, preset: CompressionPreset): PDImageXObject? {
        if (xo.isStencil || xo.bitsPerComponent < 8) return null
        val dict = xo.cosObject
        if (dict.containsKey(COSName.SMASK) || dict.containsKey(COSName.MASK)) return null
        val components = runCatching { xo.colorSpace.numberOfComponents }.getOrNull() ?: return null
        if (components != 1 && components != 3) return null
        if (xo.width.toLong() * xo.height > 40_000_000L) return null
        // The page size is the largest possible display size, so this never downsamples below the preset's DPI.
        val target = preset.targetPixels(xo.width, xo.height, pageW, pageH)
        val originalBytes = dict.length
        val decoded: Bitmap = try { xo.image } catch (_: Exception) { return null } catch (_: OutOfMemoryError) { return null } ?: return null
        val scaled = if (target != null) Bitmap.createScaledBitmap(decoded, target.first, target.second, true) else decoded
        if (scaled !== decoded) decoded.recycle()
        return try {
            val jpeg = JPEGFactory.createFromImage(doc, scaled, preset.jpegQuality / 100f)
            if (jpeg.cosObject.length < originalBytes * 0.95) jpeg else null
        } finally {
            scaled.recycle()
        }
    }

    /** Extracts text per page (respecting the document's copy permission). */
    suspend fun extractText(input: File): List<String> = io {
        load(input).use { doc ->
            if (doc.isEncrypted && !doc.currentAccessPermission.canExtractContent()) throw DocException(ErrorCode.ENCRYPTED, "copy not permitted")
            val stripper = PDFTextStripper().apply { sortByPosition = true }
            (1..doc.numberOfPages).map { p ->
                coroutineContext.ensureActive()
                stripper.startPage = p
                stripper.endPage = p
                stripper.getText(doc).trimEnd()
            }
        }
    }
}
