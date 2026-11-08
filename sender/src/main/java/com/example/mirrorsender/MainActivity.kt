package com.example.mirrorsender

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.method.DigitsKeyListener
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/** Colour palette (dark navy with an indigo-to-cyan accent). */
private object Pal {
    val BG = Color.parseColor("#0B1220")
    val CARD = Color.parseColor("#131C2E")
    val FIELD = Color.parseColor("#1B2740")
    val LINE = Color.parseColor("#22304A")
    val TEXT = Color.parseColor("#E8EEF9")
    val MUTED = Color.parseColor("#8A98B3")
    val ACCENT = Color.parseColor("#22D3EE")
    val INDIGO = Color.parseColor("#6366F1")
    val DANGER = Color.parseColor("#F87171")
}

private class DarkAdapter(ctx: Context, items: List<String>) :
    ArrayAdapter<String>(ctx, android.R.layout.simple_spinner_item, items) {
    init {
        setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val v = super.getView(position, convertView, parent) as TextView
        v.setTextColor(Pal.TEXT)
        v.textSize = 16f
        return v
    }

    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
        val v = super.getDropDownView(position, convertView, parent) as TextView
        v.setTextColor(Pal.TEXT)
        v.setBackgroundColor(Pal.FIELD)
        v.setPadding(48, 36, 48, 36)
        return v
    }
}

class MainActivity : Activity() {
    private val reqCapture = 1
    private lateinit var ipField: EditText
    private lateinit var mbpsSpinner: Spinner
    private lateinit var sideSpinner: Spinner
    private lateinit var dimSwitch: Switch
    private lateinit var muteSwitch: Switch

    private val mbpsValues = listOf(2, 4, 6, 8, 12, 16, 20)
    private val mbpsLabels = listOf(
        "2 Mbps \u00b7 very light", "4 Mbps \u00b7 light", "6 Mbps", "8 Mbps \u00b7 balanced",
        "12 Mbps \u00b7 sharp", "16 Mbps", "20 Mbps \u00b7 sharpest"
    )
    private val sideValues = listOf(640, 854, 960, 1280, 1600, 1920)
    private val sideLabels = listOf(
        "640 px \u00b7 smallest", "854 px", "960 px", "1280 px \u00b7 HD", "1600 px", "1920 px \u00b7 Full HD"
    )

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun roundBg(color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun nearest(values: List<Int>, target: Int): Int =
        values.indices.minByOrNull { abs(values[it] - target) } ?: 0

    private fun label(t: String) = TextView(this).apply {
        text = t
        textSize = 12f
        letterSpacing = 0.08f
        setTextColor(Pal.MUTED)
        setPadding(0, dp(16), 0, dp(6))
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundBg(Pal.CARD, 22)
        setPadding(dp(18), dp(8), dp(18), dp(14))
    }

    private fun cardParams() = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(18) }

    private fun spinnerField(labels: List<String>, selected: Int): Pair<View, Spinner> {
        val sp = Spinner(this).apply {
            adapter = DarkAdapter(this@MainActivity, labels)
            setSelection(selected)
            background = null
            setPopupBackgroundDrawable(roundBg(Pal.FIELD, 12))
            setPadding(dp(10), 0, dp(36), 0)
        }
        val frame = FrameLayout(this).apply { background = roundBg(Pal.FIELD, 14) }
        frame.addView(sp, FrameLayout.LayoutParams(MATCH, dp(52)))
        val arrow = TextView(this).apply {
            text = "\u25be"
            textSize = 18f
            setTextColor(Pal.MUTED)
        }
        frame.addView(
            arrow,
            FrameLayout.LayoutParams(WRAP, WRAP, Gravity.END or Gravity.CENTER_VERTICAL)
                .apply { marginEnd = dp(16) }
        )
        return frame to sp
    }

