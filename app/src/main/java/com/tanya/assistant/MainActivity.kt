package com.tanya.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var log: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private val notes by lazy { File(filesDir, "notes.txt") }
    private val countryCode = "91"   // used when a WhatsApp number has 10 digits
    private val notePrefixes = listOf("take a note ", "add note ", "note ", "remember ", "write down ")

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
        root.addView(Button(this).apply {
            text = "Tap and speak"; textSize = 22f
            setOnClickListener { startListening() }
        }, LinearLayout.LayoutParams(m, (80 * d).toInt()))
        val row = LinearLayout(this)
        input = EditText(this).apply {
            hint = "Or type a command"; setSingleLine(); imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, _, _ -> submit(); true }
        }
        row.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Button(this).apply { text = "Send"; setOnClickListener { submit() } })
        root.addView(row)
        setContentView(root)
        say("Hello! Tap the button and speak, or type a command. Say help to see what I can do.")
    }

    private fun show(t: String) { log.append(t + "\n\n"); scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) } }
    private fun say(t: String) { show("Assistant: $t"); tts?.speak(t, TextToSpeech.QUEUE_FLUSH, null, "a") }

    private fun submit() {
        val t = input.text.toString().trim()
        input.setText("")
        if (t.isNotEmpty()) handle(t)
    }

    private fun startListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1); return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            say("Speech recognition is not available on this device."); return
        }
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
                if (t.isNullOrBlank()) say("I did not catch that.") else handle(t)
            }
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true))
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED) startListening()
        else say("I need the microphone permission to hear you. You can still type commands.")
    }

    private fun handle(raw: String) {
        show("You: $raw")
        val t = raw.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9 .+*/-]"), " ").replace(Regex(" +"), " ").trim()
        val math = Regex("^(?:what is |whats |calculate )?(\\d+(?:\\.\\d+)?) (plus|minus|times|x|multiplied by|divided by|\\+|-|\\*|/) (\\d+(?:\\.\\d+)?)$").find(t)
        val wa = Regex("^(?:send )?whatsapp (?:to )?((?:\\d ?){10,13})(.*)$").find(t)
        when {
            math != null -> {
                val g = math.groupValues
                val a = g[1].toDouble(); val c = g[3].toDouble()
                val r: Double? = when (g[2]) {
                    "plus", "+" -> a + c
                    "minus", "-" -> a - c
                    "divided by", "/" -> if (c == 0.0) null else a / c
                    else -> a * c
                }
                say(if (r == null) "I cannot divide by zero." else "The answer is " +
                    (if (r % 1.0 == 0.0) r.toLong().toString() else String.format("%.4f", r).trimEnd('0').trimEnd('.')))
            }
            t == "help" -> say("You can say: open WhatsApp. Note buy milk. Show notes. Clear notes. What time is it. Flashlight on. 245 times 12. Search for weather. WhatsApp 9876543210 hello.")
            wa != null -> {
                var num = wa.groupValues[1].replace(" ", "")
                if (num.length == 10) num = countryCode + num
                val msg = wa.groupValues[2].trim()
                val url = "https://wa.me/$num" + if (msg.isNotEmpty()) "?text=" + Uri.encode(msg) else ""
                say("Opening WhatsApp. Tap the send arrow to send.")
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
            t.startsWith("open ") -> openApp(t.substring(5))
            t.startsWith("clear notes") || t.startsWith("delete notes") -> { notes.delete(); say("All notes deleted.") }
            notePrefixes.any { t.startsWith(it) } -> saveNote(t.removePrefix(notePrefixes.first { t.startsWith(it) }))
            t.contains("notes") || t == "note" -> showNotes()
            t.contains("flashlight") || t.contains("torch") -> torch(!t.contains("off"))
            t.contains("time") || t.contains("date") -> say(SimpleDateFormat("hh:mm a, EEEE d MMMM", Locale.getDefault()).format(Date()))
            t.startsWith("search ") -> {
                val q = t.removePrefix("search for ").removePrefix("search ")
                say("Searching for $q")
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))))
            }
            else -> say("I did not understand that. Say help to hear what I can do.")
        }
    }

    private fun openApp(name: String) {
        val pm = packageManager
        val list = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val apps = list.map { it.loadLabel(pm).toString().lowercase() to it.activityInfo.packageName }
        val n = name.replace(" ", "")
        val hit = apps.firstOrNull { it.first.replace(" ", "") == n }
            ?: apps.firstOrNull { it.first.replace(" ", "").contains(n) }
            ?: apps.firstOrNull { it.first.length > 2 && n.contains(it.first.replace(" ", "")) }
        val intent = hit?.let { pm.getLaunchIntentForPackage(it.second) }
        if (hit == null || intent == null) { say("I could not find an app called $name."); return }
        say("Opening ${hit.first}")
        startActivity(intent)
    }

    private fun saveNote(t: String) {
        if (t.isBlank()) { say("What should I note?"); return }
        notes.appendText(SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date()) + " - " + t + "\n")
        say("Note saved.")
    }

    private fun showNotes() {
        val l = if (notes.exists()) notes.readLines() else emptyList()
        if (l.isEmpty()) say("You have no notes yet.") else say(l.takeLast(8).joinToString("\n"))
    }

    private fun torch(on: Boolean) {
        try {
            val cm = getSystemService(CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            if (id == null) { say("This device has no flashlight."); return }
            cm.setTorchMode(id, on)
            say(if (on) "Flashlight on." else "Flashlight off.")
        } catch (e: Exception) { say("I could not use the flashlight.") }
    }

    override fun onDestroy() {
        recognizer?.destroy(); tts?.shutdown()
        super.onDestroy()
    }
}
