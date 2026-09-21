package com.example.pokercapture

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import android.util.DisplayMetrics
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.roundToInt

class CaptureService : Service() {
    companion object {
        const val ACTION_START = "capture.start"
        const val ACTION_STOP = "capture.stop"
        const val ACTION_ANALYZE = "capture.analyze"
        const val ACTION_ANALYSIS_UPDATE = "capture.analysis.update"

        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        const val EXTRA_STATE = "state"
        const val EXTRA_ACTION_TEXT = "actionText"
        const val EXTRA_META = "meta"
        const val EXTRA_ERROR = "error"
        const val EXTRA_LATENCY_MS = "latencyMs"
        const val EXTRA_IMAGE_KB = "imageKb"
        const val EXTRA_HTTP_CODE = "httpCode"
        const val EXTRA_BOARD = "board"
        const val EXTRA_STRATEGY = "strategy"
        const val EXTRA_DEBUG = "debug"
        const val EXTRA_RAW = "raw"
        const val EXTRA_REQUEST_ID = "requestId"
        const val EXTRA_IMAGE_SHA = "imageSha"
        const val EXTRA_FRAME_AGE_MS = "frameAgeMs"
        const val EXTRA_FRESH_FRAME = "freshFrame"
        const val EXTRA_CROP_INFO = "cropInfo"
        const val EXTRA_TIMING = "timing"

        private const val CHANNEL = "capture"
        private const val OPENAI_MODEL = "gpt-5.6-luna"
        private const val GEMINI_MODEL = "gemini-3.8-flash"
        private const val PROMPT_VERSION = "debug_v3_perception_lock"

        private const val POKER_PROMPT = """Analyze this low-stakes NL Hold'em tournament screenshot.

The image is the COMPLETE selected PokerStars region exactly as captured by the app.
Hero is the bottom-center player with the two face-up hole cards.

DO THIS IN TWO INTERNAL PASSES. DO NOT SKIP PASS 1.

PASS 1 — READ THE SCREEN ONLY:
1. Read Hero's two hole cards.
2. Read the board. Count the visible community cards exactly:
   - 0 cards = PREFLOP
   - 3 = FLOP
   - 4 = TURN
   - 5 = RIVER
   Never invent missing community cards.
3. Find the real PokerStars dealer/button marker: the small red/white circular marker with a spade symbol next to a player.
4. Determine which seats are ACTUALLY dealt into the current hand. Card backs/face-up cards are evidence of participation.
5. Read the visible current action facing Hero, including CHECK/FOLD/CALL/BET/RAISE and amount when clearly visible.
6. Read Hero stack and blinds only if legible.

POKERSTARS 4-COLOR DECK — HARD RULE:
- RED = HEARTS = h = ♥
- BLACK = SPADES = s = ♠
- BLUE = DIAMONDS = d = ♦
- GREEN = CLUBS = c = ♣
Use the card COLOR as a hard cross-check on the suit symbol.
A blue card MUST be diamond. A green card MUST be club. A red card MUST be heart. A black card MUST be spade.
Never output a suit that conflicts with the visible card color.

POSITION:
Only assign Hero position after identifying the real dealer marker AND the seats actually dealt into THIS hand.
For 6-handed: BTN, SB, BB, UTG, HJ, CO.
For 5-handed: BTN, SB, BB, UTG, CO.
For 4-handed: BTN, SB, BB, CO.
For 3-handed: BTN, SB, BB.
Heads-up: BTN/SB, BB.
NEVER infer Hero position from Hero's screen location alone.
Do not confuse avatars, bounty icons, blind chips, country flags, seat badges or action chips with the dealer button.

SPECIAL JOIN/WAITING RULE:
Hero may have just joined the table, posted out of turn, be waiting for the big blind, be sitting out, or not yet be part of the normal rotation.
If Hero is not clearly dealt into the current hand, or the table state makes normal positional rotation uncertain, POSITION=?.
Do NOT force BB/SB merely because Hero posted chips or is seated near a blind location.
If the dealer marker is not reliable, POSITION=?.

PASS 2 — DECIDE:
Only after PASS 1 is internally consistent, choose the poker action.
Use effective stack, verified position, verified current action, pot odds, board texture and visible opponent action.
If a key fact is unreadable, use ? rather than inventing it.
Do not default to CALL or BET. Consider FOLD, CHECK and RAISE normally.

Return exactly ONE line with 10 fields:
ACTION|SIZE|POSITION|HAND|STACK|BOARD|BUTTON|VISIBLE_ACTION|STRATEGY|CONFIDENCE

ACTION: FOLD,CHECK,CALL,BET,RAISE,ALL-IN,UNCLEAR.
SIZE: chip amount for CALL/BET/RAISE when applicable, otherwise -.
POSITION: BTN,SB,BB,UTG,HJ,CO or ?.
HAND: exactly two compact cards, e.g. Td9h, or ?.
STACK: effective stack in BB if reliable, otherwise ?.
BOARD: compact board with exactly 0,3,4,or5 cards; use PREFLOP for 0, or ? if unreadable.
BUTTON: username/seat label nearest the real dealer marker, or ?.
VISIBLE_ACTION: concise verified action facing Hero, e.g. CHECK, CALL 200, BET 800, RAISE 1600, NONE, or ?.
STRATEGY: maximum 6 words.
CONFIDENCE: HIGH,MEDIUM,LOW.
No explanation beyond that one line."""
    }

    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var reader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private val latestFrameLock = Any()
    private var latestFrame: Bitmap? = null
    private var latestFrameAtMs: Long = 0L
    private var captureSurfaceWidth: Int = 0
    private var captureSurfaceHeight: Int = 0
    private var appWindowWidth: Int = 0
    private var appWindowHeight: Int = 0
    private val analyzing = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    "Screen capture",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCapture()
                sendState("STOPPED")
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                startForeground(
                    1,
                    NotificationCompat.Builder(this, CHANNEL)
                        .setContentTitle("Poker Capture")
                        .setContentText("Split-screen capture ready")
                        .setSmallIcon(android.R.drawable.ic_menu_camera)
                        .build()
                )
                startCapture(intent)
            }
            ACTION_ANALYZE -> analyzeLatestFrame()
        }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun startCapture(intent: Intent) {
        stopCapture()

        val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data: Intent = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java) ?: run {
                sendError("Android did not return screen-capture data", System.currentTimeMillis())
                return
            }
        } else {
            intent.getParcelableExtra(EXTRA_DATA) ?: run {
                sendError("Android did not return screen-capture data", System.currentTimeMillis())
                return
            }
        }

        try {
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = mgr.getMediaProjection(code, data)

            projectionCallback = object : MediaProjection.Callback() {
                override fun onStop() {
                    reader?.close()
                    reader = null
                    virtualDisplay?.release()
                    virtualDisplay = null
                    projection = null
                    synchronized(latestFrameLock) {
                        latestFrame?.recycle()
                        latestFrame = null
                    }
                    sendState("STOPPED")
                }
            }
            projection!!.registerCallback(projectionCallback!!, Handler(Looper.getMainLooper()))

            // IMPORTANT: resources.displayMetrics is the CURRENT split-screen app pane
            // (e.g. 435x800), not the physical display. Using it here downscaled the whole
            // 1340x800 screen to 435px wide before vision ever saw it.
            val windowDm = resources.displayMetrics
            appWindowWidth = windowDm.widthPixels
            appWindowHeight = windowDm.heightPixels

            val realDm = DisplayMetrics()
            @Suppress("DEPRECATION")
            val display = (getSystemService(DISPLAY_SERVICE) as DisplayManager)
                .getDisplay(android.view.Display.DEFAULT_DISPLAY)
            @Suppress("DEPRECATION")
            display.getRealMetrics(realDm)

            val width = realDm.widthPixels
            val height = realDm.heightPixels
            captureSurfaceWidth = width
            captureSurfaceHeight = height

            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            virtualDisplay = projection!!.createVirtualDisplay(
                "PokerCapture",
                width,
                height,
                realDm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader!!.surface,
                null,
                null
            )

            reader!!.setOnImageAvailableListener({ r ->
                val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val plane = image.planes[0]
                    val buffer = plane.buffer
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride
                    val rowPadding = rowStride - pixelStride * image.width

                    val padded = Bitmap.createBitmap(
                        image.width + rowPadding / pixelStride,
                        image.height,
                        Bitmap.Config.ARGB_8888
                    )
                    padded.copyPixelsFromBuffer(buffer)

                    val clean = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
                    padded.recycle()

                    synchronized(latestFrameLock) {
                        latestFrame?.recycle()
                        latestFrame = clean
                        latestFrameAtMs = System.currentTimeMillis()
                    }
                } finally {
                    image.close()
                }
            }, null)

            sendState("CAPTURE_READY")
        } catch (e: SecurityException) {
            sendError("Android blocked screen capture for security reasons", System.currentTimeMillis())
        } catch (e: Exception) {
            sendError(cleanError(e.message), System.currentTimeMillis())
        }
    }

    private fun analyzeLatestFrame() {
        if (!analyzing.compareAndSet(false, true)) return

        val capPressedAt = System.currentTimeMillis()
        sendState("ANALYZING")

        Thread {
            var imageKb = 0
            var httpCode = 0
            try {
                // Wait for a frame captured AFTER the CAP press. This avoids stale-frame analysis.
                val freshDeadline = capPressedAt + 500L
                var frame: Bitmap? = null
                var frameAt = 0L
                var freshFrame = false

                while (System.currentTimeMillis() <= freshDeadline && frame == null) {
                    synchronized(latestFrameLock) {
                        if (latestFrame != null && latestFrameAtMs > capPressedAt) {
                            frame = latestFrame!!.copy(Bitmap.Config.ARGB_8888, false)
                            frameAt = latestFrameAtMs
                            freshFrame = true
                        }
                    }
                    if (frame == null) Thread.sleep(12)
                }

                // Fallback only if Android did not deliver a fresh frame in time; mark it clearly in debug.
                if (frame == null) {
                    synchronized(latestFrameLock) {
                        frame = latestFrame?.copy(Bitmap.Config.ARGB_8888, false)
                        frameAt = latestFrameAtMs
                    }
                }

                val captured = frame
                    ?: throw IllegalStateException("No captured frame. Screen capture is not active yet.")

                val provider = "OPENAI"
                val apiKey = ApiKeyStore.read(this)
                    ?: throw IllegalStateException("OpenAI API key is missing")

                val originalW = captured.width
                val originalH = captured.height
                val cropInfo = cropDescription(captured)
                val region = cropPokerSide(captured)
                captured.recycle()

                val finalW = region.width
                val finalH = region.height

                // DEBUG BUILD: lossless PNG. These exact bytes are both previewed and sent to the API.
                val encodeStarted = System.currentTimeMillis()
                val imageBytes = encodePng(region)
                region.recycle()
                val encodeMs = System.currentTimeMillis() - encodeStarted

                imageKb = ((imageBytes.size + 1023) / 1024).coerceAtLeast(1)
                val imageSha = sha256(imageBytes)

                // Save the EXACT bytes sent to the API for on-device visual verification.
                File(cacheDir, "last_sent.png").writeBytes(imageBytes)

                val prefs = getSharedPreferences("capture", MODE_PRIVATE)
                prefs.edit()
                    .putInt("api_request_count", prefs.getInt("api_request_count", 0) + 1)
                    .putLong("api_last_request_at", System.currentTimeMillis())
                    .apply()

                sendState("SENDING", imageKb = imageKb)

                val base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
                val imageData = "data:image/png;base64,$base64Image"
                val content = JSONArray()
                    .put(JSONObject().put("type", "input_text").put("text", POKER_PROMPT))
                    .put(
                        JSONObject()
                            .put("type", "input_image")
                            .put("image_url", imageData)
                            .put("detail", "high")
                    )

                val body = JSONObject().apply {
                    put("model", OPENAI_MODEL)
                    put(
                        "input",
                        JSONArray().put(
                            JSONObject()
                                .put("role", "user")
                                .put("content", content)
                        )
                    )
                    put("reasoning", JSONObject().put("effort", "none"))
                    put("max_output_tokens", 180)
                }

                val apiStarted = System.currentTimeMillis()
                val apiResult = callOpenAi(apiKey, body.toString(), imageKb)
                val apiMs = System.currentTimeMillis() - apiStarted
                httpCode = apiResult.httpCode

                if (apiResult.text.isBlank()) {
                    throw IllegalStateException("EMPTY MODEL TEXT")
                }

                val parsed = parsePokerResult(apiResult.text)
                val totalMs = System.currentTimeMillis() - capPressedAt
                val frameAgeMs = (capPressedAt - frameAt).coerceAtLeast(0L)

                val actionText = if (
                    parsed.action == "BET" ||
                    parsed.action == "RAISE" ||
                    parsed.action == "CALL"
                ) {
                    if (parsed.size != "-" && parsed.size.isNotBlank()) {
                        "${parsed.action} ${parsed.size}"
                    } else {
                        parsed.action
                    }
                } else {
                    parsed.action
                }

                val metaParts = mutableListOf<String>()
                if (parsed.hand != "?") metaParts += formatCards(parsed.hand)
                if (parsed.position != "?") metaParts += formatPosition(parsed.position)
                if (parsed.stack != "?") metaParts += parsed.stack
                if (parsed.confidence.isNotBlank()) metaParts += parsed.confidence

                val debugText = buildString {
                    appendLine("PROMPT: $PROMPT_VERSION")
                    appendLine("PROVIDER/MODEL: OpenAI / $OPENAI_MODEL")
                    appendLine("FRESH FRAME: $freshFrame")
                    appendLine("CAP pressed: $capPressedAt")
                    appendLine("Frame time: $frameAt")
                    appendLine("Frame age at CAP: ${frameAgeMs} ms")
                    appendLine("App window metrics: ${appWindowWidth}x${appWindowHeight}")
                    appendLine("Capture surface: ${captureSurfaceWidth}x${captureSurfaceHeight}")
                    appendLine("Original frame: ${originalW}x${originalH}")
                    appendLine("Crop: $cropInfo")
                    appendLine("Final: ${finalW}x${finalH}")
                    appendLine("FORMAT: PNG lossless")
                    appendLine("Image: ${imageKb} KB")
                    appendLine("SHA-256: $imageSha")
                    appendLine("HTTP: $httpCode")
                    appendLine("Request ID: ${apiResult.requestId.ifBlank { "—" }}")
                    appendLine("Timing: encode=${encodeMs}ms api=${apiMs}ms total=${totalMs}ms")
                    appendLine("RAW: ${apiResult.text}")
                    appendLine("BUTTON: ${parsed.button}")
                    appendLine("VISIBLE ACTION: ${parsed.visibleAction}")
                    append("PARSED: action=${parsed.action}; size=${parsed.size}; pos=${parsed.position}; hand=${parsed.hand}; stack=${parsed.stack}; board=${parsed.board}; button=${parsed.button}; visible=${parsed.visibleAction}; strategy=${parsed.strategy}; confidence=${parsed.confidence}")
                }

                sendBroadcast(
                    Intent(ACTION_ANALYSIS_UPDATE)
                        .setPackage(packageName)
                        .putExtra(EXTRA_STATE, "RESULT")
                        .putExtra(EXTRA_ACTION_TEXT, actionText)
                        .putExtra(EXTRA_META, metaParts.joinToString(" • "))
                        .putExtra(EXTRA_LATENCY_MS, totalMs)
                        .putExtra(EXTRA_IMAGE_KB, imageKb)
                        .putExtra(EXTRA_HTTP_CODE, httpCode)
                        .putExtra(EXTRA_BOARD, formatCards(parsed.board))
                        .putExtra(EXTRA_STRATEGY, parsed.strategy)
                        .putExtra(EXTRA_DEBUG, debugText)
                        .putExtra(EXTRA_RAW, apiResult.text)
                        .putExtra(EXTRA_REQUEST_ID, apiResult.requestId)
                        .putExtra(EXTRA_IMAGE_SHA, imageSha)
                        .putExtra(EXTRA_FRAME_AGE_MS, frameAgeMs)
                        .putExtra(EXTRA_FRESH_FRAME, freshFrame)
                        .putExtra(EXTRA_CROP_INFO, cropInfo)
                        .putExtra(EXTRA_TIMING, "encode=${encodeMs}ms • api=${apiMs}ms • total=${totalMs}ms")
                )
            } catch (e: ApiHttpException) {
                httpCode = e.httpCode
                sendError(cleanError(e.message), capPressedAt, imageKb, httpCode)
            } catch (e: SocketTimeoutException) {
                sendError("API timeout — tap CAP again", capPressedAt, imageKb, httpCode)
            } catch (e: Exception) {
                sendError(cleanError(e.message), capPressedAt, imageKb, httpCode)
            } finally {
                analyzing.set(false)
            }
        }.start()
    }

    private fun cropPokerSide(bitmap: Bitmap): Bitmap {
        val mode = getSharedPreferences("capture", MODE_PRIVATE)
            .getString("crop_mode", "LEFT") ?: "LEFT"

        return when (mode) {
            "RIGHT" -> {
                val x = bitmap.width / 3
                val y = (bitmap.height * 0.15f).roundToInt()
                val width = max(1, bitmap.width - x)
                val height = max(1, (bitmap.height * 0.70f).roundToInt())
                    .coerceAtMost(bitmap.height - y)
                Bitmap.createBitmap(bitmap, x, y, width, height)
            }
            "TOP" -> Bitmap.createBitmap(
                bitmap,
                0,
                0,
                bitmap.width,
                max(1, bitmap.height / 2)
            )
            "BOTTOM" -> Bitmap.createBitmap(
                bitmap,
                0,
                bitmap.height / 2,
                bitmap.width,
                max(1, bitmap.height - bitmap.height / 2)
            )
            else -> Bitmap.createBitmap(
                bitmap,
                0,
                0,
                max(1, bitmap.width / 2),
                bitmap.height
            )
        }
    }

    private fun cropDescription(bitmap: Bitmap): String {
        val mode = getSharedPreferences("capture", MODE_PRIVATE)
            .getString("crop_mode", "LEFT") ?: "LEFT"
        return when (mode) {
            "RIGHT" -> {
                val x = bitmap.width / 3
                val y = (bitmap.height * 0.15f).roundToInt()
                val w = max(1, bitmap.width - x)
                val h = max(1, (bitmap.height * 0.70f).roundToInt())
                    .coerceAtMost(bitmap.height - y)
                "RIGHT_2_3 x=$x y=$y w=$w h=$h"
            }
            "TOP" -> "TOP x=0 y=0 w=${bitmap.width} h=${max(1, bitmap.height / 2)}"
            "BOTTOM" -> "BOTTOM x=0 y=${bitmap.height / 2} w=${bitmap.width} h=${max(1, bitmap.height - bitmap.height / 2)}"
            else -> "LEFT x=0 y=0 w=${max(1, bitmap.width / 2)} h=${bitmap.height}"
        }
    }

    private fun encodePng(bitmap: Bitmap): ByteArray =
        ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun encodeJpeg(bitmap: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            out.toByteArray()
        }

    private fun resizeForVision(bitmap: Bitmap, targetMax: Int): Bitmap {
        val maxDimension = max(bitmap.width, bitmap.height)
        if (maxDimension <= targetMax) return bitmap

        val scale = targetMax.toFloat() / maxDimension.toFloat()
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).roundToInt().coerceAtLeast(1),
            (bitmap.height * scale).roundToInt().coerceAtLeast(1),
            true
        )
    }

    private data class ApiResult(
        val text: String,
        val httpCode: Int,
        val requestId: String
    )

    private class ApiHttpException(
        val httpCode: Int,
        message: String
    ) : Exception(message)

    private fun callOpenAi(apiKey: String, json: String, imageKb: Int): ApiResult {
        val connection =
            (URL("https://api.openai.com/v1/responses").openConnection() as HttpURLConnection)

        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 6000
            connection.readTimeout = 14000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("Content-Type", "application/json")

            connection.outputStream.use {
                it.write(json.toByteArray(Charsets.UTF_8))
                it.flush()
            }

            sendState("REQUEST_SENT", imageKb = imageKb)
            sendState("WAITING_API", imageKb = imageKb)

            val code = connection.responseCode
            val stream =
                if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

            if (code !in 200..299) {
                val message = try {
                    JSONObject(response)
                        .optJSONObject("error")
                        ?.optString("message")
                        ?.takeIf { it.isNotBlank() }
                } catch (_: Exception) {
                    null
                }
                throw ApiHttpException(code, message ?: "OpenAI error HTTP $code")
            }

            val root = JSONObject(response)
            return ApiResult(
                text = extractOutputText(root),
                httpCode = code,
                requestId = connection.getHeaderField("x-request-id").orEmpty()
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun callGemini(apiKey: String, json: String, imageKb: Int): ApiResult {
        val connection = (
            URL("https://generativelanguage.googleapis.com/v1beta/models/$GEMINI_MODEL:generateContent")
                .openConnection() as HttpURLConnection
        )

        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 6000
            connection.readTimeout = 16000
            connection.doOutput = true
            connection.setRequestProperty("x-goog-api-key", apiKey)
            connection.setRequestProperty("Content-Type", "application/json")

            connection.outputStream.use {
                it.write(json.toByteArray(Charsets.UTF_8))
                it.flush()
            }

            sendState("REQUEST_SENT", imageKb = imageKb)
            sendState("WAITING_API", imageKb = imageKb)

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

            if (code !in 200..299) {
                val message = try {
                    JSONObject(response)
                        .optJSONObject("error")
                        ?.optString("message")
                        ?.takeIf { it.isNotBlank() }
                } catch (_: Exception) {
                    null
                }
                throw ApiHttpException(code, message ?: "Gemini error HTTP $code")
            }

            val root = JSONObject(response)
            val candidates = root.optJSONArray("candidates")
            val text = candidates
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?.let { parts ->
                    buildString {
                        for (i in 0 until parts.length()) {
                            val t = parts.optJSONObject(i)?.optString("text").orEmpty()
                            if (t.isNotBlank()) append(t)
                        }
                    }
                }
                .orEmpty()
                .trim()

            return ApiResult(text = text, httpCode = code, requestId = "")
        } finally {
            connection.disconnect()
        }
    }

    private fun extractOutputText(root: JSONObject): String {
        val output = root.optJSONArray("output") ?: return ""
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            if (item.optString("type") != "message") continue
            val content = item.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val part = content.optJSONObject(j) ?: continue
                if (part.optString("type") == "output_text") {
                    return part.optString("text").trim()
                }
            }
        }
        return ""
    }

    private data class PokerResult(
        val action: String,
        val size: String,
        val position: String,
        val hand: String,
        val stack: String,
        val board: String,
        val button: String,
        val visibleAction: String,
        val strategy: String,
        val confidence: String
    )

    private fun parsePokerResult(raw: String): PokerResult {
        val line = raw
            .lineSequence()
            .firstOrNull { it.contains("|") }
            ?.trim()
            .orEmpty()

        val p = line.split("|").map { it.trim() }
        if (p.size < 10) {
            return PokerResult("UNCLEAR", "-", "?", "?", "?", "?", "?", "?", "Need clearer read", "LOW")
        }

        val allowed = setOf("FOLD", "CHECK", "CALL", "BET", "RAISE", "ALL-IN", "UNCLEAR")
        val action = p[0].uppercase().let { if (it in allowed) it else "UNCLEAR" }

        // Basic structural sanity checks: two hole cards; board can only be preflop/3/4/5 cards.
        val cardRegex = Regex("([2-9TJQKA])([cdhs])", RegexOption.IGNORE_CASE)
        val handRaw = p[3].replace("10", "T").replace(" ", "")
        val handCount = cardRegex.findAll(handRaw).count()
        val hand = if (p[3] == "?" || handCount == 2) p[3] else "?"

        val boardRaw = p[5].replace("10", "T").replace(" ", "")
        val boardCount = cardRegex.findAll(boardRaw).count()
        val board = when {
            p[5].equals("PREFLOP", true) -> "PREFLOP"
            p[5] == "?" -> "?"
            boardCount in setOf(3, 4, 5) -> p[5]
            else -> "?"
        }

        return PokerResult(
            action = action,
            size = p[1],
            position = p[2],
            hand = hand,
            stack = p[4],
            board = board,
            button = p[6],
            visibleAction = p[7],
            strategy = p[8],
            confidence = p[9].uppercase()
        )
    }

    private fun formatCards(raw: String): String {
        if (raw == "?" || raw.equals("PREFLOP", true)) return raw.uppercase()
        val normalized = raw.replace("10", "T").replace(" ", "")
        val cardRegex = Regex("([2-9TJQKA])([cdhs])", RegexOption.IGNORE_CASE)
        val cards = cardRegex.findAll(normalized).map { m ->
            val rank = m.groupValues[1].uppercase()
            val suit = when (m.groupValues[2].lowercase()) {
                "c" -> "♣"
                "d" -> "♦"
                "h" -> "♥"
                "s" -> "♠"
                else -> ""
            }
            rank + suit
        }.toList()
        return if (cards.isNotEmpty()) cards.joinToString(" ") else raw
    }

    private fun formatPosition(raw: String): String = when (raw.trim().uppercase()) {
        "BTN", "BUTTON" -> "BUTTON"
        "SB" -> "SMALL BLIND"
        "BB" -> "BIG BLIND"
        "UTG" -> "UNDER THE GUN"
        "HJ" -> "HIJACK"
        "CO" -> "CUTOFF"
        "BTN/SB", "SB/BTN" -> "BUTTON / SMALL BLIND"
        else -> raw
    }

    private fun sendState(
        state: String,
        imageKb: Int = 0,
        httpCode: Int = 0
    ) {
        sendBroadcast(
            Intent(ACTION_ANALYSIS_UPDATE)
                .setPackage(packageName)
                .putExtra(EXTRA_STATE, state)
                .putExtra(EXTRA_IMAGE_KB, imageKb)
                .putExtra(EXTRA_HTTP_CODE, httpCode)
        )
    }

    private fun sendError(
        message: String,
        started: Long,
        imageKb: Int = 0,
        httpCode: Int = 0
    ) {
        sendBroadcast(
            Intent(ACTION_ANALYSIS_UPDATE)
                .setPackage(packageName)
                .putExtra(EXTRA_STATE, "ERROR")
                .putExtra(EXTRA_ERROR, message)
                .putExtra(EXTRA_LATENCY_MS, System.currentTimeMillis() - started)
                .putExtra(EXTRA_IMAGE_KB, imageKb)
                .putExtra(EXTRA_HTTP_CODE, httpCode)
        )
    }

    private fun cleanError(message: String?): String {
        if (message.isNullOrBlank()) return "Request failed"
        return message
            .replace(Regex("sk-[A-Za-z0-9_-]+"), "sk-***")
            .take(180)
    }

    private fun stopCapture() {
        synchronized(latestFrameLock) {
            latestFrame?.recycle()
            latestFrame = null
            latestFrameAtMs = 0L
        }
        reader?.close()
        reader = null
        virtualDisplay?.release()
        virtualDisplay = null

        projectionCallback?.let { callback ->
            try {
                projection?.unregisterCallback(callback)
            } catch (_: Exception) {
            }
        }
        projectionCallback = null

        try {
            projection?.stop()
        } catch (_: Exception) {
        }
        projection = null
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }
}
