package com.example.pokercapture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class ChatGptAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile private var instance: ChatGptAccessibilityService? = null

        /** Returns false instead of falling back to ACTION_SEND/new-chat behavior. */
        fun attachAndSend(): Boolean {
            val service = instance ?: return false
            service.beginAttachFlow()
            return true
        }

        fun isRunning(): Boolean = instance != null
    }

    private enum class Step { IDLE, FIND_ATTACH, FIND_MEDIA, PICK_IMAGE, WAIT_RETURN, SEND }

    private val handler = Handler(Looper.getMainLooper())
    private var step = Step.IDLE
    private var retries = 0

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
        if (step == Step.IDLE) return
        val pkg = event?.packageName?.toString().orEmpty()

        when {
            isPickerPackage(pkg) -> {
                if (step == Step.PICK_IMAGE || step == Step.WAIT_RETURN) {
                    handler.postDelayed({ pickNewestImage() }, 80)
                }
            }
            pkg == CHATGPT_PACKAGE -> {
                when (step) {
                    Step.FIND_ATTACH -> handler.postDelayed({ findAndClickAttach() }, 50)
                    Step.FIND_MEDIA -> handler.postDelayed({ findAndClickMediaOption() }, 50)
                    Step.PICK_IMAGE, Step.WAIT_RETURN -> {
                        // A single-select Android picker returns straight to ChatGPT after selection.
                        step = Step.SEND
                        handler.postDelayed({ sendInChat() }, 200)
                    }
                    Step.SEND -> handler.postDelayed({ sendInChat() }, 80)
                    else -> Unit
                }
            }
        }
    }

    private fun beginAttachFlow() {
        handler.removeCallbacksAndMessages(null)
        step = Step.FIND_ATTACH
        retries = 0

        // Do not launch ChatGPT. In split-screen we operate on the already-open ChatGPT window,
        // which preserves the current conversation.
        handler.postDelayed({ findAndClickAttach() }, 80)
    }

    private fun findAndClickAttach() {
        if (step != Step.FIND_ATTACH) return
        val root = chatGptRoot()
        if (root == null) {
            retry(::findAndClickAttach)
            return
        }

        val node = findNode(
            root,
            textLabels = listOf(
                "add photos", "add files", "add attachment", "attachment", "attach",
                "upload", "plus", "επισύναψη", "προσθήκη"
            ),
            idHints = listOf("attach", "attachment", "upload", "plus", "add")
        )

        if (click(node)) {
            step = Step.FIND_MEDIA
            retries = 0
            handler.postDelayed({ findAndClickMediaOption() }, 100)
            return
        }

        // Current ChatGPT builds can expose the + button without useful text/description.
        // Use the bounds of the existing ChatGPT split-screen window as a last-resort tap.
        retries++
        if (retries >= 3 && tapChatComposerEdge(root, leftSide = true)) {
            step = Step.FIND_MEDIA
            retries = 0
            handler.postDelayed({ findAndClickMediaOption() }, 120)
        } else {
            handler.postDelayed({ findAndClickAttach() }, 100)
        }
    }

    private fun findAndClickMediaOption() {
        if (step != Step.FIND_MEDIA) return
        val root = chatGptRoot() ?: rootInActiveWindow
        if (root == null) {
            retry(::findAndClickMediaOption)
            return
        }

        val node = findNode(
            root,
            textLabels = listOf(
                "photos", "photo", "gallery", "images", "image", "upload from device",
                "choose photo", "φωτογραφ", "συλλογή", "εικόν"
            ),
            idHints = listOf("photo", "gallery", "image", "media")
        )

        if (click(node)) {
            step = Step.PICK_IMAGE
            retries = 0
            handler.postDelayed({ pickNewestImage() }, 140)
            return
        }

        retries++
        // Some ChatGPT versions open the system picker immediately after the + button.
        val activePkg = rootInActiveWindow?.packageName?.toString().orEmpty()
        if (isPickerPackage(activePkg)) {
            step = Step.PICK_IMAGE
            retries = 0
            handler.postDelayed({ pickNewestImage() }, 80)
        } else if (retries < 8) {
            handler.postDelayed({ findAndClickMediaOption() }, 100)
        } else {
            cancelFlow()
        }
    }

    private fun pickNewestImage() {
        if (step != Step.PICK_IMAGE && step != Step.WAIT_RETURN) return
        val root = rootInActiveWindow ?: return retry(::pickNewestImage)
        val pkg = root.packageName?.toString().orEmpty()

        if (pkg == CHATGPT_PACKAGE) {
            // We have returned from a one-tap picker.
            step = Step.SEND
            retries = 0
            handler.postDelayed({ sendInChat() }, 200)
            return
        }

        if (!isPickerPackage(pkg)) {
            return retry(::pickNewestImage)
        }

        val image = findBestPickerImage(root)
        if (click(image)) {
            step = Step.WAIT_RETURN
            retries = 0

            // Multi-select pickers sometimes require Add/Done; single-select pickers return immediately.
            handler.postDelayed({ clickPickerDoneIfPresent() }, 120)
            handler.postDelayed({
                if (step == Step.WAIT_RETURN) {
                    val currentPkg = rootInActiveWindow?.packageName?.toString().orEmpty()
                    if (currentPkg == CHATGPT_PACKAGE) {
                        step = Step.SEND
                        sendInChat()
                    }
                }
            }, 260)
        } else {
            retry(::pickNewestImage)
        }
    }

    private fun clickPickerDoneIfPresent() {
        if (step != Step.WAIT_RETURN) return
        val root = rootInActiveWindow ?: return
        val done = findNode(
            root,
            textLabels = listOf("add", "done", "select", "open", "choose", "προσθήκη", "τέλος", "επιλογή"),
            idHints = listOf("add", "done", "confirm", "select")
        )
        if (click(done)) {
            handler.postDelayed({
                step = Step.SEND
                sendInChat()
            }, 200)
        }
    }

    private fun sendInChat() {
        if (step != Step.SEND) return
        val root = chatGptRoot()
        if (root == null) {
            return retry(::sendInChat)
        }

        val send = findNode(
            root,
            textLabels = listOf("send", "send message", "submit", "αποστολή", "στείλε"),
            idHints = listOf("send", "submit")
        )

        if (click(send)) {
            cancelFlow()
            return
        }

        retries++
        if (retries >= 3 && tapChatComposerEdge(root, leftSide = false)) {
            cancelFlow()
        } else if (retries < 12) {
            handler.postDelayed({ sendInChat() }, 100)
        } else {
            cancelFlow()
        }
    }

    private fun chatGptRoot(): AccessibilityNodeInfo? {
        // FLAG_RETRIEVE_INTERACTIVE_WINDOWS lets us find ChatGPT even while PokerStars has focus.
        for (window in windows) {
            val root = window.root ?: continue
            if (root.packageName?.toString() == CHATGPT_PACKAGE) return root
        }
        val active = rootInActiveWindow
        return if (active?.packageName?.toString() == CHATGPT_PACKAGE) active else null
    }

    private fun findNode(
        root: AccessibilityNodeInfo,
        textLabels: List<String>,
        idHints: List<String>
    ): AccessibilityNodeInfo? {
        val q = java.util.ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        while (!q.isEmpty()) {
            val n = q.removeFirst()
            val haystack = buildString {
                append(n.text?.toString().orEmpty())
                append(' ')
                append(n.contentDescription?.toString().orEmpty())
                append(' ')
                append(n.viewIdResourceName.orEmpty())
            }.lowercase()

            if (textLabels.any { haystack.contains(it.lowercase()) } ||
                idHints.any { haystack.contains(it.lowercase()) }) {
                clickable(n)?.let { return it }
            }

            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { q.add(it) }
            }
        }
        return null
    }

    private fun clickable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var current = node
        repeat(4) {
            if (current == null) return null
            if (current!!.isClickable) return current
            current = current!!.parent
        }
        return null
    }

    private fun click(node: AccessibilityNodeInfo?): Boolean {
        return node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    private fun findBestPickerImage(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val rootBounds = Rect().also { root.getBoundsInScreen(it) }
        val candidates = ArrayList<Pair<AccessibilityNodeInfo, Rect>>()
        val q = java.util.ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)

        while (!q.isEmpty()) {
            val n = q.removeFirst()
            val b = Rect().also { n.getBoundsInScreen(it) }
            val desc = (
                n.contentDescription?.toString().orEmpty() + " " +
                n.text?.toString().orEmpty() + " " +
                n.viewIdResourceName.orEmpty()
            ).lowercase()

            val imageLike = desc.contains("image") || desc.contains("photo") ||
                desc.contains("thumbnail") || desc.contains("media") ||
                n.className?.toString()?.contains("Image", ignoreCase = true) == true

            val largeEnough = b.width() > 70 && b.height() > 70
            val belowToolbar = b.top > rootBounds.top + 80

            if (largeEnough && belowToolbar && (n.isClickable || imageLike)) {
                clickable(n)?.let { candidates.add(it to b) }
            }

            for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
        }

        // Photo Picker / DocumentsUI is newest-first. Top-most, then left-most thumbnail wins.
        return candidates
            .distinctBy { System.identityHashCode(it.first) }
            .sortedWith(compareBy<Pair<AccessibilityNodeInfo, Rect>> { it.second.top }.thenBy { it.second.left })
            .firstOrNull()?.first
    }

    private fun tapChatComposerEdge(root: AccessibilityNodeInfo, leftSide: Boolean): Boolean {
        val b = Rect().also { root.getBoundsInScreen(it) }
        if (b.width() <= 0 || b.height() <= 0) return false
        val density = resources.displayMetrics.density
        val inset = (34f * density).toInt()
        val x = if (leftSide) b.left + inset else b.right - inset
        val y = b.bottom - (42f * density).toInt()
        return tap(x.toFloat(), y.toFloat())
    }

    private fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 45))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private fun retry(action: () -> Unit) {
        retries++
        if (retries < 15) handler.postDelayed(action, 100) else cancelFlow()
    }

    private fun cancelFlow() {
        step = Step.IDLE
        retries = 0
        handler.removeCallbacksAndMessages(null)
    }

    private fun isPickerPackage(pkg: String): Boolean {
        val p = pkg.lowercase()
        return p.contains("documentsui") ||
            p.contains("photopicker") ||
            p.contains("providers.media") ||
            p.contains("media.module") ||
            p.contains("gallery")
    }

    private companion object Constants {
        const val CHATGPT_PACKAGE = "com.openai.chatgpt"
    }
}
