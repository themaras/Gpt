package com.example.pokercapture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class ChatGptAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile private var instance: ChatGptAccessibilityService? = null
        fun attachAndSend() { instance?.beginAttachFlow() }
        fun isRunning(): Boolean = instance != null
    }

    private val handler = Handler(Looper.getMainLooper())
    private var active = false

    override fun onServiceConnected() { instance = this }
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }
    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!active) return
        val pkg = event?.packageName?.toString() ?: return
        if (pkg == "com.openai.chatgpt") handler.postDelayed({ clickAttachInChat() }, 40)
        else if (pkg.contains("documentsui") || pkg.contains("photopicker") || pkg.contains("providers.media")) {
            handler.postDelayed({ chooseNewestImage() }, 60)
        }
    }

    private fun beginAttachFlow() {
        active = true
        handler.postDelayed({ clickAttachInChat() }, 80)
        handler.postDelayed({ clickAttachInChat() }, 180)
    }

    private fun clickAttachInChat() {
        if (!active) return
        val root = rootInActiveWindow ?: return
        val labels = listOf("Add photos & files", "Add photos and files", "Attach", "Add", "Upload")
        val node = findClickable(root, labels)
        if (node != null) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            handler.postDelayed({ clickPhotoMenu() }, 70)
        }
    }

    private fun clickPhotoMenu() {
        val root = rootInActiveWindow ?: return
        val node = findClickable(root, listOf("Photos", "Photo", "Gallery", "Images", "Upload from device"))
        if (node != null) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            handler.postDelayed({ chooseNewestImage() }, 100)
        }
    }

    private fun chooseNewestImage() {
        if (!active) return
        val root = rootInActiveWindow ?: return
        // Android's picker exposes image cells as clickable nodes. Pick the first visible
        // image cell; MediaStore is sorted newest-first, and CAP has just saved our frame.
        val candidates = ArrayList<AccessibilityNodeInfo>()
        collectClickableImages(root, candidates)
        if (candidates.isNotEmpty()) {
            candidates[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)
            handler.postDelayed({ clickPickerDoneOrSend() }, 80)
        }
    }

    private fun clickPickerDoneOrSend() {
        val root = rootInActiveWindow
        val done = root?.let { findClickable(it, listOf("Add", "Done", "Select", "Open")) }
        if (done != null) {
            done.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            handler.postDelayed({ sendInChat() }, 200)
        } else {
            handler.postDelayed({ sendInChat() }, 200)
        }
    }

    private fun sendInChat() {
        val root = rootInActiveWindow ?: return
        val send = findClickable(root, listOf("Send", "Send message"))
        if (send != null) {
            send.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            active = false
        }
    }

    private fun findClickable(root: AccessibilityNodeInfo, labels: List<String>): AccessibilityNodeInfo? {
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        while (q.isNotEmpty()) {
            val n = q.removeFirst()
            val text = (n.text?.toString() ?: "") + " " + (n.contentDescription?.toString() ?: "")
            if (n.isClickable && labels.any { text.contains(it, ignoreCase = true) }) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
        }
        return null
    }

    private fun collectClickableImages(root: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>) {
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        while (q.isNotEmpty()) {
            val n = q.removeFirst()
            val d = (n.contentDescription?.toString() ?: "").lowercase()
            if (n.isClickable && (d.contains("image") || d.contains("photo") || d.contains("pokercapture"))) out.add(n)
            for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
        }
    }
}
