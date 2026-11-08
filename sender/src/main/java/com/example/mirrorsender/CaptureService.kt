package com.example.mirrorsender

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer

/**
 * Screen -> hardware H.264 encoder (zero-copy via Surface) -> TCP -> TV.
 * Phone audio (AudioPlaybackCapture) -> raw PCM -> same TCP connection.
 *
 * Wire format per frame: [1 byte type][8 bytes pts us][4 bytes size][data]
 * type: 0 = codec config, 1 = key frame, 2 = delta frame,
 *       3 = pong (latency stats), 4 = audio (PCM 16-bit stereo 48 kHz)
 * TV -> phone: an 8-byte "ping" (long) about once per second.
 */
class CaptureService : Service() {
    companion object {
        const val ACTION_STOP = "com.example.mirrorsender.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_IP = "ip"
        const val EXTRA_MBPS = "mbps"
        const val EXTRA_SIDE = "side"
        const val EXTRA_AUDIO = "audio"
        const val EXTRA_DIM = "dim"
        const val EXTRA_MUTE = "mute"
        /** true while a mirroring session is alive in this process */
        @Volatile var running = false
        const val PORT = 5600
        private const val CHANNEL = "mirror"
        private const val TAG = "MirrorSender"
        private const val SAMPLE_RATE = 48000
        private const val TYPE_CONFIG = 0
        private const val TYPE_KEY = 1
        private const val TYPE_DELTA = 2
        private const val TYPE_PONG = 3
        private const val TYPE_AUDIO = 4
    }

