package com.oneminutedocs.processing.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import com.oneminutedocs.domain.DocException
import com.oneminutedocs.domain.ErrorCode
import com.oneminutedocs.domain.PageGeometry
import java.io.File
import java.io.FileOutputStream

/** Memory-conscious image decoding: never decodes more pixels than needed and honours EXIF orientation. */
object BitmapLoader {
    /** Default cap for page images: ~3.5 MP keeps peak memory well under 30 MB per bitmap on budget phones. */
    const val DEFAULT_MAX_SIDE = 2400

    fun bounds(file: File): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) throw DocException(ErrorCode.CORRUPT_FILE, "unreadable image")
        return o.outWidth to o.outHeight
    }

    fun decode(file: File, maxSide: Int = DEFAULT_MAX_SIDE): Bitmap {
        val (w, h) = bounds(file)
        val scale = maxOf(w, h).toFloat() / maxSide
        val target = if (scale > 1f) ((w / scale).toInt() to (h / scale).toInt()) else (w to h)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = PageGeometry.inSampleSize(w, h, target.first, target.second)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = try {
            BitmapFactory.decodeFile(file.absolutePath, opts)
        } catch (e: OutOfMemoryError) {
            throw DocException(ErrorCode.OUT_OF_MEMORY, "decode", e)
        } ?: throw DocException(ErrorCode.CORRUPT_FILE, "decode failed")
        val scaled = scaleDown(decoded, maxSide)
        return applyExifOrientation(file, scaled)
    }

    fun scaleDown(src: Bitmap, maxSide: Int): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= maxSide) return src
        val f = maxSide.toFloat() / longest
        val out = Bitmap.createScaledBitmap(src, (src.width * f).toInt().coerceAtLeast(1), (src.height * f).toInt().coerceAtLeast(1), true)
        if (out !== src) src.recycle()
        return out
    }

    private fun applyExifOrientation(file: File, bitmap: Bitmap): Bitmap {
        val orientation = try {
            ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return bitmap
        }
        val out = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
        if (out !== bitmap) bitmap.recycle()
        return out
    }

    fun rotate(src: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return src
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    fun saveJpeg(bitmap: Bitmap, file: File, quality: Int = 88) {
        FileOutputStream(file).use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(30, 100), out)) throw DocException(ErrorCode.UNKNOWN, "jpeg encode")
        }
    }

    fun savePng(bitmap: Bitmap, file: File) {
        FileOutputStream(file).use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw DocException(ErrorCode.UNKNOWN, "png encode")
        }
    }
}
