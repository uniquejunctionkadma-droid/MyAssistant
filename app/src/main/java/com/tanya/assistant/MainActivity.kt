package com.tanya.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var log: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var jarvisBtn: Button
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private val brain by lazy { Assistant(this) { say(it) } }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        tts = TextToSpeech(this) { if (it == TextToSpeech.SUCCESS) tts?.language = Locale.getDefault() }
        val d = resources.displayMetrics.density
        val m = LinearLayout.LayoutParams.MATCH_PARENT
        val pad = (16 * d).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        log = TextView(this).apply { textSize = 18f }
        scroll = ScrollView(this).apply { addView(log) }
        root.addView(scroll, LinearLayout.LayoutParams(m, 0, 1f))
        jarvisBtn = Button(this).apply { textSize = 20f; setOnClickListener { toggleJarvis() } }
        root.addView(jarvisBtn, LinearLayout.LayoutParams(m, (70 * d).toInt()))
        root.addView(Button(this).apply {
            text = "Tap and speak (one command)"; textSize = 20f
            setOnClickListener { startListening() }
        }, LinearLayout.LayoutParams(m, (70 * d).toInt()))
        val row = LinearLayout(this)
        input = EditText(this).apply {
            hint = "Or type a command"; setSingleLine(); imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, _, _ -> submit(); true }
        }
        row.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Button(this).apply { text = "Send"; setOnClickListener { submit() } })
        root.addView(row)
        setContentView(root)
        say("Hello! Tap Start Jarvis to keep me listening in the background, or type a command. Say help to see what I can do.")
    }

    override fun onResume() { super.onResume(); updateJarvisButton() }

    private fun updateJarvisButton() {
        jarvisBtn.text = if (ListenService.running) "Stop Jarvis (listening)" else "Start Jarvis (background)"
    }

    private fun show(t: String) { log.append(t + "\n\n"); scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) } }
    private fun say(t: String) { show("Assistant: $t"); tts?.speak(t, TextToSpeech.QUEUE_FLUSH, null, "a") }

    private fun submit() {
        val t = input.text.toString().trim()
        input.setText("")
        if (t.isNotEmpty()) { show("You: $t"); brain.handle(t) }
    }

    private fun toggleJarvis() {
        if (ListenService.running) {
            stopService(Intent(this, ListenService::class.java))
            jarvisBtn.text = "Start Jarvis (background)"
            say("Jarvis stopped."); return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            val need = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS)
            requestPermissions(need.toTypedArray(), 2); return
        }
        if (!Settings.canDrawOverlays(this)) {
            say("Please switch on Display over other apps for My Assistant. This lets me open apps while I run in the background. Then tap Start Jarvis again.")
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        startForegroundService(Intent(this, ListenService::class.java))
        jarvisBtn.text = "Stop Jarvis (listening)"
        say("Jarvis is online. Say Jarvis, then your command. You can leave this screen now.")
    }

    private fun startListening() {
        if (ListenService.running) { say("Jarvis is already listening. Just say Jarvis and your command."); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1); return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { say("Speech recognition is not available on this device."); return }
        recognizer?.destroy()
        val r = if (Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this))
            SpeechRecognizer.createOnDeviceSpeechRecognizer(this) else SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) { input.hint = "Listening... speak now" }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() { input.hint = "Or type a command" }
            override fun onEvent(t: Int, p: Bundle?) {}
            override fun onError(e: Int) {
                input.hint = "Or type a command"
                val offlineMissing = e == SpeechRecognizer.ERROR_NETWORK || e == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ||
                    e == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || e == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
                say(if (offlineMissing) "The offline English speech pack is missing. In Android Settings, search for offline speech recognition and download English."
                    else "I did not catch that. Tap and try again.")
            }
            override fun onPartialResults(p: Bundle?) {
                p?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { input.setText(it) }
            }
            override fun onResults(res: Bundle?) {
                input.setText("")
                val t = res?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (t.isNullOrBlank()) say("I did not catch that.") else { show("You: $t"); brain.handle(t) }
            }
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true))
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            say("I need the microphone permission to hear you. You can still type commands."); return
        }
        if (code == 2) toggleJarvis() else startListening()
    }

    override fun onDestroy() {
        recognizer?.destroy(); tts?.shutdown()
        super.onDestroy()
    }
}