    private val main = Handler(Looper.getMainLooper())
    private var worker: HandlerThread? = null
    private var handler: Handler? = null
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var audioRecord: AudioRecord? = null
    private var socket: Socket? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val outLock = Any()
    @Volatile private var out: DataOutputStream? = null
    @Volatile private var stopped = false
    @Volatile private var encMs = -1f   // smoothed capture->encoded time, -1 = unknown
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopAll()
            return START_NOT_STICKY
        }
        if (started || stopped) return START_NOT_STICKY
        started = true
        running = true

        showNotification()   // must happen BEFORE getMediaProjection()

        val code = intent.getIntExtra(EXTRA_CODE, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
        }
        val ip = intent.getStringExtra(EXTRA_IP) ?: ""
        val mbps = intent.getIntExtra(EXTRA_MBPS, 8)
        val side = intent.getIntExtra(EXTRA_SIDE, 1280)
        val wantAudio = intent.getBooleanExtra(EXTRA_AUDIO, true)
        val dim = intent.getBooleanExtra(EXTRA_DIM, false)
        val mute = intent.getBooleanExtra(EXTRA_MUTE, false)
        if (data == null || ip.isEmpty()) {
            stopAll()
            return START_NOT_STICKY
        }

        val t = HandlerThread("mirror-worker").also { it.start() }
        worker = t
        handler = Handler(t.looper)
        handler!!.post {
            try {
                begin(code, data, ip, mbps, side, wantAudio, dim, mute)
            } catch (e: Exception) {
                Log.e(TAG, "start failed", e)
                requestStop()
            }
        }
        return START_NOT_STICKY
    }

    private fun showNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Mirroring", NotificationManager.IMPORTANCE_LOW)
        )
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val action = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_delete), "Stop", stopPi
        ).build()
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("Mirroring to TV")
            .setContentText("Tap Stop to end")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .addAction(action)
            .setOngoing(true)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    private fun format(w: Int, h: Int, bps: Int, full: Boolean): MediaFormat =
        MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bps)
            setInteger(MediaFormat.KEY_FRAME_RATE, 60)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            if (full) {
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                setInteger(MediaFormat.KEY_PRIORITY, 0)            // real-time
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)        // no frame reordering
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
                setInteger("prepend-sps-pps-to-idr-frames", 1)
            }
        }

    /** All writes to the TV go through here so video, audio and stats never interleave mid-frame. */
    @Throws(IOException::class)
    private fun sendFrame(type: Int, pts: Long, data: ByteArray) {
        val o = out ?: return
        synchronized(outLock) {
            o.writeByte(type)
            o.writeLong(pts)
            o.writeInt(data.size)
            o.write(data)
            o.flush()
        }
    }

    private fun begin(code: Int, data: Intent, ip: String, mbps: Int, side: Int, wantAudio: Boolean, dim: Boolean, mute: Boolean) {
        // 1) connect to the TV
        val s = Socket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(ip, PORT), 5000)
        socket = s
        out = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 1 shl 16))
        startPongResponder(s)

        // 2) pick output size (keep aspect, multiples of 16)
        val bounds = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        val scale = minOf(1f, side.toFloat() / maxOf(bounds.width(), bounds.height()))
        val w = maxOf(16, (bounds.width() * scale).toInt() / 16 * 16)
        val h = maxOf(16, (bounds.height() * scale).toInt() / 16 * 16)
        val dpi = resources.displayMetrics.densityDpi

        // 3) low-latency hardware encoder
        val cb = object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(c: MediaCodec, index: Int) {}
            override fun onOutputFormatChanged(c: MediaCodec, format: MediaFormat) {}
            override fun onError(c: MediaCodec, e: MediaCodec.CodecException) {
                Log.e(TAG, "encoder error", e)
                requestStop()
            }

            override fun onOutputBufferAvailable(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                try {
                    if (info.size > 0 && !stopped) {
                        val buf = c.getOutputBuffer(index)
                        if (buf != null) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            val bytes = ByteArray(info.size)
                            buf.get(bytes)
                            val type = when {
                                info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 -> TYPE_CONFIG
                                info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0 -> TYPE_KEY
                                else -> TYPE_DELTA
                            }
                            if (type != TYPE_CONFIG) {
                                // Frame timestamps normally use the same clock as System.nanoTime().
                                // If they don't on this phone the value is out of range and ignored.
                                val encUs = System.nanoTime() / 1000 - info.presentationTimeUs
                                if (encUs in 0..500_000) {
                                    val ms = encUs / 1000f
                                    encMs = if (encMs < 0) ms else encMs * 0.9f + ms * 0.1f
                                }
                            }
                            sendFrame(type, info.presentationTimeUs, bytes)
                        }
                    }
                } catch (e: IOException) {
                    requestStop()
                } catch (e: IllegalStateException) {
                    // codec already released
                } finally {
                    runCatching { c.releaseOutputBuffer(index, false) }
                }
            }
        }

        val bps = mbps * 1_000_000
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        enc.setCallback(cb, handler)
        try {
            enc.configure(format(w, h, bps, true), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            Log.w(TAG, "low-latency options rejected, using basic config", e)
            enc.reset()
            enc.setCallback(cb, handler)
            enc.configure(format(w, h, bps, false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        inputSurface = enc.createInputSurface()
        enc.start()
        encoder = enc

        // 4) screen capture straight into the encoder's input surface
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = mpm.getMediaProjection(code, data)
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { requestStop() }
        }, handler)
        projection = p
        display = p.createVirtualDisplay(
            "mirror", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface, null, null
        )

        // 5) phone audio (optional; a failure here never stops the video)
        val audioOk = wantAudio && startAudio(p)

        // 6) battery saver: minimum brightness + keep the screen from timing out
        if (dim) {
            Brightness.dim(this)
            keepScreenAwake()
        }

        // 7) silence the phone's own speaker, but only if the audio is really reaching the TV
        if (mute && audioOk) AudioMute.mute(this)

        Log.i(TAG, "streaming ${w}x$h @ ${mbps}Mbps to $ip:$PORT audio=$wantAudio")
    }

    /** Answers the TV's pings. The reply travels in the same queue as the video, so the TV's
     *  round-trip time includes any sender-side queueing, which is what we want to measure. */
    private fun startPongResponder(s: Socket) {
        Thread {
            try {
                val din = DataInputStream(BufferedInputStream(s.getInputStream()))
                while (!stopped) {
                    val t0 = din.readLong()
                    val enc = encMs
                    val payload = ByteBuffer.allocate(12)
                        .putLong(t0)
                        .putInt(if (enc < 0) -1 else (enc * 10).toInt())
                        .array()
                    sendFrame(TYPE_PONG, 0, payload)
                }
            } catch (e: IOException) {
                if (!stopped) requestStop()
            }
        }.apply { isDaemon = true; start() }
    }

    private fun startAudio(p: MediaProjection): Boolean {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "no audio permission, sending video only")
            return false
        }
        try {
            val cfg = AudioPlaybackCaptureConfiguration.Builder(p)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val fmt = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val chunk = SAMPLE_RATE / 50 * 4          // 20 ms of 16-bit stereo
            val min = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
            )
            val rec = AudioRecord.Builder()
                .setAudioFormat(fmt)
                .setAudioPlaybackCaptureConfig(cfg)
                .setBufferSizeInBytes(maxOf(min, chunk * 4))
                .build()
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "audio capture not available")
                rec.release()
                return false
            }
            audioRecord = rec
            rec.startRecording()
            Thread {
                val buf = ByteArray(chunk)
                try {
                    while (!stopped) {
                        val n = rec.read(buf, 0, buf.size)
                        if (n > 0) sendFrame(TYPE_AUDIO, System.nanoTime() / 1000, buf.copyOf(n))
                        else if (n < 0) break
                    }
                } catch (e: IOException) {
                    if (!stopped) requestStop()
                } catch (e: IllegalStateException) {
                    // recorder stopped
                }
            }.apply { isDaemon = true; start() }
            return true
        } catch (e: Exception) {
            Log.w(TAG, "audio capture unavailable, continuing without audio", e)
            return false
        }
    }

    @Suppress("DEPRECATION")
    private fun keepScreenAwake() {
        try {
            val pm = getSystemService(PowerManager::class.java)
            val wl = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "$packageName:mirror")
            wl.setReferenceCounted(false)
            wl.acquire(4 * 60 * 60 * 1000L)   // safety timeout: 4 hours
            wakeLock = wl
        } catch (e: Exception) {
            Log.w(TAG, "could not hold the screen awake", e)
        }
    }

    private fun requestStop() { main.post { stopAll() } }

    private fun stopAll() {
        if (stopped) return
        stopped = true
        running = false
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        runCatching { display?.release() }
        runCatching { projection?.stop() }
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        runCatching { inputSurface?.release() }
        runCatching { socket?.close() }
        runCatching { worker?.quitSafely() }
        runCatching { wakeLock?.release() }
        Brightness.restore(this)
        AudioMute.restore(this)
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    override fun onDestroy() {
        stopAll()
        super.onDestroy()
    }
}
