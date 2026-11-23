package com.eds.auto

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private data class MusicApp(val name: String, val pkg: String)

    private val musicApps = listOf(
        MusicApp("Spotify", "com.spotify.music"),
        MusicApp("YouTube Music", "com.google.android.apps.youtube.music"),
        MusicApp("Smart AudioBook Player", "ak.alizandro.smartaudiobookplayer"),
        MusicApp("Audible", "com.audible.application")
    )
    private val wazePkg = "com.waze"

    private lateinit var prefs: SharedPreferences
    private lateinit var status: TextView
    private lateinit var limitEdit: EditText
    private lateinit var audioCheck: CheckBox
    private lateinit var landscapeCheck: CheckBox
    private lateinit var forceCheck: CheckBox

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("eds_auto", Context.MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(28))
            setBackgroundColor(Color.parseColor("#121418"))
        }

        root.addView(text("Ed's Auto", 28f, bold = true))
        root.addView(text("Waze + music side by side, with a looping dash cam.", 14f, "#9AA0A6"))

        // ---- Split screen ----
        root.addView(header("Split screen"))
        root.addView(text("Music app for the other half:", 14f))
        val group = RadioGroup(this)
        musicApps.forEachIndexed { i, app ->
            group.addView(RadioButton(this).apply {
                id = i
                text = app.name
                setTextColor(Color.WHITE)
            })
        }
        group.check(prefs.getInt("music", 0))
        group.setOnCheckedChangeListener { _, checked -> prefs.edit().putInt("music", checked).apply() }
        root.addView(group)

        root.addView(button("Start Waze + music") { startSplit(group.checkedRadioButtonId) })
        root.addView(button("Turn on split-screen helper (one-time)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })

        // ---- Dash cam ----
        root.addView(header("Dash cam"))
        root.addView(text("Storage limit for recordings (GB). Oldest clips are deleted when it is reached.", 14f))
        limitEdit = EditText(this).apply {
            setText(prefs.getString("limit_gb", "16"))
            setTextColor(Color.WHITE)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        root.addView(limitEdit)

        audioCheck = check("Record sound (back camera clip)", prefs.getBoolean("audio", false))
        landscapeCheck = check("Phone is mounted sideways (landscape)", prefs.getBoolean("landscape", true))
        forceCheck = check("Try front + back even if phone doesn't say it supports it", prefs.getBoolean("force", false))
        root.addView(audioCheck)
        root.addView(landscapeCheck)
        root.addView(forceCheck)

        root.addView(button("Start recording") { startRecording() })
        root.addView(button("Stop recording") { stopRecording() })

        status = text("", 14f, "#8AB4F8")
        root.addView(status)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        status.text = if (RecorderService.running) {
            "Recording. Clips are in:\n${RecorderService.recordingDir(this).absolutePath}"
        } else {
            "Not recording. Clips are saved in:\n${RecorderService.recordingDir(this).absolutePath}"
        }
    }

    // ---------------- split screen ----------------

    private fun startSplit(musicIndex: Int) {
        val waze = packageManager.getLaunchIntentForPackage(wazePkg)
        if (waze == null) {
            toast("Waze isn't installed")
            return
        }
        val music = packageManager.getLaunchIntentForPackage(musicApps[musicIndex].pkg)
        if (music == null) {
            toast("${musicApps[musicIndex].name} isn't installed")
            return
        }
        val svc = SplitService.instance
        if (svc == null) {
            toast("Turn on the split-screen helper for Ed's Auto first")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        svc.launchSplit(waze, music)
    }

    // ---------------- recording ----------------

    private fun startRecording() {
        val perms = mutableListOf(Manifest.permission.CAMERA)
        if (audioCheck.isChecked) perms += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 1)
        } else {
            launchRecorder()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
            (!audioCheck.isChecked || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        ) {
            launchRecorder()
        } else {
            toast("Camera (and microphone, if sound is on) permission is needed")
        }
    }

    private fun launchRecorder() {
        val gb = limitEdit.text.toString().toDoubleOrNull()?.takeIf { it > 0.5 } ?: 16.0
        prefs.edit()
            .putString("limit_gb", limitEdit.text.toString())
            .putBoolean("audio", audioCheck.isChecked)
            .putBoolean("landscape", landscapeCheck.isChecked)
            .putBoolean("force", forceCheck.isChecked)
            .apply()

        val i = Intent(this, RecorderService::class.java).apply {
            action = RecorderService.ACTION_START
            putExtra(RecorderService.EXTRA_LIMIT_MB, (gb * 1024).toLong())
            putExtra(RecorderService.EXTRA_AUDIO, audioCheck.isChecked)
            putExtra(RecorderService.EXTRA_LANDSCAPE, landscapeCheck.isChecked)
            putExtra(RecorderService.EXTRA_FORCE_BOTH, forceCheck.isChecked)
        }
        startForegroundService(i)
        status.postDelayed({ refreshStatus() }, 1500)
    }

    private fun stopRecording() {
        startService(Intent(this, RecorderService::class.java).apply { action = RecorderService.ACTION_STOP })
        status.postDelayed({ refreshStatus() }, 800)
    }

    // ---------------- little UI helpers ----------------

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun text(s: String, size: Float, color: String = "#FFFFFF", bold: Boolean = false) =
        TextView(this).apply {
            text = s
            textSize = size
            setTextColor(Color.parseColor(color))
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(6), 0, dp(6))
        }

    private fun header(s: String) = text(s, 20f, "#8AB4F8", bold = true).apply { setPadding(0, dp(26), 0, dp(6)) }

    private fun check(label: String, checked: Boolean) = CheckBox(this).apply {
        text = label
        isChecked = checked
        setTextColor(Color.WHITE)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(8) }
        setOnClickListener { onClick() }
    }
}
