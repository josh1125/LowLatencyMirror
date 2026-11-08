package com.example.mirrorreceiver

import android.app.Activity
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Listens for the phone, decodes the incoming H.264 with the TV's hardware decoder in
 * low-latency mode, plays the phone's audio, and shows an estimated latency (top-left).
 * Press OK / ENTER on the TV remote to hide or show the latency box.
 */
class MainActivity : Activity(), SurfaceHolder.Callback {
    companion object {
        const val PORT = 5600
        private const val TAG = "MirrorReceiver"
        private const val SAMPLE_RATE = 48000
        /** Audio buffer on the TV. Smaller = less delay but more risk of crackles.
         *  If sound plays BEFORE the picture, raise it; if it plays AFTER, lower it. */
        private const val AUDIO_BUFFER_MS = 80
        private const val TYPE_CONFIG = 0
        private const val TYPE_KEY = 1
        private const val TYPE_PONG = 3
        private const val TYPE_AUDIO = 4
    }

    private lateinit var root: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var info: TextView
    private lateinit var stats: TextView
    @Volatile private var server: ServerSocket? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var active = false
    @Volatile private var streaming = false
    @Volatile private var showStats = true

    // latency pieces in ms, -1 = unknown
    @Volatile private var rttMs = -1f
    @Volatile private var encMs = -1f
    @Volatile private var decMs = -1f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        surfaceView = SurfaceView(this)
        root.addView(
            surfaceView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        info = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 28f
            gravity = Gravity.CENTER
        }
        root.addView(
            info,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        val m = (24 * resources.displayMetrics.density).toInt()
        stats = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(160, 0, 0, 0))
            textSize = 18f
            setPadding(m / 2, m / 4, m / 2, m / 4)
            visibility = View.GONE
        }
        root.addView(
            stats,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            ).apply { setMargins(m, m, 0, 0) }
        )
        setContentView(root)
        surfaceView.holder.addCallback(this)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            showStats = !showStats
            stats.visibility = if (showStats && streaming) View.VISIBLE else View.GONE
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---- surface lifecycle ----
    override fun surfaceCreated(holder: SurfaceHolder) {
        active = true
        val surface = holder.surface
        worker = Thread { loop(surface) }.also { it.start() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        active = false
        runCatching { server?.close() }
        worker?.interrupt()
    }

    // ---- network loop ----
    private fun loop(surface: Surface) {
        try {
            val ss = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(PORT))
            }
            server = ss
            while (active) {
                setInfo("Waiting for the phone...\n\nTV address: ${ipAddress()}\nPort: $PORT")
                val s = ss.accept()
                s.tcpNoDelay = true
                setInfo("")
                try {
                    play(s, surface)
                } catch (e: Exception) {
                    Log.w(TAG, "session ended: ${e.message}")
                } finally {
                    streaming = false
                    runOnUiThread { stats.visibility = View.GONE }
                    runCatching { s.close() }
                }
            }
        } catch (e: Exception) {
            if (active) setInfo("Error: ${e.message}")
        } finally {
            runCatching { server?.close() }
        }
    }

    private fun createTrack(): AudioTrack? = try {
        val wanted = SAMPLE_RATE * 4 * AUDIO_BUFFER_MS / 1000
        val min = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setBufferSizeInBytes(maxOf(wanted, min))
            .build()
            .also { it.play() }
    } catch (e: Exception) {
        Log.w(TAG, "audio output unavailable", e)
        null
    }

    private fun play(s: Socket, surface: Surface) {
        val din = DataInputStream(BufferedInputStream(s.getInputStream(), 1 shl 18))
        val dout = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
        rttMs = -1f; encMs = -1f; decMs = -1f
        val queueTimes = ConcurrentHashMap<Long, Long>()

        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080).apply {
            setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            setInteger("vendor.rtc-ext-dec-low-latency.enable", 1) // Qualcomm hint, ignored elsewhere
        }
        val dec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        dec.configure(fmt, surface, null, 0)
        dec.start()
        val track = createTrack()

        val running = AtomicBoolean(true)
        streaming = true

        val outThread = Thread {
            val bi = MediaCodec.BufferInfo()
            while (running.get()) {
                val i = try { dec.dequeueOutputBuffer(bi, 10_000) } catch (e: Exception) { break }
                when {
                    i >= 0 -> {
                        val t0 = queueTimes.remove(bi.presentationTimeUs)
                        if (t0 != null) {
                            val ms = (System.nanoTime() - t0) / 1_000_000f
                            decMs = if (decMs < 0) ms else decMs * 0.9f + ms * 0.1f
                        }
                        runCatching { dec.releaseOutputBuffer(i, true) } // render immediately
                    }
                    i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = dec.outputFormat
                        val w = if (f.containsKey("crop-right"))
                            f.getInteger("crop-right") - f.getInteger("crop-left") + 1
                        else f.getInteger(MediaFormat.KEY_WIDTH)
                        val h = if (f.containsKey("crop-bottom"))
                            f.getInteger("crop-bottom") - f.getInteger("crop-top") + 1
                        else f.getInteger(MediaFormat.KEY_HEIGHT)
                        fit(w, h)
                    }
                }
            }
        }.also { it.start() }

        // Once a second: ping the phone (measures network round trip) and refresh the overlay.
        val pinger = Thread {
            try {
                while (running.get()) {
                    dout.writeLong(System.nanoTime())
                    dout.flush()
                    updateStats()
                    Thread.sleep(1000)
                }
            } catch (e: Exception) {
                // session over
            }
        }.also { it.isDaemon = true; it.start() }

        try {
            while (active) {
                val type = din.readUnsignedByte()
                val pts = din.readLong()
                val size = din.readInt()
                if (size <= 0 || size > 8_000_000) throw IOException("bad frame size $size")
                val data = ByteArray(size)
                din.readFully(data)

                if (type == TYPE_AUDIO) {
                    // non-blocking: if the buffer is full we drop audio instead of stalling the video
                    track?.write(data, 0, size, AudioTrack.WRITE_NON_BLOCKING)
                    continue
                }
                if (type == TYPE_PONG) {
                    val bb = ByteBuffer.wrap(data)
                    val t0 = bb.long
                    val enc10 = bb.int
                    val r = (System.nanoTime() - t0) / 1_000_000f
                    rttMs = if (rttMs < 0) r else rttMs * 0.7f + r * 0.3f
                    encMs = if (enc10 < 0) -1f else enc10 / 10f
                    continue
                }

                var idx = -1
                while (idx < 0 && active) idx = dec.dequeueInputBuffer(20_000)
                if (idx < 0) break
                dec.getInputBuffer(idx)!!.apply { clear(); put(data) }
                val flags = when (type) {
                    TYPE_CONFIG -> MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                    TYPE_KEY -> MediaCodec.BUFFER_FLAG_KEY_FRAME
                    else -> 0
                }
                if (type != TYPE_CONFIG) {
                    if (queueTimes.size > 300) queueTimes.clear()
                    queueTimes[pts] = System.nanoTime()
                }
                dec.queueInputBuffer(idx, 0, size, pts, flags)
            }
        } finally {
            running.set(false)
            pinger.interrupt()
            outThread.join(500)
            runCatching { dec.stop() }
            runCatching { dec.release() }
            runCatching { track?.stop() }
            runCatching { track?.release() }
        }
    }

    private fun statsText(): String {
        val rtt = rttMs
        val enc = encMs
        val dec = decMs
        if (rtt < 0 && dec < 0) return "measuring..."
        val net = if (rtt >= 0) rtt / 2 else 0f
        val total = (if (enc >= 0) enc else 0f) + net + (if (dec >= 0) dec else 0f)
        val encStr = if (enc >= 0) String.format(Locale.US, "%.0f", enc) else "?"
        val decStr = if (dec >= 0) String.format(Locale.US, "%.0f", dec) else "?"
        return String.format(Locale.US, "~%.0f ms\nenc %s · net %.0f · dec %s", total, encStr, net, decStr)
    }

    private fun updateStats() = runOnUiThread {
        if (showStats && streaming) {
            stats.text = statsText()
            stats.visibility = View.VISIBLE
        }
    }

    // Keep the picture's aspect ratio (letterbox) instead of stretching.
    private fun fit(w: Int, h: Int) = runOnUiThread {
        val pw = root.width
        val ph = root.height
        if (pw == 0 || ph == 0 || w == 0 || h == 0) return@runOnUiThread
        val scale = minOf(pw / w.toFloat(), ph / h.toFloat())
        surfaceView.layoutParams = FrameLayout.LayoutParams(
            (w * scale).toInt(), (h * scale).toInt(), Gravity.CENTER
        )
    }

    private fun setInfo(text: String) = runOnUiThread {
        info.text = text
        info.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun ipAddress(): String {
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                for (a in ni.inetAddresses) {
                    if (a is Inet4Address && !a.isLoopbackAddress && a.isSiteLocalAddress) {
                        return a.hostAddress ?: "?"
                    }
                }
            }
        } catch (_: Exception) {}
        return "unknown (see TV Wi-Fi/Network settings)"
    }
}
