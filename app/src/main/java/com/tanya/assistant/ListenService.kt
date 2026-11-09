package com.tanya.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

// Jarvis: keeps listening in the background. Say "Jarvis, <command>" or just "Jarvis" and then the command.
class ListenService : Service() {
    companion object { @Volatile var running = false }

    private val h = Handler(Looper.getMainLooper())
    private var rec: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var brain: Assistant
    private var awakeUntil = 0L
    private var speaking = false
    private var fatal = false
    private val wakeWords = listOf("jarvis", "jarvice", "jarves", "jarvas", "javis", "garvis", "charvis")

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        if (running) return START_STICKY
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("jarvis", "Jarvis", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "jarvis")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Jarvis is listening")
            .setContentText("Say Jarvis, then your command. Tap to open.")
            .setContentIntent(pi).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(1, n)
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jarvis:listen").apply { acquire() }
        brain = Assistant(this) { speak(it) }
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { resume() }
                    override fun onError(id: String?) { resume() }
                })
                ttsReady = true
            }
            running = true
            h.post { listen() }
        }
        return START_STICKY
    }

    private fun speak(text: String) {
        if (!ttsReady) return
        speaking = true
        rec?.cancel()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "j")
    }

    private fun resume() {
        speaking = false
        if (!fatal) h.post { listen() }
    }

    private fun listen() {
        if (!running || speaking) return
        rec?.destroy()
        val r = if (Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this))
            SpeechRecognizer.createOnDeviceSpeechRecognizer(this) else SpeechRecognizer.createSpeechRecognizer(this)
        rec = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(t: Int, p: Bundle?) {}
            override fun onPartialResults(p: Bundle?) {}
            override fun onError(e: Int) {
                when (e) {
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> stopSelf()
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                        fatal = true
                        speak("The offline English speech pack is missing. Going to sleep.")
                        h.postDelayed({ stopSelf() }, 5000)
                    }
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> h.postDelayed({ listen() }, 3000)
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> h.postDelayed({ listen() }, 1500)
                    else -> h.postDelayed({ listen() }, 300)
                }
            }
            override fun onResults(res: Bundle?) {
                res?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { process(it) }
                if (!speaking) h.postDelayed({ listen() }, 300)
            }
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true))
    }

    private fun process(raw: String) {
        val t = raw.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9 .+*/-]"), " ").replace(Regex(" +"), " ").trim()
        val w = wakeWords.firstOrNull { t.contains(it) }
        val cmd = when {
            w != null -> t.substringAfter(w).trim()
            System.currentTimeMillis() < awakeUntil -> t
            else -> return
        }
        awakeUntil = 0L
        if (cmd.isEmpty()) { awakeUntil = System.currentTimeMillis() + 10_000; speak("At your service."); return }
        if (cmd.startsWith("stop listening") || cmd.startsWith("go to sleep") || cmd == "goodbye") {
            fatal = true
            speak("Going to sleep. Goodbye.")
            h.postDelayed({ stopSelf() }, 3000)
            return
        }
        brain.handle(cmd)
    }

    override fun onDestroy() {
        running = false
        h.removeCallbacksAndMessages(null)
        rec?.destroy(); tts?.shutdown()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}
