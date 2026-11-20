package com.hunsu.note.rec

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.hunsu.note.MainActivity
import com.hunsu.note.R
import com.hunsu.note.Prefs
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 녹화 설정 묶음 */
data class RecSettings(
    val height: Int,
    val fps: Int,
    val bitrateMbps: Int,
    val mic: Boolean
) {
    companion object {
        fun fromPrefs(): RecSettings = RecSettings(
            Prefs.recordHeight,
            Prefs.recordFps,
            Prefs.recordBitrateMbps,
            Prefs.recordMic
        )
    }
}

/**
 * MediaProjection + MediaRecorder 로 화면(과 마이크)을 MP4 로 녹화하는 포그라운드 서비스.
 * Android 14 부터는 getMediaProjection() 전에 mediaProjection 타입의 포그라운드 서비스가
 * 먼저 떠 있어야 하므로, 서비스가 startForeground() 를 먼저 호출한 뒤 프로젝션을 얻는다.
 */
class ScreenRecordService : Service() {

    companion object {
        private const val TAG = "ScreenRecordService"

        const val ACTION_START = "com.hunsu.note.rec.START"
        const val ACTION_STOP = "com.hunsu.note.rec.STOP"
        const val ACTION_STATE = "com.hunsu.note.rec.STATE"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_FPS = "fps"
        const val EXTRA_BITRATE_MBPS = "bitrate_mbps"
        const val EXTRA_MIC = "mic"
        const val EXTRA_IS_RECORDING = "is_recording"
        const val EXTRA_SAVED_PATH = "saved_path"

        private const val NOTIFICATION_ID = 4711
        private const val CHANNEL_ID = "hunsu_note_recording"

        @Volatile
        var isRecording: Boolean = false
            private set

        fun start(context: Context, resultCode: Int, data: Intent, settings: RecSettings) {
            val intent = Intent(context, ScreenRecordService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
                putExtra(EXTRA_HEIGHT, settings.height)
                putExtra(EXTRA_FPS, settings.fps)
                putExtra(EXTRA_BITRATE_MBPS, settings.bitrateMbps)
                putExtra(EXTRA_MIC, settings.mic)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ScreenRecordService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "stop 요청 실패", e)
            }
        }
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null
    private var outputParcel: ParcelFileDescriptor? = null
    private var outputUri: android.net.Uri? = null
    private var outputFile: File? = null
    private var stopped = false
    private var startedForeground = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // 사용자가 시스템 UI 에서 녹화를 중지한 경우
            if (!stopped) finishRecording(fromProjection = true)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (isRecording) return START_NOT_STICKY
                startForegroundSafely()
                if (!startedForeground) return START_NOT_STICKY
                if (!beginRecording(intent)) {
                    finishRecording(fromProjection = false)
                }
            }

            ACTION_STOP -> finishRecording(fromProjection = false)
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------ 알림/포그라운드

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.record_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                description = getString(R.string.record_notification_text)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ScreenRecordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle(getString(R.string.record_notification_title))
            .setContentText(getString(R.string.record_notification_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .addAction(
                android.R.drawable.ic_media_pause,
                getString(R.string.record_stop_action),
                stopIntent
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startForegroundSafely() {
        if (startedForeground) return
        createChannel()
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            startedForeground = true
        } catch (e: Exception) {
            Log.e(TAG, "startForeground 실패", e)
            notifyMessage(getString(R.string.recording_failed, e.message ?: ""))
            stopSelf()
        }
    }

    // ------------------------------------------------------------ 녹화 시작

    private fun beginRecording(intent: Intent): Boolean {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        @Suppress("DEPRECATION")
        val resultData: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            notifyMessage(getString(R.string.recording_cancelled))
            return false
        }

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (manager == null) {
            notifyMessage(getString(R.string.recording_failed, "MediaProjectionManager 없음"))
            return false
        }

        val mediaProjection = try {
            manager.getMediaProjection(resultCode, resultData)
        } catch (e: Exception) {
            Log.e(TAG, "getMediaProjection 실패", e)
            null
        }
        if (mediaProjection == null) {
            notifyMessage(getString(R.string.recording_failed, "화면 캡처 권한 없음"))
            return false
        }
        projection = mediaProjection
        mediaProjection.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))

        val targetHeight = intent.getIntExtra(EXTRA_HEIGHT, 1080)
        val fps = intent.getIntExtra(EXTRA_FPS, 30)
        val bitrateMbps = intent.getIntExtra(EXTRA_BITRATE_MBPS, 8)
        val withMic = intent.getBooleanExtra(EXTRA_MIC, true)

        val display = currentDisplayMetrics()
        val srcWidth = display.widthPixels
        val srcHeight = display.heightPixels
        val height = minOf(targetHeight, srcHeight)
        var outHeight = height / 2 * 2
        var outWidth = (srcWidth.toFloat() * outHeight / srcHeight).toInt() / 2 * 2
        if (outWidth <= 0) outWidth = 2
        if (outHeight <= 0) outHeight = 2

        val name = "HunsuNote_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"

        try {
            openOutput(name)
        } catch (e: Exception) {
            Log.e(TAG, "출력 파일 생성 실패", e)
            notifyMessage(getString(R.string.recording_failed, e.message ?: "저장소"))
            return false
        }

        val parcel = outputParcel
        if (parcel == null) {
            notifyMessage(getString(R.string.recording_failed, "출력 파일 없음"))
            return false
        }

        try {
            val newRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            newRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            if (withMic) {
                newRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            }
            newRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            newRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            if (withMic) {
                newRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                newRecorder.setAudioEncodingBitRate(128_000)
                newRecorder.setAudioSamplingRate(44_100)
            }
            newRecorder.setVideoSize(outWidth, outHeight)
            newRecorder.setVideoFrameRate(fps)
            newRecorder.setVideoEncodingBitRate(bitrateMbps * 1_000_000)
            newRecorder.setOutputFile(parcel.fileDescriptor)
            newRecorder.prepare()
            newRecorder.start()
            recorder = newRecorder

            virtualDisplay = mediaProjection.createVirtualDisplay(
                "HunsuNoteRecorder",
                outWidth,
                outHeight,
                display.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                newRecorder.surface,
                null,
                null
            )
        } catch (e: Exception) {
            Log.e(TAG, "MediaRecorder 시작 실패", e)
            notifyMessage(getString(R.string.recording_failed, e.message ?: "인코더"))
            return false
        }

        isRecording = true
        notifyState(true, null)
        return true
    }

    private fun currentDisplayMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            metrics.widthPixels = bounds.width()
            metrics.heightPixels = bounds.height()
            metrics.densityDpi = resources.displayMetrics.densityDpi
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
        }
        return metrics
    }

    private fun openOutput(fileName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + "/HunsuNote"
                )
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore 등록 실패")
            outputUri = uri
            outputParcel = resolver.openFileDescriptor(uri, "w")
                ?: throw IllegalStateException("파일 열기 실패")
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                "HunsuNote"
            )
            if (!dir.exists() && !dir.mkdirs()) throw IllegalStateException("폴더 생성 실패")
            val file = File(dir, fileName)
            outputFile = file
            outputParcel = ParcelFileDescriptor.open(
                file,
                ParcelFileDescriptor.MODE_READ_WRITE or
                    ParcelFileDescriptor.MODE_CREATE or
                    ParcelFileDescriptor.MODE_TRUNCATE
            )
        }
    }

    // ------------------------------------------------------------ 녹화 종료

    private fun finishRecording(fromProjection: Boolean) {
        if (stopped) return
        stopped = true

        try {
            virtualDisplay?.release()
        } catch (e: Exception) {
            Log.w(TAG, "virtualDisplay release", e)
        }
        virtualDisplay = null

        recorder?.let { rec ->
            try {
                rec.stop()
            } catch (e: Exception) {
                Log.w(TAG, "recorder stop", e)
            }
            try {
                rec.reset()
            } catch (e: Exception) {
                // 무시
            }
            try {
                rec.release()
            } catch (e: Exception) {
                // 무시
            }
        }
        recorder = null

        projection?.let { proj ->
            try {
                proj.unregisterCallback(projectionCallback)
            } catch (e: Exception) {
                // 무시
            }
            if (!fromProjection) {
                try {
                    proj.stop()
                } catch (e: Exception) {
                    // 무시
                }
            }
        }
        projection = null

        try {
            outputParcel?.close()
        } catch (e: Exception) {
            // 무시
        }
        outputParcel = null

        val savedPath = finalizeOutput()
        isRecording = false
        notifyState(false, savedPath)

        if (startedForeground) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
            } catch (e: Exception) {
                // 무시
            }
            startedForeground = false
        }
        stopSelf()
    }

    private fun finalizeOutput(): String? {
        val uri = outputUri
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri != null) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
                contentResolver.update(uri, values, null, null)
                "Movies/HunsuNote"
            } else {
                val file = outputFile ?: return null
                MediaScannerConnection.scanFile(
                    this,
                    arrayOf(file.absolutePath),
                    arrayOf("video/mp4"),
                    null
                )
                file.absolutePath
            }
        } catch (e: Exception) {
            Log.w(TAG, "출력 마무리 실패", e)
            null
        }
    }

    // ------------------------------------------------------------ 상태 알림

    private fun notifyState(recording: Boolean, savedPath: String?) {
        val intent = Intent(ACTION_STATE).setPackage(packageName)
        intent.putExtra(EXTRA_IS_RECORDING, recording)
        if (savedPath != null) intent.putExtra(EXTRA_SAVED_PATH, savedPath)
        sendBroadcast(intent)
    }

    private fun notifyMessage(message: String) {
        Handler(Looper.getMainLooper()).post {
            try {
                Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                // 무시
            }
        }
    }

    override fun onDestroy() {
        if (!stopped) {
            // 시스템이 서비스를 내린 경우에도 파일을 마무리한다.
            finishRecording(fromProjection = false)
        }
        super.onDestroy()
    }
}
