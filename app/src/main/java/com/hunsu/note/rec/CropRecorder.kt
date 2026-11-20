package com.hunsu.note.rec

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/**
 * 화면 녹화 '페이지만' 모드.
 *
 * MediaProjection 프레임을 SurfaceTexture(GL)로 받아, 노트 캔버스 영역만 잘라(크롭)
 * MediaCodec(H.264)로 다시 그려 MP4 로 저장한다. 마이크 소리는 AudioRecord + AAC 로
 * 같은 파일에 함께 담는다.
 *
 * - VirtualDisplay 는 화면 전체 크기로 만들고, GL 단계에서 크롭 영역만 샘플링한다.
 * - 크롭 영역은 화면 픽셀 좌표(좌상단 기준)로 받는다.
 * - 마이크를 못 열면 조용히 영상만 녹화한다.
 */
class CropRecorder(
    private val projection: MediaProjection,
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val densityDpi: Int,
    private val cropX: Int,
    private val cropY: Int,
    private val cropWidth: Int,
    private val cropHeight: Int,
    targetHeight: Int,
    private val fps: Int,
    private val bitrateMbps: Int,
    private val withMic: Boolean,
    private val outputFd: FileDescriptor
) {

    companion object {
        private const val TAG = "CropRecorder"
        private const val MIME_VIDEO = "video/avc"
        private const val MIME_AUDIO = "audio/mp4a-latm"
        private const val IFRAME_SECONDS = 2
        private const val MAX_PENDING = 90
        private const val AUDIO_SAMPLE_RATE = 44100
        private const val AUDIO_BITRATE = 128_000
        private const val EGL_RECORDABLE_ANDROID = 0x3142
    }

    // 녹화 크기 (홀수를 피한다)
    private val outHeight: Int
    private val outWidth: Int

    // 화면 밖으로 넘어가지 않게 다듬은 크롭 영역
    private val cropLeft: Int
    private val cropTop: Int
    private val cropW: Int
    private val cropH: Int

    private var eglDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext = EGL14.EGL_NO_CONTEXT
    private var eglPbuffer = EGL14.EGL_NO_SURFACE
    private var eglEncoderSurface = EGL14.EGL_NO_SURFACE
    private var eglConfig: EGLConfig? = null

    private var textureId = 0
    private var surfaceTexture: android.graphics.SurfaceTexture? = null
    private var projectionSurface: Surface? = null
    private var inputSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null

    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null

    private var program = 0
    private var positionBuffer: FloatBuffer? = null
    private var texCoordBuffer: FloatBuffer? = null
    private var texMatrixLocation = 0

    private val texMatrix = FloatArray(16)
    private val cropMatrix = FloatArray(16)
    private val combinedMatrix = FloatArray(16)

    private var videoThread: Thread? = null
    private var audioThread: Thread? = null
    private var audioHandlerThread: HandlerThread? = null

    @Volatile
    private var running = false

    @Volatile
    private var audioEnabled = withMic

    /** 첫 프레임과 같은 기준 시각 — 영상/소리의 타임스탬프를 맞춘다. */
    @Volatile
    private var startNs = 0L

    private val frameQueue = LinkedBlockingQueue<Long>()

    // ------------------------------------------------------------ 먹서

    private val muxerLock = Object()
    private var muxer: MediaMuxer? = null
    private var videoTrack = -1
    private var audioTrack = -1
    private var muxerStarted = false
    private val pendingVideo = ArrayList<PendingSample>()

    private class PendingSample(
        val data: ByteArray,
        val presentationTimeUs: Long,
        val flags: Int
    )

    init {
        cropLeft = cropX.coerceIn(0, max(0, screenWidth - 16))
        cropTop = cropY.coerceIn(0, max(0, screenHeight - 16))
        cropW = cropWidth.coerceIn(16, max(16, screenWidth - cropLeft))
        cropH = cropHeight.coerceIn(16, max(16, screenHeight - cropTop))
        val scale = min(1f, targetHeight.toFloat() / cropH.toFloat())
        outHeight = even((cropH * scale).toInt().coerceAtLeast(16))
        outWidth = even((cropW * scale).toInt().coerceAtLeast(16))
    }

    private fun even(value: Int): Int = max(2, value / 2 * 2)

    // ------------------------------------------------------------ 시작

    /** @return 준비에 실패하면 false (이때는 자원을 스스로 정리한다) */
    fun start(): Boolean {
        startNs = System.nanoTime()
        return try {
            if (!setupEgl()) {
                release()
                return false
            }
            if (!setupVideoCodec()) {
                release()
                return false
            }
            muxer = try {
                MediaMuxer(outputFd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            } catch (e: Exception) {
                Log.e(TAG, "먹서 생성 실패", e)
                release()
                return false
            }
            running = true
            videoThread = Thread({ videoLoop() }, "CropRecorderVideo").also { it.start() }
            if (audioEnabled) {
                audioThread = Thread({ audioLoop() }, "CropRecorderAudio").also { it.start() }
            } else {
                // 소리가 없으면 영상 트랙만으로 먹서를 시작할 수 있다.
                synchronized(muxerLock) { tryStartMuxerLocked() }
            }
            startVirtualDisplay()
            true
        } catch (e: Exception) {
            Log.e(TAG, "녹화 시작 실패", e)
            release()
            false
        }
    }

    private fun startVirtualDisplay() {
        val texture = surfaceTexture ?: return
        val surface = Surface(texture)
        projectionSurface = surface
        virtualDisplay = projection.createVirtualDisplay(
            "HoonNoteCrop",
            screenWidth,
            screenHeight,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            null,
            null
        )
    }

    // ------------------------------------------------------------ EGL / GL

    private fun setupEgl(): Boolean {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            Log.e(TAG, "eglGetDisplay 실패")
            return false
        }
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            Log.e(TAG, "eglInitialize 실패")
            return false
        }
        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(
                eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0
            ) || numConfigs[0] <= 0
        ) {
            Log.e(TAG, "eglChooseConfig 실패")
            return false
        }
        eglConfig = configs[0]
        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(
            eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0
        )
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            Log.e(TAG, "eglCreateContext 실패")
            return false
        }
        val pbufferAttribs = intArrayOf(
            EGL14.EGL_WIDTH, 1,
            EGL14.EGL_HEIGHT, 1,
            EGL14.EGL_NONE
        )
        eglPbuffer = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, pbufferAttribs, 0)
        if (eglPbuffer == EGL14.EGL_NO_SURFACE) {
            Log.e(TAG, "eglCreatePbufferSurface 실패")
            return false
        }
        if (!EGL14.eglMakeCurrent(eglDisplay, eglPbuffer, eglPbuffer, eglContext)) {
            Log.e(TAG, "eglMakeCurrent 실패")
            return false
        }

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE
        )

        surfaceTexture = android.graphics.SurfaceTexture(textureId).also { st ->
            audioHandlerThread = HandlerThread("CropRecorderFrames").also { it.start() }
            val handler = Handler(audioHandlerThread!!.looper)
            st.setOnFrameAvailableListener({ frameQueue.offer(System.nanoTime()) }, handler)
        }

        program = buildProgram()
        if (program == 0) {
            Log.e(TAG, "셰이더 준비 실패")
            return false
        }
        texMatrixLocation = GLES20.glGetUniformLocation(program, "uTexMatrix")

        positionBuffer = floatBuffer(
            floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        )
        texCoordBuffer = floatBuffer(
            floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
        )

        // 크롭 영역만 샘플링하도록 텍스처 좌표를 좁힌다.
        val u0 = cropLeft.toFloat() / screenWidth.toFloat()
        val v0 = cropTop.toFloat() / screenHeight.toFloat()
        val su = min(1f, cropW.toFloat() / screenWidth.toFloat())
        val sv = min(1f, cropH.toFloat() / screenHeight.toFloat())
        Matrix.setIdentityM(cropMatrix, 0)
        Matrix.translateM(cropMatrix, 0, u0, v0, 0f)
        Matrix.scaleM(cropMatrix, 0, su, sv, 1f)

        return true
    }

    private fun floatBuffer(values: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(values)
                position(0)
            }

    private fun buildProgram(): Int {
        val vertexSource = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """.trimIndent()

        val fragmentSource = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """.trimIndent()

        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource) ?: return 0
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource) ?: return 0
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vertexShader)
        GLES20.glAttachShader(prog, fragmentShader)
        GLES20.glLinkProgram(prog)
        val status = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            Log.e(TAG, "프로그램 링크 실패: " + GLES20.glGetProgramInfoLog(prog))
            GLES20.glDeleteProgram(prog)
            return 0
        }
        return prog
    }

    private fun compileShader(type: Int, source: String): Int? {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] != GLES20.GL_TRUE) {
            Log.e(TAG, "셰이더 컴파일 실패: " + GLES20.glGetShaderInfoLog(shader))
            GLES20.glDeleteShader(shader)
            return null
        }
        return shader
    }

    // ------------------------------------------------------------ 영상 인코더

    private fun setupVideoCodec(): Boolean {
        return try {
            val format = MediaFormat.createVideoFormat(MIME_VIDEO, outWidth, outHeight)
            format.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrateMbps * 1_000_000)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_SECONDS)

            val codec = MediaCodec.createEncoderByType(MIME_VIDEO)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = codec.createInputSurface()
            codec.start()
            videoCodec = codec

            eglEncoderSurface = EGL14.eglCreateWindowSurface(
                eglDisplay, eglConfig, inputSurface, intArrayOf(EGL14.EGL_NONE), 0
            )
            if (eglEncoderSurface == EGL14.EGL_NO_SURFACE) {
                Log.e(TAG, "인코더 EGL 서피스 생성 실패")
                return false
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "영상 인코더 준비 실패", e)
            false
        }
    }

    // ------------------------------------------------------------ 영상 루프

    private fun videoLoop() {
        val info = MediaCodec.BufferInfo()
        var lastTimestamp = 0L
        try {
            while (running) {
                val frame = frameQueue.poll(200, TimeUnit.MILLISECONDS)
                if (frame == null) {
                    drainEncoder(videoCodec, true, info, 0L)
                    continue
                }
                val surfaceTexture = surfaceTexture ?: break
                if (!EGL14.eglMakeCurrent(
                        eglDisplay, eglEncoderSurface, eglEncoderSurface, eglContext
                    )
                ) {
                    Log.e(TAG, "eglMakeCurrent(인코더) 실패")
                    break
                }
                try {
                    surfaceTexture.updateTexImage()
                } catch (e: Exception) {
                    Log.w(TAG, "updateTexImage 실패", e)
                    continue
                }
                surfaceTexture.getTransformMatrix(texMatrix)
                Matrix.multiplyMM(combinedMatrix, 0, texMatrix, 0, cropMatrix, 0)
                drawFrame()

                var presentationNs = surfaceTexture.timestamp
                if (presentationNs <= 0L) presentationNs = System.nanoTime()
                if (presentationNs <= lastTimestamp) presentationNs = lastTimestamp + 1_000_000L
                lastTimestamp = presentationNs
                val ptsUs = ((presentationNs - startNs) / 1000L).coerceAtLeast(0L)
                EGLExt.eglPresentationTimeANDROID(eglDisplay, eglEncoderSurface, ptsUs * 1000L)
                if (!EGL14.eglSwapBuffers(eglDisplay, eglEncoderSurface)) {
                    Log.e(TAG, "eglSwapBuffers 실패: ${EGL14.eglGetError()}")
                }
                drainEncoder(videoCodec, true, info, 0L)
            }
            // 종료: 인코더에 마지막 프레임을 알리고 남은 데이터를 모두 꺼낸다.
            try {
                videoCodec?.signalEndOfInputStream()
            } catch (e: Exception) {
                Log.w(TAG, "signalEndOfInputStream 실패", e)
            }
            drainEncoder(videoCodec, true, info, 10_000L, untilEos = true)
        } catch (e: Exception) {
            Log.e(TAG, "영상 루프 오류", e)
        }
    }

    private fun drawFrame() {
        GLES20.glViewport(0, 0, outWidth, outHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

        val posLoc = GLES20.glGetAttribLocation(program, "aPosition")
        val texLoc = GLES20.glGetAttribLocation(program, "aTexCoord")

        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, positionBuffer)

        GLES20.glEnableVertexAttribArray(texLoc)
        GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

        GLES20.glUniformMatrix4fv(texMatrixLocation, 1, false, combinedMatrix, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(texLoc)
    }

    // ------------------------------------------------------------ 소리

    @android.annotation.SuppressLint("MissingPermission")
    private fun audioLoop() {
        var recorder: AudioRecord? = null
        var codec: MediaCodec? = null
        try {
            val minBuffer = AudioRecord.getMinBufferSize(
                AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = max(minBuffer * 2, 8192)
            recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "AudioRecord 초기화 실패 — 영상만 녹화")
                audioEnabled = false
                synchronized(muxerLock) { tryStartMuxerLocked() }
                return
            }
            codec = MediaCodec.createEncoderByType(MIME_AUDIO)
            val format = MediaFormat.createAudioFormat(MIME_AUDIO, AUDIO_SAMPLE_RATE, 1).apply {
                setInteger(
                    MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC
                )
                setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            audioCodec = codec

            recorder.startRecording()
            val pcm = ByteArray(bufferSize)
            val info = MediaCodec.BufferInfo()
            var totalSamples = 0L
            val bytesPerFrame = 2 // mono, 16bit

            while (running) {
                val read = recorder.read(pcm, 0, pcm.size)
                if (read <= 0) continue
                val codecLocal = codec
                var offset = 0
                while (offset < read) {
                    val inIndex = try {
                        codecLocal.dequeueInputBuffer(10_000L)
                    } catch (e: IllegalStateException) {
                        -1
                    }
                    if (inIndex < 0) break
                    val buffer = codecLocal.getInputBuffer(inIndex) ?: break
                    buffer.clear()
                    val chunk = min(buffer.capacity(), read - offset)
                    buffer.put(pcm, offset, chunk)
                    val ptsUs = totalSamples * 1_000_000L / AUDIO_SAMPLE_RATE
                    codecLocal.queueInputBuffer(inIndex, 0, chunk, ptsUs, 0)
                    totalSamples += chunk / bytesPerFrame
                    offset += chunk
                }
                drainEncoder(codecLocal, false, info, 0L)
            }

            // 마지막 패킷
            val inIndex = try {
                codec.dequeueInputBuffer(10_000L)
            } catch (e: IllegalStateException) {
                -1
            }
            if (inIndex >= 0) {
                val ptsUs = totalSamples * 1_000_000L / AUDIO_SAMPLE_RATE
                codec.queueInputBuffer(
                    inIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                )
            }
            drainEncoder(codec, false, info, 10_000L, untilEos = true)
        } catch (e: Exception) {
            Log.e(TAG, "소리 녹음 오류", e)
            audioEnabled = false
            synchronized(muxerLock) { tryStartMuxerLocked() }
        } finally {
            try {
                recorder?.stop()
            } catch (e: Exception) {
                // 무시
            }
            try {
                recorder?.release()
            } catch (e: Exception) {
                // 무시
            }
            try {
                codec?.stop()
            } catch (e: Exception) {
                // 무시
            }
            try {
                codec?.release()
            } catch (e: Exception) {
                // 무시
            }
            audioCodec = null
        }
    }

    // ------------------------------------------------------------ 인코더 → 먹서

    private fun drainEncoder(
        codec: MediaCodec?,
        isVideo: Boolean,
        info: MediaCodec.BufferInfo,
        timeoutUs: Long,
        untilEos: Boolean = false
    ): Boolean {
        if (codec == null) return true
        var endOfStream = false
        val deadline = System.currentTimeMillis() + if (untilEos) 6000L else 0L
        var guard = 0
        while (true) {
            guard++
            if (guard > 20_000) break
            val status = try {
                codec.dequeueOutputBuffer(info, timeoutUs)
            } catch (e: Exception) {
                return true
            }
            when {
                status == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!untilEos || System.currentTimeMillis() > deadline) break
                }

                status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = codec.outputFormat
                    if (isVideo) onVideoFormat(format) else onAudioFormat(format)
                }

                status >= 0 -> {
                    val buffer = codec.getOutputBuffer(status)
                    val isConfig =
                        (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    if (buffer != null && info.size > 0 && !isConfig) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val data = ByteArray(info.size)
                        buffer.get(data)
                        writeSample(isVideo, data, info.presentationTimeUs, info.flags)
                    }
                    codec.releaseOutputBuffer(status, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        endOfStream = true
                        break
                    }
                }

                else -> {
                    // INFO_OUTPUT_BUFFERS_CHANGED 등
                    if (!untilEos) break
                }
            }
        }
        return endOfStream
    }

    private fun onVideoFormat(format: MediaFormat) {
        synchronized(muxerLock) {
            if (videoTrack >= 0) return
            videoTrack = try {
                muxer?.addTrack(format) ?: -1
            } catch (e: Exception) {
                Log.e(TAG, "영상 트랙 추가 실패", e)
                -1
            }
            tryStartMuxerLocked()
        }
    }

    private fun onAudioFormat(format: MediaFormat) {
        synchronized(muxerLock) {
            if (audioTrack >= 0) return
            audioTrack = try {
                muxer?.addTrack(format) ?: -1
            } catch (e: Exception) {
                Log.e(TAG, "소리 트랙 추가 실패", e)
                -1
            }
            tryStartMuxerLocked()
        }
    }

    private fun writeSample(isVideo: Boolean, data: ByteArray, ptsUs: Long, flags: Int) {
        synchronized(muxerLock) {
            if (muxerStarted) {
                val track = if (isVideo) videoTrack else audioTrack
                if (track >= 0) {
                    try {
                        val buffer = ByteBuffer.wrap(data)
                        val info = MediaCodec.BufferInfo().apply {
                            set(0, data.size, ptsUs.coerceAtLeast(0L), flags)
                        }
                        muxer?.writeSampleData(track, buffer, info)
                    } catch (e: Exception) {
                        Log.w(TAG, "샘플 쓰기 실패", e)
                    }
                }
            } else if (isVideo) {
                if (pendingVideo.size >= MAX_PENDING) pendingVideo.removeAt(0)
                pendingVideo.add(PendingSample(data, ptsUs, flags))
            }
        }
    }

    /** 영상·소리 트랙 정보가 모두 준비되면 먹서를 시작하고 밀린 영상 샘플을 쓴다. */
    private fun tryStartMuxerLocked() {
        if (muxerStarted) return
        val target = muxer ?: return
        if (videoTrack < 0) return
        if (audioEnabled && audioTrack < 0) return
        try {
            target.start()
            muxerStarted = true
        } catch (e: Exception) {
            Log.e(TAG, "먹서 시작 실패", e)
            return
        }
        for (sample in pendingVideo) {
            try {
                val buffer = ByteBuffer.wrap(sample.data)
                val info = MediaCodec.BufferInfo().apply {
                    set(0, sample.data.size, sample.presentationTimeUs, sample.flags)
                }
                target.writeSampleData(videoTrack, buffer, info)
            } catch (e: Exception) {
                Log.w(TAG, "밀린 샘플 쓰기 실패", e)
            }
        }
        pendingVideo.clear()
    }

    // ------------------------------------------------------------ 종료/정리

    fun stop() {
        if (!running) {
            release()
            return
        }
        running = false
        try {
            videoThread?.join(8000)
        } catch (e: InterruptedException) {
            // 무시
        }
        try {
            audioThread?.join(4000)
        } catch (e: InterruptedException) {
            // 무시
        }
        release()
    }

    /** 녹화된 트랙이 하나라도 있었는지 (없으면 빈 파일) */
    fun hasTrack(): Boolean = synchronized(muxerLock) { muxerStarted }

    private fun release() {
        running = false
        try {
            virtualDisplay?.release()
        } catch (e: Exception) {
            // 무시
        }
        virtualDisplay = null

        try {
            projectionSurface?.release()
        } catch (e: Exception) {
            // 무시
        }
        projectionSurface = null

        try {
            surfaceTexture?.setOnFrameAvailableListener(null)
            surfaceTexture?.release()
        } catch (e: Exception) {
            // 무시
        }
        surfaceTexture = null

        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            try {
                EGL14.eglMakeCurrent(
                    eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
                )
                if (eglEncoderSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, eglEncoderSurface)
                }
                if (eglPbuffer != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, eglPbuffer)
                }
                if (eglContext != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(eglDisplay, eglContext)
                }
                EGL14.eglTerminate(eglDisplay)
            } catch (e: Exception) {
                // 무시
            }
        }
        eglEncoderSurface = EGL14.EGL_NO_SURFACE
        eglPbuffer = EGL14.EGL_NO_SURFACE
        eglContext = EGL14.EGL_NO_CONTEXT
        eglDisplay = EGL14.EGL_NO_DISPLAY

        if (program != 0) {
            try {
                GLES20.glDeleteProgram(program)
            } catch (e: Exception) {
                // 무시
            }
            program = 0
        }

        try {
            inputSurface?.release()
        } catch (e: Exception) {
            // 무시
        }
        inputSurface = null

        videoCodec?.let { codec ->
            try {
                codec.stop()
            } catch (e: Exception) {
                // 무시
            }
            try {
                codec.release()
            } catch (e: Exception) {
                // 무시
            }
        }
        videoCodec = null

        synchronized(muxerLock) {
            val target = muxer
            muxer = null
            if (target != null) {
                if (muxerStarted) {
                    try {
                        target.stop()
                    } catch (e: Exception) {
                        Log.w(TAG, "먹서 정지 실패", e)
                    }
                }
                try {
                    target.release()
                } catch (e: Exception) {
                    // 무시
                }
            }
            muxerStarted = false
        }

        try {
            audioHandlerThread?.quitSafely()
        } catch (e: Exception) {
            // 무시
        }
        audioHandlerThread = null
        frameQueue.clear()
    }
}