    private fun toggleRow(title: String, desc: String, checked: Boolean): Pair<View, Switch> {
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        val sw = Switch(this).apply {
            isChecked = checked
            thumbTintList = ColorStateList(states, intArrayOf(Color.WHITE, Pal.MUTED))
            trackTintList = ColorStateList(states, intArrayOf(Pal.ACCENT, Color.parseColor("#33415C")))
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Pal.TEXT)
            })
            addView(TextView(this@MainActivity).apply {
                text = desc
                textSize = 12.5f
                setTextColor(Pal.MUTED)
            })
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            setOnClickListener { sw.toggle() }
        }
        row.addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(sw)
        return row to sw
    }

    private fun circleButton(text: String, textSp: Float, fill: Drawable, textColor: Int): Button =
        Button(this).apply {
            this.text = text
            textSize = textSp
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.1f
            isAllCaps = false
            setTextColor(textColor)
            stateListAnimator = null
            minWidth = 0
            minHeight = 0
            minimumWidth = 0
            minimumHeight = 0
            setPadding(0, 0, 0, 0)
            gravity = Gravity.CENTER
            val mask = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
            background = RippleDrawable(ColorStateList.valueOf(Color.argb(70, 255, 255, 255)), fill, mask)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // If a previous session ended abnormally, put brightness and volume back.
        if (!CaptureService.running) {
            Brightness.restore(this)
            AudioMute.restore(this)
        }
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(perms.toTypedArray(), 2)

        window.statusBarColor = Pal.BG
        window.navigationBarColor = Pal.BG
        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)

        // ---- header ----
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }
        page.addView(TextView(this).apply {
            text = "Mirror Sender"
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Pal.TEXT)
        })
        page.addView(TextView(this).apply {
            text = "Your phone on the TV, with low delay"
            textSize = 14f
            setTextColor(Pal.MUTED)
        })

        // ---- connection card ----
        ipField = EditText(this).apply {
            hint = "e.g. 192.168.1.50"
            setHintTextColor(Pal.MUTED)
            setTextColor(Pal.TEXT)
            textSize = 16f
            setSingleLine(true)
            keyListener = DigitsKeyListener.getInstance("0123456789.")
            background = roundBg(Pal.FIELD, 14)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setText(prefs.getString("ip", ""))
        }
        val (mbpsFrame, mbpsSp) = spinnerField(
            mbpsLabels, nearest(mbpsValues, prefs.getString("mbps", "8")?.toIntOrNull() ?: 8)
        )
        val (sideFrame, sideSp) = spinnerField(
            sideLabels, nearest(sideValues, prefs.getString("side", "1280")?.toIntOrNull() ?: 1280)
        )
        mbpsSpinner = mbpsSp
        sideSpinner = sideSp

        val c1 = card()
        c1.addView(label("TV ADDRESS"))
        c1.addView(ipField)
        c1.addView(label("QUALITY (BITRATE)"))
        c1.addView(mbpsFrame)
        c1.addView(label("MAX VIDEO SIZE"))
        c1.addView(sideFrame)
        page.addView(c1, cardParams())

        // ---- options card ----
        val (dimRow, dimSw) = toggleRow(
            "Battery saver", "Lowest screen brightness while mirroring", prefs.getBoolean("dim", false)
        )
        val (muteRow, muteSw) = toggleRow(
            "Mute the phone", "Sound plays on the TV only", prefs.getBoolean("mute", true)
        )
        dimSwitch = dimSw
        muteSwitch = muteSw
        val c2 = card()
        c2.addView(dimRow)
        c2.addView(View(this).apply { setBackgroundColor(Pal.LINE) }, LinearLayout.LayoutParams(MATCH, dp(1)))
        c2.addView(muteRow)
        page.addView(c2, cardParams())

        // ---- round buttons ----
        val startFill = GradientDrawable(
            GradientDrawable.Orientation.TL_BR, intArrayOf(Pal.INDIGO, Pal.ACCENT)
        ).apply { shape = GradientDrawable.OVAL }
        val start = circleButton("START", 22f, startFill, Color.WHITE).apply {
            setOnClickListener { onStartClicked() }
        }
        val stopFill = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
            setStroke(dp(2), Pal.DANGER)
        }
        val stop = circleButton("STOP", 14f, stopFill, Pal.DANGER).apply {
            setOnClickListener {
                startService(
                    Intent(this@MainActivity, CaptureService::class.java)
                        .setAction(CaptureService.ACTION_STOP)
                )
            }
        }
        page.addView(
            start,
            LinearLayout.LayoutParams(dp(168), dp(168)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(32)
            }
        )
        page.addView(
            stop,
            LinearLayout.LayoutParams(dp(84), dp(84)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(20)
            }
        )

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Pal.BG)
            isFillViewport = true
            addView(page)
        }
        setContentView(scroll)
    }

    private fun onStartClicked() {
        val ip = ipField.text.toString().trim()
        if (!Regex("""\d{1,3}(\.\d{1,3}){3}""").matches(ip)) {
            Toast.makeText(this, "Enter the TV's IP address", Toast.LENGTH_SHORT).show()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 3)
            Toast.makeText(this, "Allow the audio permission, then tap Start again", Toast.LENGTH_LONG).show()
            return
        }
        if (dimSwitch.isChecked && !Settings.System.canWrite(this)) {
            Toast.makeText(
                this,
                "Turn on \"Allow modify system settings\" for Mirror Sender, come back, then tap Start again",
                Toast.LENGTH_LONG
            ).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))
            )
            return
        }
        getSharedPreferences("cfg", MODE_PRIVATE).edit()
            .putString("ip", ip)
            .putString("mbps", mbpsValues[mbpsSpinner.selectedItemPosition].toString())
            .putString("side", sideValues[sideSpinner.selectedItemPosition].toString())
            .putBoolean("dim", dimSwitch.isChecked)
            .putBoolean("mute", muteSwitch.isChecked)
            .apply()
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), reqCapture)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != reqCapture || resultCode != RESULT_OK || data == null) return
        val i = Intent(this, CaptureService::class.java)
            .putExtra(CaptureService.EXTRA_CODE, resultCode)
            .putExtra(CaptureService.EXTRA_DATA, data)
            .putExtra(CaptureService.EXTRA_IP, ipField.text.toString().trim())
            .putExtra(CaptureService.EXTRA_MBPS, mbpsValues[mbpsSpinner.selectedItemPosition])
            .putExtra(CaptureService.EXTRA_SIDE, sideValues[sideSpinner.selectedItemPosition])
            .putExtra(CaptureService.EXTRA_AUDIO, true)
            .putExtra(CaptureService.EXTRA_DIM, dimSwitch.isChecked)
            .putExtra(CaptureService.EXTRA_MUTE, muteSwitch.isChecked)
        startForegroundService(i)
        Toast.makeText(this, "Mirroring started", Toast.LENGTH_SHORT).show()
    }
}
