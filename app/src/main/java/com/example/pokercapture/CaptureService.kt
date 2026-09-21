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
        private const val PROMPT_VERSION = "debug_v1_fresh_png"

        private const val POKER_PROMPT = """Analyze this low-stakes NL Hold'em tournament screenshot.

The image is the COMPLETE selected PokerStars region exactly as captured by the app.
Hero is the bottom-center player with the two face-up hole cards.

VERY IMPORTANT — POKERSTARS 4-COLOR DECK:
- RED cards are HEARTS (♥).
- BLACK cards are SPADES (♠).
- BLUE cards are DIAMONDS (♦).
- GREEN cards are CLUBS (♣).
Use BOTH the suit symbol and the card color to identify suits. If the symbol is small, the color mapping above is authoritative.
Examples: red Q = Qh, black Q = Qs, blue Q = Qd, green Q = Qc.

POSITION DETECTION — MUST FOLLOW THIS ORDER:
A. Find Hero at bottom-center.
B. Find the actual dealer/button marker: the small red/white circular marker with a spade symbol next to one player.
C. Count only seats actually dealt into the current hand.
D. Starting from the dealer button and moving clockwise, assign positions from the active seats.
For 6-handed: BTN, SB, BB, UTG, HJ, CO.
For 5-handed: BTN, SB, BB, UTG, CO.
For 4-handed: BTN, SB, BB, CO.
For 3-handed: BTN, SB, BB.
Heads-up: BTN/SB, BB.
NEVER infer Hero position from screen location alone.
Do not confuse avatars, bounty icons, blind chips, country flags or action chips with the dealer button.
If dealer/button is not reliable, POSITION=? rather than guessing.

STRATEGY:
Use effective stack, position, prior action, pot odds, board texture and visible opponent action.
Do not default to CALL or BET. Consider FOLD, CHECK and RAISE normally.
For strong value hands, use appropriate value aggression.
For weak hands facing meaningful action, fold when calling is not justified.
Never invent unreadable values.

Return exactly ONE line:
ACTION|SIZE|POSITION|HAND|STACK|BOARD|STRATEGY|CONFIDENCE

ACTION: FOLD,CHECK,CALL,BET,RAISE,ALL-IN,UNCLEAR.
SIZE: chip amount for CALL/BET/RAISE when applicable, otherwise -.
POSITION: BTN,SB,BB,UTG,HJ,CO or ?.
HAND: compact cards, e.g. Td9h.
STACK: effective stack in BB if reliable, otherwise ?.
BOARD: compact board, e.g. Ac4h2d or PREFLOP.
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

            val dm = resources.displayMetrics
            val width = dm.widthPixels
            val height = dm.heightPixels

            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            virtualDisplay = projection!!.createVirtualDisplay(
                "PokerCapture",
                width,
                height,
                dm.densityDpi,
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
                    put("max_output_tokens", 120)
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
                    appendLine("Original: ${originalW}x${originalH}")
                    appendLine("Crop: $cropInfo")
                    appendLine("Final: ${finalW}x${finalH}")
                    appendLine("FORMAT: PNG lossless")
                    appendLine("Image: ${imageKb} KB")
                    appendLine("SHA-256: $imageSha")
                    appendLine("HTTP: $httpCode")
                    appendLine("Request ID: ${apiResult.requestId.ifBlank { "—" }}")
                    appendLine("Timing: encode=${encodeMs}ms api=${apiMs}ms total=${totalMs}ms")
                    appendLine("RAW: ${apiResult.text}")
                    append("PARSED: action=${parsed.action}; size=${parsed.size}; pos=${parsed.position}; hand=${parsed.hand}; stack=${parsed.stack}; board=${parsed.board}; strategy=${parsed.strategy}; confidence=${parsed.confidence}")
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
        if (p.size < 8) return PokerResult("UNCLEAR", "-", "?", "?", "?", "?", "Need clearer read", "LOW")

        val allowed = setOf("FOLD", "CHECK", "CALL", "BET", "RAISE", "ALL-IN", "UNCLEAR")
        val action = p[0].uppercase().let { if (it in allowed) it else "UNCLEAR" }

        return PokerResult(
            action = action,
            size = p[1],
            position = p[2],
            hand = p[3],
            stack = p[4],
            board = p[5],
            strategy = p[6],
            confidence = p[7].uppercase()
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
