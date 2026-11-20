package com.hunsu.note.io

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.Closeable

/**
 * PDF 파일을 열어 페이지별 비율을 알려주고, 필요한 페이지만 비트맵으로 렌더링한다.
 * PdfRenderer 는 스레드 안전하지 않으므로 모든 호출을 synchronized 로 감싼다.
 */
class PdfPageProvider(context: Context, uri: Uri) : Closeable {

    private val fileDescriptor: ParcelFileDescriptor? =
        try {
            context.contentResolver.openFileDescriptor(uri, "r")
        } catch (e: Exception) {
            null
        }

    private val renderer: PdfRenderer? = try {
        fileDescriptor?.let { PdfRenderer(it) }
    } catch (e: Exception) {
        null
    }

    private val aspects = ArrayList<Float>()
    private val cache = LinkedHashMap<String, Bitmap>()

    @Volatile
    private var closed = false

    companion object {
        private const val CACHE_SIZE = 5
        private const val DEFAULT_ASPECT = 0.7071f // A4 세로
    }

    val isOpen: Boolean get() = renderer != null && !closed

    val pageCount: Int
        get() = if (closed) 0 else (renderer?.pageCount ?: 0)

    init {
        val r = renderer
        if (r != null) {
            var i = 0
            while (i < r.pageCount) {
                var aspect = DEFAULT_ASPECT
                try {
                    val p = r.openPage(i)
                    try {
                        if (p.height > 0) aspect = p.width.toFloat() / p.height.toFloat()
                    } finally {
                        p.close()
                    }
                } catch (e: Exception) {
                    // 비율을 못 읽으면 기본값 유지
                }
                aspects.add(aspect)
                i++
            }
        }
    }

    fun aspectOf(index: Int): Float = aspects.getOrNull(index) ?: DEFAULT_ASPECT

    @Synchronized
    fun render(index: Int, targetWidth: Int): Bitmap? {
        val r = renderer ?: return null
        if (closed || index < 0 || index >= r.pageCount) return null
        val width = targetWidth.coerceAtLeast(64)
        val key = "$index:$width"
        cache[key]?.let { return it }

        val aspect = aspectOf(index).coerceIn(0.2f, 5f)
        val height = (width / aspect).toInt().coerceAtLeast(64)

        val bitmap = try {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            return null
        }
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        try {
            val page = r.openPage(index)
            try {
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            } finally {
                page.close()
            }
        } catch (e: Exception) {
            bitmap.recycle()
            return null
        }

        cache[key] = bitmap
        while (cache.size > CACHE_SIZE) {
            val eldest = cache.keys.firstOrNull() ?: break
            // 화면에 쓰이고 있을 수 있으므로 recycle 하지 않고 참조만 해제한다.
            cache.remove(eldest)
        }
        return bitmap
    }

    override fun close() {
        closed = true
        cache.clear()
        try {
            renderer?.close()
        } catch (e: Exception) {
            // 무시
        }
        try {
            fileDescriptor?.close()
        } catch (e: Exception) {
            // 무시
        }
    }
}
