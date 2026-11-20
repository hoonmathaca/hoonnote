package com.hunsu.note.io

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream

/**
 * 페이지 배경(PDF·이미지) 비트맵을 앱 내부 캐시 폴더(cacheDir)에 파일로 저장하고,
 * 화면에 쓰는 몇 장만 메모리에 남기는 저장소.
 *
 * 3.3 부터 PDF·이미지를 이어서 쌓을 수 있게 되면서, 페이지 수만큼 배경 비트맵을
 * 메모리에 들고 있으면 금방 램이 부족해진다. 그래서
 * - 배경은 [save] 로 캐시 파일(PNG/JPEG)에 두고,
 * - 지금 보는 페이지(과 최근에 본 페이지)만 [putMemory] 로 메모리에 올리며,
 * - 메모리 예산([maxMemoryBytes])을 넘으면 가장 오래 안 본 것부터 [getMemory] 목록에서 뺀다.
 *
 * 파일을 다시 읽을 때는 [load] 에 원하는 폭을 주면 그보다 조금 큰 표본만 디코딩하므로
 * 썸네일을 만들 때 원본 크기 비트맵을 통째로 올리지 않는다.
 */
class PageBackgroundCache(
    private val dir: File,
    private val maxMemoryBytes: Long = DEFAULT_MEMORY_BYTES,
    private val maxDiskBytes: Long = DEFAULT_DISK_BYTES
) {

    /** 키 → 저장된 파일 (확장자는 저장할 때 정해진다) */
    private val files = HashMap<String, File>()

    /** 접근 순서 LRU. 가장 앞이 가장 오래 안 본 비트맵이다. */
    private val memory = LinkedHashMap<String, Bitmap>(8, 0.75f, true)
    private var memoryBytes = 0L

    companion object {
        const val FILE_PREFIX = "bg_"
        const val DEFAULT_MEMORY_BYTES = 48L * 1024 * 1024
        const val DEFAULT_DISK_BYTES = 512L * 1024 * 1024
        const val JPEG_QUALITY = 92

        fun bytesOf(bitmap: Bitmap): Long = bitmap.byteCount.toLong()

        private fun safeName(key: String): String =
            key.map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '_' }
                .joinToString("")
    }

    /** 이 키의 배경이 이미 캐시 파일로 있는지 */
    @Synchronized
    fun hasFile(key: String): Boolean {
        val file = files[key] ?: return false
        if (file.exists()) return true
        files.remove(key)
        return false
    }

    /**
     * 비트맵을 캐시 파일로 저장한다.
     * PDF(글자·도형 위주)는 PNG, 사진 이미지는 JPEG 로 두어 파일 크기를 줄인다.
     */
    @Synchronized
    fun save(key: String, bitmap: Bitmap, jpeg: Boolean = false): Boolean {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return false
        if (!dir.exists() && !dir.mkdirs()) return false

        val name = FILE_PREFIX + safeName(key) + if (jpeg) ".jpg" else ".png"
        val target = File(dir, name)
        val temp = File(dir, "$name.tmp")
        val written = try {
            FileOutputStream(temp).use { out ->
                bitmap.compress(
                    if (jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG,
                    if (jpeg) JPEG_QUALITY else 100,
                    out
                )
            }
        } catch (e: Exception) {
            false
        } catch (e: OutOfMemoryError) {
            false
        }
        if (!written || !temp.exists() || temp.length() <= 0L) {
            temp.delete()
            return false
        }

        // 같은 키의 예전 파일(다른 확장자)이 있으면 지운다.
        files[key]?.let { if (it.absolutePath != target.absolutePath) it.delete() }
        target.delete()
        if (!temp.renameTo(target)) {
            temp.delete()
            return false
        }
        files[key] = target
        trimDisk(keepKey = key)
        return true
    }

    /**
     * 캐시 파일을 비트맵으로 읽는다.
     * @param targetWidth 0 이면 원본 크기, 아니면 이 폭보다 조금 큰 표본(2의 거듭제곱)으로 읽는다.
     */
    @Synchronized
    fun load(key: String, targetWidth: Int = 0): Bitmap? {
        val file = files[key]
        if (file == null || !file.exists()) {
            files.remove(key)
            return null
        }
        return try {
            if (targetWidth <= 0) {
                BitmapFactory.decodeFile(file.absolutePath)
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                var sample = 1
                var width = bounds.outWidth
                while (width > 0 && width / (sample * 2) >= targetWidth) sample *= 2
                BitmapFactory.decodeFile(
                    file.absolutePath,
                    BitmapFactory.Options().apply { inSampleSize = sample }
                )
            }
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    /** 지금 화면에 쓰는 비트맵을 메모리에 올린다. 예산을 넘으면 오래된 것부터 놓아준다. */
    @Synchronized
    fun putMemory(key: String, bitmap: Bitmap) {
        if (bitmap.isRecycled) return
        memory.remove(key)?.let { memoryBytes -= bytesOf(it) }
        memory[key] = bitmap
        memoryBytes += bytesOf(bitmap)
        trimMemory(keepKey = key)
    }

    /** 메모리에 남아 있는 배경 (없으면 null) */
    @Synchronized
    fun getMemory(key: String): Bitmap? {
        val bitmap = memory[key] ?: return null
        if (bitmap.isRecycled) {
            memory.remove(key)
            memoryBytes -= bytesOf(bitmap)
            return null
        }
        return bitmap
    }

    /** 메모리에 남아 있는 키 목록 (오래된 것부터) */
    @Synchronized
    fun memoryKeys(): List<String> = ArrayList(memory.keys)

    @Synchronized
    fun memoryCount(): Int = memory.size

    @Synchronized
    fun memoryBytes(): Long = memoryBytes

    /** 파일과 메모리에서 한 페이지 배경을 지운다. */
    @Synchronized
    fun evict(key: String) {
        memory.remove(key)?.let { memoryBytes -= bytesOf(it) }
        files.remove(key)?.delete()
    }

    /** 캐시 폴더를 통째로 비운다(새 노트). */
    @Synchronized
    fun clear() {
        memory.clear()
        memoryBytes = 0L
        files.clear()
        try {
            dir.listFiles()?.forEach { it.delete() }
        } catch (e: Exception) {
            // 무시
        }
    }

    /** 캐시 파일 전체 크기(바이트) */
    @Synchronized
    fun diskBytes(): Long {
        var total = 0L
        for (file in files.values) if (file.exists()) total += file.length()
        return total
    }

    @Synchronized
    fun fileCount(): Int = files.count { it.value.exists() }

    private fun trimMemory(keepKey: String) {
        while (memoryBytes > maxMemoryBytes && memory.size > 1) {
            val eldest = memory.entries.firstOrNull { it.key != keepKey } ?: break
            memory.remove(eldest.key)
            memoryBytes -= bytesOf(eldest.value)
        }
    }

    /** 디스크 예산을 넘으면 오래 전에 저장한 파일부터 지운다(지금 쓰는 파일은 남긴다). */
    private fun trimDisk(keepKey: String) {
        var total = 0L
        for (file in files.values) if (file.exists()) total += file.length()
        if (total <= maxDiskBytes) return

        val oldest = files.entries
            .filter { it.key != keepKey && it.value.exists() }
            .sortedBy { it.value.lastModified() }
        for (entry in oldest) {
            if (total <= maxDiskBytes) break
            val length = entry.value.length()
            if (entry.value.delete()) {
                files.remove(entry.key)
                total -= length
            }
        }
    }
}
