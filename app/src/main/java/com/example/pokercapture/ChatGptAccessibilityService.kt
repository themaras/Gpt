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

    private enum class AiTarget {
        CHATGPT, GEMINI, CLAUDE
    }

    private val handler = Handler(Looper.getMainLooper())
    private var active = false
    private var pasteRetries = 0
    private var sendRetries = 0
    private var longPressTried = false
    private var lastComposerBounds: Rect? = null

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
        if (pkg != targetPackage && pkg != "android" && pkg != "com.android.systemui") return

        if (longPressTried) {
            handler.postDelayed({ clickPasteMenuIfVisible() }, 40)
        }

        handler.postDelayed({ trySend() }, 80)
    }

    private fun selectedAiTarget(): AiTarget {
        return when (getSharedPreferences("capture", MODE_PRIVATE).getString("ai_model", "CHATGPT")) {
            "GEMINI" -> AiTarget.GEMINI
            "CLAUDE" -> AiTarget.CLAUDE
            else -> AiTarget.CHATGPT
        }
    }

    private fun selectedAiPackage(): String {
        return when (selectedAiTarget()) {
            AiTarget.GEMINI -> GEMINI_PACKAGE
            AiTarget.CLAUDE -> CLAUDE_PACKAGE
            AiTarget.CHATGPT -> CHATGPT_PACKAGE
        }
    }

    private fun selectedAiLabel(): String {
        return when (selectedAiTarget()) {
            AiTarget.GEMINI -> "Gemini"
            AiTarget.CLAUDE -> "Claude"
            AiTarget.CHATGPT -> "ChatGPT"
        }
    }

    private fun beginPasteFlow() {
        handler.removeCallbacksAndMessages(null)
        active = true
        pasteRetries = 0
        sendRetries = 0
        longPressTried = false
        lastComposerBounds = null

        // CAP stays on the current conversation. Never launch the AI app and never
        // use ACTION_SEND here. The screenshot URI is already on the clipboard.
        handler.postDelayed({ tryPasteIntoComposer() }, 70)
    }

    private fun tryPasteIntoComposer() {
        if (!active) return
        val root = targetRoot()
        if (root == null) {
            retryPaste()
            return
        }

        val composer = findComposer(root)
        if (composer == null) {
            // Gemini and Claude sometimes expose their Compose input as a generic
            // container instead of an EditText. In that case long-press the expected
            // composer area inside the app window and use Android's Paste menu.
            if (selectedAiTarget() != AiTarget.CHATGPT && !longPressTried && tryGeometricPaste(root)) {
                return
            }
            retryPaste()
            return
        }

        lastComposerBounds = Rect().also { composer.getBoundsInScreen(it) }

        composer.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        composer.performAction(AccessibilityNodeInfo.ACTION_CLICK)

        // First try Android's direct accessibility paste. ChatGPT accepts this on
        // most builds, while Gemini/Claude may fall back to the contextual Paste menu.
        val pasted = composer.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        if (pasted) {
            handler.postDelayed({ trySend() }, 220)
            return
        }

        if (!longPressTried) {
            longPressTried = true
            val b = lastComposerBounds ?: return retryPaste()
            val x = (b.left + b.width() * 0.45f)
            val y = b.exactCenterY()
            if (!longPress(x, y)) {
                retryPaste()
                return
            }
            handler.postDelayed(
                { clickPasteMenuIfVisible() },
                ViewConfiguration.getLongPressTimeout().toLong() + 140L
            )
        } else {
            retryPaste()
        }
    }

    private fun tryGeometricPaste(root: AccessibilityNodeInfo): Boolean {
        val b = Rect().also { root.getBoundsInScreen(it) }
        if (b.width() <= 0 || b.height() <= 0) return false

        val density = resources.displayMetrics.density
        val bottomInsetDp = when (selectedAiTarget()) {
            AiTarget.GEMINI -> 82f
            AiTarget.CLAUDE -> 76f
            AiTarget.CHATGPT -> 80f
        }
        val x = b.left + b.width() * 0.45f
        val y = (b.bottom - bottomInsetDp * density).coerceAtLeast(b.top + 1f)

        longPressTried = true
        if (!longPress(x, y)) {
            longPressTried = false
            return false
        }

        handler.postDelayed(
            { clickPasteMenuIfVisible() },
            ViewConfiguration.getLongPressTimeout().toLong() + 140L
        )
        return true
    }

    private fun clickPasteMenuIfVisible() {
        if (!active) return
        val root = rootInActiveWindow ?: targetRoot() ?: return
        val paste = findClickableByLabels(
            root,
            listOf(
                "paste",
                "paste from clipboard",
                "insert from clipboard",
                "επικόλληση",
                "επικολληση"
            ),
            listOf("paste", "clipboard")
        )
        if (paste != null && paste.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            handler.postDelayed({ trySend() }, 250)
        }
    }

    private fun trySend() {
        if (!active) return
        val root = targetRoot()
        if (root == null) {
            retrySend()
            return
        }

        val send = findSendButton(root)
        if (send != null && send.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            finishFlow()
            return
        }

        sendRetries++
        if (sendRetries <= 24) {
            handler.postDelayed({ trySend() }, 100)
            return
        }

        // Package-specific UIs can hide the send icon from the accessibility tree.
        // First tap near the right side of the composer, then use the chat-window
        // lower-right corner only as the final fallback.
        if (tapSendNearComposer() || tapSendInChatWindow()) {
            finishFlow()
        } else {
            fail("CAP: δεν βρέθηκε το Send στο " + selectedAiLabel())
        }
    }

    private fun retryPaste() {
        pasteRetries++
        if (pasteRetries <= 14) {
            handler.postDelayed({ tryPasteIntoComposer() }, 100)
        } else {
            fail("CAP: δεν βρέθηκε το ενεργό " + selectedAiLabel() + " composer")
        }
    }

    private fun retrySend() {
        sendRetries++
        if (sendRetries <= 24) handler.postDelayed({ trySend() }, 100)
        else fail("CAP: δεν βρέθηκε το " + selectedAiLabel() + " window")
    }

    private fun targetRoot(): AccessibilityNodeInfo? {
        val targetPackage = selectedAiPackage()
        val activeRoot = rootInActiveWindow
        if (activeRoot?.packageName?.toString() == targetPackage) return activeRoot

        for (window in windows) {
            val root = window.root ?: continue
            if (root.packageName?.toString() == targetPackage) return root
        }
        return null
    }

    private fun composerHints(): List<String> {
        return when (selectedAiTarget()) {
            AiTarget.GEMINI -> listOf(
                "ask gemini",
                "enter a prompt",
                "type a prompt",
                "prompt",
                "message",
                "composer",
                "ρώτησε το gemini",
                "ρωτησε το gemini",
                "μήνυμα",
                "μηνυμα"
            )
            AiTarget.CLAUDE -> listOf(
                "reply to claude",
                "message claude",
                "ask claude",
                "chat input",
                "message",
                "prompt",
                "composer",
                "μήνυμα",
                "μηνυμα"
            )
            AiTarget.CHATGPT -> listOf(
                "message",
                "ask anything",
                "prompt",
                "composer",
                "μήνυμα",
                "μηνυμα"
            )
        }
    }

    private fun findComposer(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let {
            if (isUsableInput(it)) return it
        }

        data class Candidate(val node: AccessibilityNodeInfo, val bounds: Rect, val score: Int)

        val hints = composerHints()
        val candidates = ArrayList<Candidate>()
        val q = java.util.ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)

        while (!q.isEmpty()) {
            val n = q.removeFirst()
            val className = n.className?.toString().orEmpty()
            val haystack = nodeText(n)
            val b = Rect().also { n.getBoundsInScreen(it) }

            if (b.width() > 40 && b.height() > 20 && n.isVisibleToUser) {
                var score = 0
                if (n.isEditable) score += 100
                if (className.contains("EditText", true)) score += 90
                if (n.isFocusable) score += 15
                if (n.isFocused) score += 45
                if (n.actionList.any { it.id == AccessibilityNodeInfo.ACTION_PASTE }) score += 40
                if (hints.any { haystack.contains(it) }) score += 55

                // All three apps keep their composer near the bottom of their own window.
                if (score > 0) {
                    score += (b.bottom / 100)
                    candidates.add(Candidate(n, b, score))
                }
            }

            for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
        }

        return candidates.maxWithOrNull(
            compareBy<Candidate> { it.score }.thenBy { it.bounds.bottom }
        )?.node
    }

    private fun isUsableInput(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString().orEmpty()
        return node.isVisibleToUser && (
            node.isEditable ||
            className.contains("EditText", true) ||
            composerHints().any { nodeText(node).contains(it) }
        )
    }

    private fun findSendButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val labels = when (selectedAiTarget()) {
            AiTarget.GEMINI -> listOf(
                "send",
                "send message",
                "submit",
                "send prompt",
                "αποστολή",
                "αποστολη",
                "στείλε",
                "στειλε"
            )
            AiTarget.CLAUDE -> listOf(
                "send",
                "send message",
                "submit",
                "send prompt",
                "αποστολή",
                "αποστολη",
                "στείλε",
                "στειλε"
            )
            AiTarget.CHATGPT -> listOf(
                "send",
                "send message",
                "submit",
                "αποστολή",
                "αποστολη",
                "στείλε",
                "στειλε"
            )
        }

        return findClickableByLabels(
            root,
            labels,
            listOf("send", "submit", "send_button", "sendbutton")
        )
    }

    private fun nodeText(node: AccessibilityNodeInfo): String {
        return (
            node.text?.toString().orEmpty() + " " +
            node.contentDescription?.toString().orEmpty() + " " +
            node.viewIdResourceName.orEmpty() + " " +
            node.hintText?.toString().orEmpty()
        ).lowercase()
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
            if (n.isVisibleToUser) {
                val haystack = nodeText(n)
                if (labels.any { haystack.contains(it.lowercase()) } ||
                    idHints.any { haystack.contains(it.lowercase()) }) {
                    clickableAncestor(n)?.let { return it }
                }
            }

            for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
        }
        return null
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var n = node
        repeat(6) {
            if (n == null) return null
            if (n!!.isVisibleToUser && n!!.isClickable) return n
            n = n!!.parent
        }
        return null
    }

    private fun tapSendNearComposer(): Boolean {
        val b = lastComposerBounds ?: return false
        if (b.width() <= 0 || b.height() <= 0) return false

        val density = resources.displayMetrics.density
        val inset = when (selectedAiTarget()) {
            AiTarget.GEMINI -> 28f
            AiTarget.CLAUDE -> 28f
            AiTarget.CHATGPT -> 26f
        } * density

        val x = (b.right - inset).coerceAtLeast(b.left + 1f)
        val y = b.exactCenterY()
        return tap(x, y)
    }

    private fun tapSendInChatWindow(): Boolean {
        val root = targetRoot() ?: return false
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
