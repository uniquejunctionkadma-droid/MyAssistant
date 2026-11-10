package com.tanya.assistant

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// The "brain": understands a command and does it. Used by the screen and by the background service.
class Assistant(private val ctx: Context, private val reply: (String) -> Unit) {
    private val notes by lazy { File(ctx.filesDir, "notes.txt") }
    private val h = Handler(Looper.getMainLooper())
    private val countryCode = "91"   // added when a number has only 10 digits
    private val notePrefixes = listOf("take a note ", "add note ", "note ", "remember ", "write down ")
    private val msgRe = Regex("^(?:send )?(?:a )?(?:whatsapp message|whatsapp|message|text) (?:to )?(.+?) (?:saying|that says|that) (.+)$", RegexOption.IGNORE_CASE)
    private val typeRe = Regex("^type (.+)$", RegexOption.IGNORE_CASE)
    private val globals = mapOf(
        "go back" to AccessibilityService.GLOBAL_ACTION_BACK, "back" to AccessibilityService.GLOBAL_ACTION_BACK,
        "go home" to AccessibilityService.GLOBAL_ACTION_HOME, "home" to AccessibilityService.GLOBAL_ACTION_HOME,
        "home screen" to AccessibilityService.GLOBAL_ACTION_HOME,
        "recent apps" to AccessibilityService.GLOBAL_ACTION_RECENTS, "recents" to AccessibilityService.GLOBAL_ACTION_RECENTS,
        "notifications" to AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS,
        "open notifications" to AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS,
        "quick settings" to AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS,
        "lock screen" to AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN, "lock" to AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN,
        "screenshot" to AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT,
        "take screenshot" to AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT
    )

    private fun go(i: Intent) = ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    // Several steps in one sentence: "open Instagram then next reel then next reel"
    fun handle(raw: String) {
        val r = raw.trim()
        val steps = if (msgRe.containsMatchIn(r)) listOf(r)
            else r.split(Regex("(?i)\\s+(?:and then|then)\\s+")).filter { it.isNotBlank() }
        steps.forEachIndexed { i, s -> if (i == 0) one(s) else h.postDelayed({ one(s) }, 3500L * i) }
    }

    private fun one(r: String) {
        val t = r.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9 .+*/-]"), " ").replace(Regex(" +"), " ").trim()
        val math = Regex("^(?:what is |whats |calculate )?(\\d+(?:\\.\\d+)?) (plus|minus|times|x|multiplied by|divided by|\\+|-|\\*|/) (\\d+(?:\\.\\d+)?)$").find(t)
        val m = msgRe.find(r)
        val wa = Regex("^(?:send )?whatsapp (?:to )?((?:\\d ?){10,13})(.*)$").find(t)
        val ty = typeRe.find(r)
        val a = JarvisAccess.instance
        fun screen(block: (JarvisAccess) -> Boolean) {
            if (a == null) reply("Screen control is off. Open the Jarvis app and tap Enable screen control.")
            else reply(if (block(a)) "Done." else "I could not do that.")
        }
        when {
            math != null -> {
                val g = math.groupValues
                val x = g[1].toDouble(); val c = g[3].toDouble()
                val res: Double? = when (g[2]) {
                    "plus", "+" -> x + c
                    "minus", "-" -> x - c
                    "divided by", "/" -> if (c == 0.0) null else x / c
                    else -> x * c
                }
                reply(if (res == null) "I cannot divide by zero." else "The answer is " +
                    (if (res % 1.0 == 0.0) res.toLong().toString() else String.format("%.4f", res).trimEnd('0').trimEnd('.')))
            }
            t == "help" -> reply("Try: WhatsApp Mom saying I am coming. Open Instagram then next reel. Tap search. Type hello. Scroll down. Go back. Go home. Remember buy milk. Show notes. What time is it. Flashlight on. 245 times 12. Search for weather.")
            m != null -> sendWhatsApp(m.groupValues[1].trim(), m.groupValues[2].trim())
            wa != null -> {
                var num = wa.groupValues[1].replace(" ", "")
                if (num.length == 10) num = countryCode + num
                sendWhatsAppTo(num, wa.groupValues[2].trim(), num)
            }
            globals.containsKey(t) -> screen { it.global(globals.getValue(t)) }
            t.startsWith("tap ") || t.startsWith("click ") || t.startsWith("press ") -> {
                val target = t.substringAfter(" ")
                screen { if (target == "center" || target == "screen" || target == "middle") it.tapCenter() else it.tapText(target) }
            }
            ty != null -> screen { it.typeText(ty.groupValues[1]) }
            t in listOf("next reel", "next video", "next short", "swipe up", "scroll down") -> screen { it.swipeDir("up") }
            t in listOf("previous reel", "previous video", "swipe down", "scroll up") -> screen { it.swipeDir("down") }
            t == "swipe left" -> screen { it.swipeDir("left") }
            t == "swipe right" -> screen { it.swipeDir("right") }
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

    private fun findNumber(name: String): String? {
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val c = ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?", arrayOf("%" + name.trim() + "%"), null
        ) ?: return null
        var best: String? = null
        var bestLen = Int.MAX_VALUE
        c.use {
            while (it.moveToNext()) {
                val n = it.getString(0)
                if (n != null && n.length < bestLen) { bestLen = n.length; best = it.getString(1) }
            }
        }
        return best
    }

    private fun sendWhatsApp(name: String, text: String) {
        val num = findNumber(name)
        if (num == null) {
            reply("I could not find $name in your contacts. Check that Jarvis has the Contacts permission and the name is saved on this device.")
            return
        }
        var digits = num.filter { it.isDigit() }
        if (digits.length == 10) digits = countryCode + digits
        sendWhatsAppTo(digits, text, name)
    }

    private fun sendWhatsAppTo(number: String, text: String, who: String) {
        val acc = JarvisAccess.instance
        reply(if (text.isEmpty()) "Opening WhatsApp chat with $who." else "Sending to $who: $text")
        val url = "https://wa.me/$number" + if (text.isNotEmpty()) "?text=" + Uri.encode(text) else ""
        try { go(Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage("com.whatsapp")) }
        catch (e: Exception) { go(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        if (text.isEmpty()) return
        if (acc == null) {
            h.postDelayed({ reply("Screen control is off, so please tap the send arrow yourself.") }, 3000)
            return
        }
        h.postDelayed({
            acc.tapWhenReady("send", 6) { ok ->
                reply(if (ok) "Message sent to $who." else "I could not find the send button. Please tap it yourself.")
            }
        }, 3500)
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
