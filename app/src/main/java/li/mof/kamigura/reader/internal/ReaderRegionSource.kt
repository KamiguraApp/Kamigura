package li.mof.kamigura.reader.internal

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.exifinterface.media.ExifInterface
import coil.ImageLoader
import java.io.Closeable
import java.io.File
import java.util.zip.ZipFile
import li.mof.kamigura.download.OfflinePage

internal sealed interface ReaderRegionModel {
    data class Cached(val loader: ImageLoader, val key: String, val revision: Int = 0) : ReaderRegionModel {
        override fun toString() = "Cached reader image [redacted]"
    }
    data class Offline(val page: OfflinePage) : ReaderRegionModel
}

internal interface ReaderRegionSource : Closeable {
    val width: Int
    val height: Int
    fun decode(rect: ReaderRegionRect, sample: Int): Bitmap?
}

/** Opens existing bytes only; this path must never execute an image/network request. */
internal fun openReaderRegionSource(context: Context, model: ReaderRegionModel, checkActive: () -> Unit = {}): ReaderRegionSource? {
    var cleanup: (() -> Unit)? = null
    var openedDecoder: BitmapRegionDecoder? = null
    try {
        if (model is ReaderRegionModel.Offline && model.page is OfflinePage.PdfPage) {
            return PdfRegionSource(model.page)
        }
        val file = when (model) {
            is ReaderRegionModel.Cached -> {
                val snapshot = model.loader.diskCache?.openSnapshot(model.key) ?: return null
                cleanup = { snapshot.close() }
                snapshot.data.toFile()
            }
            is ReaderRegionModel.Offline -> {
                val page = model.page as? OfflinePage.ArchiveEntry ?: return null
                val temporary = File.createTempFile("reader-region-", ".image", context.cacheDir)
                cleanup = { temporary.delete(); Unit }
                ZipFile(page.archivePath).use { zip ->
                    val entry = zip.getEntry(page.entryName) ?: return null.also { cleanup?.invoke() }
                    zip.getInputStream(entry).use { input -> temporary.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            checkActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    } }
                }
                temporary
            }
        }
        @Suppress("DEPRECATION")
        val decoder = BitmapRegionDecoder.newInstance(file.absolutePath, false) ?: return null.also { cleanup?.invoke() }
        openedDecoder = decoder
        // Coil applies EXIF online; the existing archive preview uses raw BitmapFactory pixels.
        val orientation = if (model is ReaderRegionModel.Cached) {
            runCatching { ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }
                .getOrDefault(1).takeIf { it in 1..8 } ?: 1
        } else 1
        return RasterRegionSource(decoder, orientation, cleanup)
    } catch (_: Exception) {
        openedDecoder?.recycle()
        cleanup?.invoke()
        return null
    } catch (_: OutOfMemoryError) {
        openedDecoder?.recycle()
        cleanup?.invoke()
        return null
    }
}

private class RasterRegionSource(private val decoder: BitmapRegionDecoder, private val orientation: Int,
    private val cleanup: (() -> Unit)?) : ReaderRegionSource {
    override val width = if (orientation >= 5) decoder.height else decoder.width
    override val height = if (orientation >= 5) decoder.width else decoder.height

    override fun decode(rect: ReaderRegionRect, sample: Int): Bitmap? {
        fun raw(x: Int, y: Int): Pair<Int, Int> = when (orientation) {
            2 -> decoder.width - x to y
            3 -> decoder.width - x to decoder.height - y
            4 -> x to decoder.height - y
            5 -> y to x
            6 -> y to decoder.height - x
            7 -> decoder.width - y to decoder.height - x
            8 -> decoder.width - y to x
            else -> x to y
        }
        val points = listOf(raw(rect.left, rect.top), raw(rect.right, rect.bottom))
        val region = Rect(points.minOf { it.first }, points.minOf { it.second },
            points.maxOf { it.first }, points.maxOf { it.second })
        val bitmap = decoder.decodeRegion(region, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: return null
        if (orientation == 1) return bitmap
        val matrix = Matrix().apply {
            when (orientation) {
                2 -> setScale(-1f, 1f)
                3 -> setRotate(180f)
                4 -> setScale(1f, -1f)
                5 -> setValues(floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f))
                6 -> setRotate(90f)
                7 -> setValues(floatArrayOf(0f, -1f, 0f, -1f, 0f, 0f, 0f, 0f, 1f))
                8 -> setRotate(270f)
            }
        }
        return try {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } finally { bitmap.recycle() }
    }

    override fun close() { decoder.recycle(); cleanup?.invoke() }
}

private class PdfRegionSource(page: OfflinePage.PdfPage) : ReaderRegionSource {
    private val descriptor = ParcelFileDescriptor.open(File(page.pdfPath), ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = try { PdfRenderer(descriptor) } catch (error: Throwable) { descriptor.close(); throw error }
    private val index = page.index
    override val width: Int
    override val height: Int
    init {
        try {
            renderer.openPage(index).use { width = Math.multiplyExact(it.width, 16); height = Math.multiplyExact(it.height, 16) }
        } catch (error: Throwable) { close(); throw error }
    }
    override fun decode(rect: ReaderRegionRect, sample: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(((rect.width.toLong() + sample - 1) / sample).toInt(),
            ((rect.height.toLong() + sample - 1) / sample).toInt(), Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(android.graphics.Color.WHITE)
            renderer.openPage(index).use { page ->
                val matrix = Matrix().apply {
                    setScale(16f / sample, 16f / sample)
                    postTranslate(-rect.left.toFloat() / sample, -rect.top.toFloat() / sample)
                }
                page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
            return bitmap
        } catch (error: Throwable) { bitmap.recycle(); throw error }
    }
    override fun close() { renderer.close(); descriptor.close() }
}
