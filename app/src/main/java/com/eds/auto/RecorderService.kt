package com.eds.auto

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.StatFs
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One camera: opens once, then records one MP4 segment at a time. */
class CamRecorder(
    private val ctx: Context,
    private val mgr: CameraManager,
    private val id: String,
    val label: String,
    private val handler: Handler,
    private val audio: Boolean,
    private val landscape: Boolean
) {
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var recorder: MediaRecorder? = null
    private var reported = false

    @SuppressLint("MissingPermission")
    fun open(cb: (Boolean) -> Unit) {
        try {
            mgr.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    device = d
                    if (!reported) { reported = true; cb(true) }
                }
                override fun onDisconnected(d: CameraDevice) {
                    d.close(); device = null
                    if (!reported) { reported = true; cb(false) }
                }
                override fun onError(d: CameraDevice, error: Int) {
                    Log.e("EdsAuto", "Camera $label error $error")
                    d.close(); device = null
                    if (!reported) { reported = true; cb(false) }
                }
            }, handler)
        } catch (e: Exception) {
            Log.e("EdsAuto", "open failed", e)
            if (!reported) { reported = true; cb(false) }
        }
    }

    fun startSegment(file: File) {
        val dev = device ?: return
        try {
            val chars = mgr.getCameraCharacteristics(id)
            val sensor = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val rot = if (landscape) 90 else 0
            val hint = if (label == "front") (sensor + rot) % 360 else (sensor - rot + 360) % 360

            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            val sizes = map.getOutputSizes(MediaRecorder::class.java)
            val size = sizes.firstOrNull { it.width == 1280 && it.height == 720 }
                ?: sizes.filter { it.width <= 1920 }.maxByOrNull { it.width * it.height }
                ?: sizes.first()

            val rec = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
            // Only one camera records sound, so the two files don't both capture the mic.
            val withAudio = audio && label == "back"
            if (withAudio) rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setOutputFile(file.absolutePath)
            rec.setVideoEncodingBitRate(RecorderService.VIDEO_BITRATE)
            rec.setVideoFrameRate(30)
            rec.setVideoSize(size.width, size.height)
            rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            if (withAudio) rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setOrientationHint(hint)
            rec.prepare()
            recorder = rec

            val surface = rec.surface
            val request = dev.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply { addTarget(surface) }

            @Suppress("DEPRECATION")
            dev.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    try {
                        s.setRepeatingRequest(request.build(), null, handler)
                        rec.start()
                    } catch (e: Exception) {
                        Log.e("EdsAuto", "start failed", e)
                    }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    Log.e("EdsAuto", "Session configure failed ($label)")
                }
            }, handler)
        } catch (e: Exception) {
            Log.e("EdsAuto", "startSegment failed ($label)", e)
        }
    }

    fun stopSegment() {
        try { session?.stopRepeating() } catch (_: Exception) {}
        try { session?.abortCaptures() } catch (_: Exception) {}
        try { recorder?.stop() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        try { session?.close() } catch (_: Exception) {}
        session = null
    }

    fun close() {
        stopSegment()
        try { device?.close() } catch (_: Exception) {}
        device = null
    }
}

class RecorderService : Service() {

    companion object {
        const val ACTION_START = "com.eds.auto.START"
        const val ACTION_STOP = "com.eds.auto.STOP"
        const val EXTRA_LIMIT_MB = "limit_mb"
        const val EXTRA_AUDIO = "audio"
        const val EXTRA_LANDSCAPE = "landscape"
        const val EXTRA_FORCE_BOTH = "force_both"

        const val VIDEO_BITRATE = 4_000_000          // 4 Mbit/s, about 300 MB per 10 minutes per camera
        const val SEGMENT_MS = 10 * 60 * 1000L       // 10 minutes
        private const val BYTES_PER_SEGMENT = VIDEO_BITRATE / 8L * 600L
        private const val CHANNEL = "eds_auto_rec"
        private const val NOTIF_ID = 1

        @Volatile
        var running = false

        fun recordingDir(ctx: Context): File =
            (ctx.getExternalFilesDir("EdsAuto") ?: File(ctx.filesDir, "EdsAuto")).also { it.mkdirs() }
    }

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private val cams = mutableListOf<CamRecorder>()
    private var dir: File? = null
    private var limitBytes = 16L * 1024 * 1024 * 1024
    private var audio = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY

