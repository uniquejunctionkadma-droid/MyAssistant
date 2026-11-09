package com.tanya.assistant

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.BatteryManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// The "brain": understands a command and does it. Used by the screen and by the background service.
class Assistant(private val ctx: Context, private val reply: (String) -> Unit) {
    private val notes by lazy { File(ctx.filesDir, "notes.txt") }
    private val countryCode = "91"   // used when a WhatsApp number has 10 digits
    private val notePrefixes = listOf("take a note ", "add note ", "note ", "remember ", "write down ")

    private fun go(i: Intent) = ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    fun handle(raw: String) {
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
                reply(if (r == null) "I cannot divide by zero." else "The answer is " +
                    (if (r % 1.0 == 0.0) r.toLong().toString() else String.format("%.4f", r).trimEnd('0').trimEnd('.')))
            }
            t == "help" -> reply("You can say: open WhatsApp. Remember buy milk. Show notes. Clear notes. What time is it. Battery level. Flashlight on. 245 times 12. Search for weather. WhatsApp 9876543210 hello. In background mode, start with the word Jarvis.")
            wa != null -> {
                var num = wa.groupValues[1].replace(" ", "")
                if (num.length == 10) num = countryCode + num
                val msg = wa.groupValues[2].trim()
                val url = "https://wa.me/$num" + if (msg.isNotEmpty()) "?text=" + Uri.encode(msg) else ""
                reply("Opening WhatsApp. Tap the send arrow to send.")
                go(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
            t.startsWith("open ") -> openApp(t.substring(5))
            t.startsWith("clear notes") || t.startsWith("delete notes") -> { notes.delete(); reply("All notes deleted.") }
            notePrefixes.any { t.startsWith(it) } -> saveNote(t.removePrefix(notePrefixes.first { t.startsWith(it) }))
            t.contains("notes") || t == "note" -> showNotes()
            t.contains("flashlight") || t.contains("torch") -> torch(!t.contains("off"))
            t.contains("battery") -> {
                val b = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                reply("Battery is at " + b.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) + " percent.")
            }
            t.contains("time") || t.contains("date") -> reply(SimpleDateFormat("hh:mm a, EEEE d MMMM", Locale.getDefault()).format(Date()))
            t.startsWith("search ") -> {
                val q = t.removePrefix("search for ").removePrefix("search ")
                reply("Searching for $q")
                go(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))))
            }
            else -> reply("I did not understand that. Say help to hear what I can do.")
        }
    }

    private fun openApp(name: String) {
        val pm = ctx.packageManager
        val list = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val apps = list.map { it.loadLabel(pm).toString().lowercase() to it.activityInfo.packageName }
        val n = name.replace(" ", "")
        val hit = apps.firstOrNull { it.first.replace(" ", "") == n }
            ?: apps.firstOrNull { it.first.replace(" ", "").contains(n) }
            ?: apps.firstOrNull { it.first.length > 2 && n.contains(it.first.replace(" ", "")) }
        val intent = hit?.let { pm.getLaunchIntentForPackage(it.second) }
        if (hit == null || intent == null) { reply("I could not find an app called $name."); return }
        reply("Opening ${hit.first}")
        go(intent)
    }

    private fun saveNote(t: String) {
        if (t.isBlank()) { reply("What should I note?"); return }
        notes.appendText(SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date()) + " - " + t + "\n")
        reply("Note saved.")
    }

    private fun showNotes() {
        val l = if (notes.exists()) notes.readLines() else emptyList()
        if (l.isEmpty()) reply("You have no notes yet.") else reply(l.takeLast(8).joinToString("\n"))
    }

    private fun torch(on: Boolean) {
        try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            if (id == null) { reply("This device has no flashlight."); return }
            cm.setTorchMode(id, on)
            reply(if (on) "Flashlight on." else "Flashlight off.")
        } catch (e: Exception) { reply("I could not use the flashlight.") }
    }
}
