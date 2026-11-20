package com.hunsu.note.io

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.hunsu.note.model.Page
import com.hunsu.note.view.PagePainter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 페이지를 PNG 로 저장하거나, 전체 페이지를 PDF 로 내보낸다.
 * Android 10+ 는 MediaStore(스코프 저장소), 9 이하는 공용 폴더 + 미디어 스캔을 사용한다.
 */
object PageExporter {

    fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    /** 페이지 한 장을 world 좌표 크기의 비트맵으로 렌더링한다. */
    fun renderPage(page: Page, background: Bitmap?): Bitmap {
        val width = page.worldWidth.toInt().coerceAtLeast(1)
        val height = page.worldHeight.toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        PagePainter.drawPage(canvas, page, background, paint)
        return bitmap
    }

    /** 현재 페이지를 PNG 로 저장하고, 저장 위치를 사람이 읽을 수 있는 문자열로 돌려준다. */
    fun savePng(context: Context, bitmap: Bitmap, baseName: String): String? {
        val name = "$baseName.png"
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/HunsuNote"
                    )
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return null
                var written = false
                resolver.openOutputStream(uri)?.use { out ->
                    written = bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                if (!written) {
                    resolver.delete(uri, null, null)
                    return null
                }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                "Pictures/HunsuNote/$name"
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "HunsuNote"
                )
                if (!dir.exists() && !dir.mkdirs()) return null
                val file = File(dir, name)
                FileOutputStream(file).use { out ->
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) return null
                }
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(file.absolutePath),
                    arrayOf("image/png"),
                    null
                )
                file.absolutePath
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 모든 페이지를 PDF 로 내보낸다.
     * @param backgroundFor 페이지별 배경(PDF 페이지면 렌더링된 비트맵, 빈 페이지면 null)
     * @return 저장 위치 문자열, 실패 시 null
     */
    fun exportPdf(
        context: Context,
        pages: List<Page>,
        backgroundFor: (Page) -> Bitmap?,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): String? {
        if (pages.isEmpty()) return null
        val document = PdfDocument()
        try {
            pages.forEachIndexed { index, page ->
                val aspect = page.worldWidth / page.worldHeight
                val pageWidth = 595
                val pageHeight = (pageWidth / aspect).toInt().coerceAtLeast(1)
                val info = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, index + 1).create()
                val pdfPage = document.startPage(info)
                val bitmap = renderPage(page, backgroundFor(page))
                pdfPage.canvas.drawBitmap(
                    bitmap, null,
                    RectF(0f, 0f, pageWidth.toFloat(), pageHeight.toFloat()),
                    Paint(Paint.FILTER_BITMAP_FLAG)
                )
                document.finishPage(pdfPage)
                bitmap.recycle()
                onProgress(index + 1, pages.size)
            }
            val name = "HunsuNote_${timestamp()}.pdf"
            return savePdfDocument(context, document, name)
        } catch (e: OutOfMemoryError) {
            return null
        } catch (e: Exception) {
            return null
        } finally {
            try {
                document.close()
            } catch (e: Exception) {
                // 무시
            }
        }
    }

    private fun savePdfDocument(context: Context, document: PdfDocument, name: String): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                    put(
                        MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/HunsuNote"
                    )
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return null
                var written = false
                val out: OutputStream? = resolver.openOutputStream(uri)
                if (out != null) {
                    out.use {
                        document.writeTo(it)
                        written = true
                    }
                }
                if (!written) {
                    resolver.delete(uri, null, null)
                    return null
                }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                "Download/HunsuNote/$name"
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "HunsuNote"
                )
                if (!dir.exists() && !dir.mkdirs()) return null
                val file = File(dir, name)
                FileOutputStream(file).use { document.writeTo(it) }
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(file.absolutePath),
                    arrayOf("application/pdf"),
                    null
                )
                file.absolutePath
            }
        } catch (e: Exception) {
            null
        }
    }
}