        limitBytes = (intent?.getLongExtra(EXTRA_LIMIT_MB, 16 * 1024L) ?: 16 * 1024L) * 1024L * 1024L
        audio = intent?.getBooleanExtra(EXTRA_AUDIO, false) ?: false
        val landscape = intent?.getBooleanExtra(EXTRA_LANDSCAPE, true) ?: true
        val forceBoth = intent?.getBooleanExtra(EXTRA_FORCE_BOTH, false) ?: false

        createChannel()
        goForeground("Starting dash cam...")

        val mgr = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val back = findCamera(mgr, CameraCharacteristics.LENS_FACING_BACK)
        val front = findCamera(mgr, CameraCharacteristics.LENS_FACING_FRONT)

        val wanted = mutableListOf<Pair<String, String>>()
        if (back != null) wanted += back to "back"
        val both = back != null && front != null && (forceBoth || concurrentOk(mgr, back, front))
        if (both) wanted += front!! to "front"
        if (wanted.isEmpty()) {
            shutdown()
            return START_NOT_STICKY
        }

        goForeground(if (both) "Recording front + back" else "Recording back camera only")
        dir = recordingDir(this)
        running = true

        val t = HandlerThread("eds-rec").also { it.start() }
        thread = t
        val h = Handler(t.looper)
        handler = h

        var opened = 0
        var done = 0
        h.post {
            for ((id, label) in wanted) {
                val cam = CamRecorder(this, mgr, id, label, h, audio, landscape)
                cam.open { ok ->
                    h.post {
                        done++
                        if (ok) { opened++; cams += cam }
                        if (done == wanted.size) {
                            if (opened == 0) shutdown() else {
                                startSegments()
                                h.postDelayed(rollover, SEGMENT_MS)
                            }
                        }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private val rollover = object : Runnable {
        override fun run() {
            cams.forEach { it.stopSegment() }
            startSegments()
            handler?.postDelayed(this, SEGMENT_MS)
        }
    }

    private fun startSegments() {
        val d = dir ?: return
        prune(d)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        cams.forEach { it.startSegment(File(d, "${stamp}_${it.label}.mp4")) }
    }

    /** Delete oldest clips until there is room for the next segment under the limit. */
    private fun prune(d: File) {
        val files = d.listFiles { f -> f.extension == "mp4" }
            ?.sortedBy { it.lastModified() }?.toMutableList() ?: return
        val reserve = BYTES_PER_SEGMENT * maxOf(cams.size, 1) * 11 / 10
        var total = files.sumOf { it.length() }
        while (files.isNotEmpty()) {
            val free = try { StatFs(d.path).availableBytes } catch (_: Exception) { Long.MAX_VALUE }
            if (total + reserve <= limitBytes && free > reserve + 200L * 1024 * 1024) break
            val oldest = files.removeAt(0)
            total -= oldest.length()
            oldest.delete()
        }
    }

    private fun findCamera(mgr: CameraManager, facing: Int): String? =
        mgr.cameraIdList.firstOrNull {
            mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == facing
        }

    private fun concurrentOk(mgr: CameraManager, a: String, b: String): Boolean =
        Build.VERSION.SDK_INT >= 30 && mgr.concurrentCameraIds.any { it.containsAll(setOf(a, b)) }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Dash cam", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun goForeground(text: String) {
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("Ed's Auto")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (audio) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            startForeground(NOTIF_ID, n, type)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun shutdown() {
        running = false
        val h = handler
        val t = thread
        if (h != null && t != null) {
            h.removeCallbacksAndMessages(null)
            h.post {
                cams.forEach { it.close() }
                cams.clear()
                t.quitSafely()
            }
        }
        handler = null
        thread = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (running) shutdown()
        super.onDestroy()
    }
}
