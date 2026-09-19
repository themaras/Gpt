package com.example.pokercapture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

class ChatGptAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile private var instance: ChatGptAccessibilityService? = null
        private const val CHATGPT_PACKAGE = "com.openai.chatgpt"
        private const val GEMINI_PACKAGE = "com.google.android.apps.bard"
        private const val CLAUDE_PACKAGE = "com.anthropic.claude"

        fun pasteAndSend(): Boolean {
            val service = instance ?: return false
            service.beginPasteFlow()
            return true
        }

        fun isRunning(): Boolean = instance != null
    }

    private val handler = Handler(Looper.getMainLooper())
    private var active = false
    private var pasteRetries = 0
    private var sendRetries = 0
    private var longPressTried = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!active) return
        val pkg = event?.packageName?.toString().orEmpty()
        val targetPackage = selectedAiPackage()
        if (pkg != targetPackage && pkg != "android") return

        // Contextual Paste menu may appear after the long press.
        if (longPressTried) {
            handler.postDelayed({ clickPasteMenuIfVisible() }, 40)
        }

        // Once an image is attached, ChatGPT exposes/activates Send. Keep checking.
        handler.postDelayed({ trySend() }, 80)
    }

    private fun selectedAiPackage(): String {
        return when (getSharedPreferences("capture", MODE_PRIVATE).getString("ai_model", "CHATGPT")) {
            "GEMINI" -> GEMINI_PACKAGE
            "CLAUDE" -> CLAUDE_PACKAGE
            else -> CHATGPT_PACKAGE
        }
    }

    private fun selectedAiLabel(): String {
        return when (getSharedPreferences("capture", MODE_PRIVATE).getString("ai_model", "CHATGPT")) {
            "GEMINI" -> "Gemini"
            "CLAUDE" -> "Claude"
            else -> "ChatGPT"
        }
    }

    private fun beginPasteFlow() {
        handler.removeCallbacksAndMessages(null)
        active = true
        pasteRetries = 0
        sendRetries = 0
        longPressTried = false

        // The user keeps the wanted ChatGPT conversation already open/active.
        // CAP is a non-focusable overlay, so we never launch ChatGPT or use ACTION_SEND.
        handler.postDelayed({ tryPasteIntoComposer() }, 70)
    }

    private fun tryPasteIntoComposer() {
        if (!active) return
        val root = chatGptRoot()
        if (root == null) {
            retryPaste()
            return
        }

        val composer = findComposer(root)
        if (composer == null) {
            retryPaste()
            return
        }

        composer.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        composer.performAction(AccessibilityNodeInfo.ACTION_CLICK)

        // Best path: Android accessibility paste directly into the already-open composer.
        val pasted = composer.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        if (pasted) {
            // First send attempt is exactly 200 ms later; retries continue while the
            // image attachment finishes rendering.
            handler.postDelayed({ trySend() }, 200)
            return
        }

        // Some Compose/WebView editors do not expose ACTION_PASTE for image clips.
        // Long-press the real composer and choose the system Paste command instead.
        if (!longPressTried) {
            longPressTried = true
            val b = Rect().also { composer.getBoundsInScreen(it) }
            val x = b.exactCenterX()
            val y = b.exactCenterY()
            longPress(x, y)
            handler.postDelayed({ clickPasteMenuIfVisible() }, ViewConfiguration.getLongPressTimeout().toLong() + 120L)
        } else {
            retryPaste()
        }
    }

    private fun clickPasteMenuIfVisible() {
        if (!active) return
        val root = rootInActiveWindow ?: chatGptRoot() ?: return
        val paste = findClickableByLabels(
            root,
            listOf("paste", "επικόλληση", "επικολληση"),
            listOf("paste")
        )
        if (paste != null && paste.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            handler.postDelayed({ trySend() }, 200)
        }
    }

    private fun trySend() {
        if (!active) return
        val root = chatGptRoot()
        if (root == null) {
            retrySend()
            return
        }

        val send = findClickableByLabels(
            root,
            listOf("send", "send message", "submit", "αποστολή", "αποστολη", "στείλε", "στειλε"),
            listOf("send", "submit")
        )

        if (send != null && send.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            finishFlow()
            return
        }

        sendRetries++
        if (sendRetries <= 20) {
            handler.postDelayed({ trySend() }, 100)
        } else {
            // Last resort: tap the lower-right of the actual ChatGPT window, not a
            // hard-coded half of the tablet.
            if (tapSendInChatWindow()) {
                finishFlow()
            } else {
                fail("CAP: δεν βρέθηκε το Send")
            }
        }
    }

    private fun retryPaste() {
        pasteRetries++
        if (pasteRetries <= 12) {
            handler.postDelayed({ tryPasteIntoComposer() }, 100)
        } else {
            fail("CAP: δεν βρέθηκε το ενεργό " + selectedAiLabel() + " composer")
        }
    }

    private fun retrySend() {
        sendRetries++
        if (sendRetries <= 20) handler.postDelayed({ trySend() }, 100)
        else fail("CAP: δεν βρέθηκε το " + selectedAiLabel() + " window")
    }

    private fun chatGptRoot(): AccessibilityNodeInfo? {
        val targetPackage = selectedAiPackage()
        val activeRoot = rootInActiveWindow
        if (activeRoot?.packageName?.toString() == targetPackage) return activeRoot

        for (window in windows) {
            val root = window.root ?: continue
            if (root.packageName?.toString() == targetPackage) return root
        }
        return null
    }

    private fun findComposer(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Prefer the currently focused input.
        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let {
            if (it.isEditable || it.className?.toString()?.contains("EditText", true) == true) return it
        }

        val candidates = ArrayList<Pair<AccessibilityNodeInfo, Rect>>()
        val q = java.util.ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)

        while (!q.isEmpty()) {
            val n = q.removeFirst()
            val className = n.className?.toString().orEmpty()
            val text = (
                n.text?.toString().orEmpty() + " " +
                n.contentDescription?.toString().orEmpty() + " " +
                n.viewIdResourceName.orEmpty()
            ).lowercase()

            val inputLike = n.isEditable ||
                className.contains("EditText", true) ||
                text.contains("message") ||
                text.contains("prompt") ||
                text.contains("composer") ||
                text.contains("μήνυμα") ||
                text.contains("μηνυμα")

            if (inputLike) {
                val b = Rect().also { n.getBoundsInScreen(it) }
                if (b.width() > 40 && b.height() > 20) candidates.add(n to b)
            }

            for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
        }

        // The ChatGPT composer is the lowest input-like node in the chat window.
        return candidates.maxByOrNull { it.second.bottom }?.first
    }

    private fun findClickableByLabels(
        root: AccessibilityNodeInfo,
        labels: List<String>,
        idHints: List<String>
    ): AccessibilityNodeInfo? {
        val q = java.util.ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)

        while (!q.isEmpty()) {
            val n = q.removeFirst()
            val haystack = (
                n.text?.toString().orEmpty() + " " +
                n.contentDescription?.toString().orEmpty() + " " +
                n.viewIdResourceName.orEmpty()
            ).lowercase()

            if (labels.any { haystack.contains(it.lowercase()) } ||
                idHints.any { haystack.contains(it.lowercase()) }) {
                clickableAncestor(n)?.let { return it }
            }

            for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
        }
        return null
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var n = node
        repeat(5) {
            if (n == null) return null
            if (n!!.isClickable) return n
            n = n!!.parent
        }
        return null
    }

    private fun tapSendInChatWindow(): Boolean {
        val root = chatGptRoot() ?: return false
        val b = Rect().also { root.getBoundsInScreen(it) }
        if (b.width() <= 0 || b.height() <= 0) return false

        val density = resources.displayMetrics.density
        val x = b.right - 34f * density
        val y = b.bottom - 44f * density
        return tap(x, y)
    }

    private fun longPress(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val duration = ViewConfiguration.getLongPressTimeout().toLong() + 150L
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 45))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private fun finishFlow() {
        active = false
        handler.removeCallbacksAndMessages(null)
    }

    private fun fail(message: String) {
        finishFlow()
        handler.post {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }
}
