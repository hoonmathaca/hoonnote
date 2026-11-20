package com.hunsu.note.io

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor

/**
 * PDF 파일을 열어 페이지별 비율을 알려주고, 필요한 페이지만 비트맵으로 렌더링한다.
 * PdfRenderer 는 스레드 안전하지 않으므로 모든 호출을 synchronized 로 감싼다.
 */
class PdfPageProvider(context: Context, uri: Uri) : PdfPageSource {

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
        private const val CACHE_SIZE = 3
        private const val DEFAULT_ASPECT = 0.7071f // A4 세로

        /** 한 장에 쓸 수 있는 최대 변 길이와 픽셀 수(메모리 보호) */
        private const val MAX_SIDE = 4096
        private const val MAX_PIXELS = 5_000_000L
    }

    override val isOpen: Boolean get() = renderer != null && !closed

    override val pageCount: Int
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

    override fun aspectOf(index: Int): Float = aspects.getOrNull(index) ?: DEFAULT_ASPECT

    @Synchronized
    override fun render(index: Int, targetWidth: Int): Bitmap? {
        val r = renderer ?: return null
        // 0부터 시작하는 인덱스만 받는다. 범위를 벗어나면 그리지 않고 null 을 돌려준다.
        if (closed || index < 0 || index >= r.pageCount) return null
        val aspect = aspectOf(index).coerceIn(0.05f, 20f)
        var width = targetWidth.coerceIn(64, MAX_SIDE)
        // 아주 큰 페이지는 총 픽셀 수를 제한해 메모리 부족으로 흰 화면이 되지 않게 한다.
        while (width > 64 && width.toLong() * (width / aspect).toLong() > MAX_PIXELS) {
            width = (width * 3) / 4
        }
        val key = "$index:$width"
        cache[key]?.let { if (!it.isRecycled) return it }

        val height = (width / aspect).toInt().coerceAtLeast(64)

        val bitmap = try {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            return null
        } catch (e: Exception) {
            return null
        }
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        try {
            val page = r.openPage(index)
            try {
                // 비트맵을 페이지 비율에 꼭 맞추고, 크기는 fit-inside 로 자동 조정된다.
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            } finally {
                page.close()
            }
        } catch (e: Exception) {
            bitmap.recycle()
            return null
        } catch (e: OutOfMemoryError) {
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
