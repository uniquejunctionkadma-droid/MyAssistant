package com.tanya.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

// Lets Jarvis tap, scroll and type on the screen. Only works after you switch it on in Accessibility settings.
class JarvisAccess : AccessibilityService() {
    companion object { @Volatile var instance: JarvisAccess? = null }

    private val h = Handler(Looper.getMainLooper())

    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onUnbind(i: Intent?): Boolean { instance = null; return super.onUnbind(i) }
    override fun onDestroy() { instance = null; super.onDestroy() }

    private fun collect(n: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>) {
        if (n == null) return
        out.add(n)
        for (i in 0 until n.childCount) collect(n.getChild(i), out)
    }

    fun global(action: Int): Boolean = performGlobalAction(action)

    fun tapText(q: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val key = q.lowercase().trim()
        val all = ArrayList<AccessibilityNodeInfo>()
        collect(root, all)
        fun txt(n: AccessibilityNodeInfo) = (n.text?.toString() ?: "").lowercase().trim()
        fun dsc(n: AccessibilityNodeInfo) = (n.contentDescription?.toString() ?: "").lowercase().trim()
        val hit = all.firstOrNull { txt(it) == key || dsc(it) == key }
            ?: all.firstOrNull { (txt(it) + " " + dsc(it)).contains(key) }
            ?: return false
        var n: AccessibilityNodeInfo? = hit
        while (n != null) {
            if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            n = n.parent
        }
        val r = Rect()
        hit.getBoundsInScreen(r)
        return tapAt(r.exactCenterX(), r.exactCenterY())
    }

    // Keeps trying for a few seconds (apps need a moment to open)
    fun tapWhenReady(text: String, tries: Int, onResult: (Boolean) -> Unit) {
        if (tapText(text)) { onResult(true); return }
        if (tries <= 0) { onResult(false); return }
        h.postDelayed({ tapWhenReady(text, tries - 1, onResult) }, 1000)
    }

    fun typeText(t: String): Boolean {
        val root = rootInActiveWindow ?: return false
        var f: AccessibilityNodeInfo? = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (f == null) {
            val all = ArrayList<AccessibilityNodeInfo>()
            collect(root, all)
            f = all.firstOrNull { it.isEditable }
        }
        if (f == null) return false
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, t)
        return f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float): Boolean {
        val p = Path()
        p.moveTo(x1, y1)
        p.lineTo(x2, y2)
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 300)).build()
        return dispatchGesture(g, null, null)
    }

    fun tapAt(x: Float, y: Float): Boolean {
        val p = Path()
        p.moveTo(x, y)
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 60)).build()
        return dispatchGesture(g, null, null)
    }

    fun tapCenter(): Boolean {
        val m = resources.displayMetrics
        return tapAt(m.widthPixels / 2f, m.heightPixels / 2f)
    }

    // "up" = finger moves up (next reel / scroll down the page)
    fun swipeDir(dir: String): Boolean {
        val m = resources.displayMetrics
        val w = m.widthPixels.toFloat()
        val hh = m.heightPixels.toFloat()
        return when (dir) {
            "up" -> swipe(w / 2, hh * 0.75f, w / 2, hh * 0.25f)
            "down" -> swipe(w / 2, hh * 0.25f, w / 2, hh * 0.75f)
            "left" -> swipe(w * 0.8f, hh / 2, w * 0.2f, hh / 2)
            else -> swipe(w * 0.2f, hh / 2, w * 0.8f, hh / 2)
        }
    }
}
