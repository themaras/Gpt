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
        const val EXTRA_RAW_RESPONSE = "rawResponse"

        private const val CHANNEL = "capture"
        private const val MODEL = "gpt-5.6-luna"

        private const val POKER_PROMPT = """You analyze a poker table screenshot.
Return exactly ONE pipe-separated line and nothing else:
ACTION|SIZE|POSITION|HAND|STACK|CONFIDENCE

ACTION must be one of FOLD,CHECK,CALL,BET,RAISE,ALL-IN,UNCLEAR.
SIZE is the chip amount for BET/RAISE/CALL when visible or useful; otherwise -.
POSITION is Hero position, or ? if uncertain.
HAND is Hero hole cards in compact notation, or ?.
STACK is Hero effective stack in BB if reliably readable, otherwise ?.
CONFIDENCE is HIGH,MEDIUM,LOW.

Important screenshot rules:
- This image is ALREADY cropped to the PokerStars half of the screen.
- Hero is ALWAYS the bottom-center player with the two face-up hole cards directly above the action buttons.
- The bottom row buttons (Fold / Check / Call / Raise To / Bet) are Hero's currently available actions and are reliable context.
- First read Hero's two face-up cards, board cards, pot, blinds, visible bet/call amount and available action buttons.
- Then find the dealer button. It may appear as a small red/white spade marker next to a player.
- Determine position only if reliable. If position or stack is uncertain, use ? for that field BUT STILL choose an action.
- NEVER output UNCLEAR merely because position, exact stack, opponent name, or dealer button is uncertain.
- If Hero's two cards and at least one action button are visible, you MUST choose one of FOLD,CHECK,CALL,BET,RAISE,ALL-IN.
- Use UNCLEAR only when Hero's cards or action buttons are genuinely not visible.
- Do not invent exact numeric values you cannot read.
- Be fast and concise."""
    }

    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var reader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private val latestFrameLock = Any()
    private var latestFrame: Bitmap? = null
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

        val started = System.currentTimeMillis()
        sendState("ANALYZING")

        val frame = synchronized(latestFrameLock) {
            latestFrame?.copy(Bitmap.Config.ARGB_8888, false)
        }

        if (frame == null) {
            analyzing.set(false)
            sendError("No captured frame. Screen capture is not active yet.", started)
            return
        }

        Thread {
            var imageKb = 0
            var httpCode = 0
            try {
                val apiKey = ApiKeyStore.read(this)
                    ?: throw IllegalStateException("OpenAI API key is missing")

                val crop = cropPokerSide(frame)
                frame.recycle()

                val optimized = resizeForVision(crop)
                if (optimized !== crop) crop.recycle()

                val jpegBytes = ByteArrayOutputStream().use { out ->
                    optimized.compress(Bitmap.CompressFormat.JPEG, 84, out)
                    out.toByteArray()
                }
                optimized.recycle()

                imageKb = ((jpegBytes.size + 1023) / 1024).coerceAtLeast(1)

                // Persist the exact JPEG bytes being sent to the API so the UI can preview
                // the real MediaProjection crop. This is app-private and never goes to Gallery.
                try {
                    java.io.File(filesDir, "last_api_capture.jpg").writeBytes(jpegBytes)
                } catch (_: Exception) {
                }

                val prefs = getSharedPreferences("capture", MODE_PRIVATE)
                prefs.edit()
                    .putInt("api_request_count", prefs.getInt("api_request_count", 0) + 1)
                    .putLong("api_last_request_at", System.currentTimeMillis())
                    .apply()

                sendState("SENDING", imageKb = imageKb)

                val imageData =
                    "data:image/jpeg;base64," + Base64.encodeToString(jpegBytes, Base64.NO_WRAP)

                val body = JSONObject().apply {
                    put("model", MODEL)
                    put(
                        "input",
                        JSONArray().put(
                            JSONObject().apply {
                                put("role", "user")
                                put(
                                    "content",
                                    JSONArray()
                                        .put(
                                            JSONObject()
                                                .put("type", "input_text")
                                                .put("text", POKER_PROMPT)
                                        )
                                        .put(
                                            JSONObject()
                                                .put("type", "input_image")
                                                .put("image_url", imageData)
                                                .put("detail", "high")
                                        )
                                )
                            }
                        )
                    )
                    put("max_output_tokens", 100)
                }

                val apiResult = callOpenAi(apiKey, body.toString(), imageKb)
                httpCode = apiResult.httpCode

                val parsed = parsePokerResult(apiResult.text)
                val elapsed = System.currentTimeMillis() - started

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
                if (parsed.hand != "?") metaParts += parsed.hand
                if (parsed.position != "?") metaParts += parsed.position
                if (parsed.stack != "?") metaParts += parsed.stack
                if (parsed.confidence.isNotBlank()) metaParts += parsed.confidence

                sendBroadcast(
                    Intent(ACTION_ANALYSIS_UPDATE)
                        .setPackage(packageName)
                        .putExtra(EXTRA_STATE, "RESULT")
                        .putExtra(EXTRA_ACTION_TEXT, actionText)
                        .putExtra(EXTRA_META, metaParts.joinToString(" • "))
                        .putExtra(EXTRA_LATENCY_MS, elapsed)
                        .putExtra(EXTRA_IMAGE_KB, imageKb)
                        .putExtra(EXTRA_HTTP_CODE, httpCode)
                        .putExtra(EXTRA_RAW_RESPONSE, apiResult.text.take(180))
                )
            } catch (e: ApiHttpException) {
                httpCode = e.httpCode
                sendError(cleanError(e.message), started, imageKb, httpCode)
            } catch (e: SocketTimeoutException) {
                sendError("API timeout — tap CAP again", started, imageKb, httpCode)
            } catch (e: Exception) {
                sendError(cleanError(e.message), started, imageKb, httpCode)
            } finally {
                analyzing.set(false)
            }
        }.start()
    }

    private fun cropPokerSide(bitmap: Bitmap): Bitmap {
        val mode = getSharedPreferences("capture", MODE_PRIVATE)
            .getString("crop_mode", "LEFT") ?: "LEFT"

        return when (mode) {
            "RIGHT" -> Bitmap.createBitmap(
                bitmap,
                bitmap.width / 2,
                0,
                max(1, bitmap.width - bitmap.width / 2),
                bitmap.height
            )
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

    private fun resizeForVision(bitmap: Bitmap): Bitmap {
        val maxDimension = max(bitmap.width, bitmap.height)
        if (maxDimension <= 1800) return bitmap

        val scale = 1800f / maxDimension.toFloat()
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).roundToInt().coerceAtLeast(1),
            (bitmap.height * scale).roundToInt().coerceAtLeast(1),
            true
        )
    }

    private data class ApiResult(val text: String, val httpCode: Int)

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

            return ApiResult(
                text = extractOutputText(JSONObject(response)),
                httpCode = code
            )
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
        val confidence: String
    )

    private fun parsePokerResult(raw: String): PokerResult {
        val line = raw
            .lineSequence()
            .firstOrNull { it.contains("|") }
            ?.trim()
            .orEmpty()

        val p = line.split("|").map { it.trim() }
        if (p.size < 6) return PokerResult("UNCLEAR", "-", "?", "?", "?", "LOW")

        val allowed = setOf("FOLD", "CHECK", "CALL", "BET", "RAISE", "ALL-IN", "UNCLEAR")
        val action = p[0].uppercase().let { if (it in allowed) it else "UNCLEAR" }

        return PokerResult(
            action = action,
            size = p[1],
            position = p[2],
            hand = p[3],
            stack = p[4],
            confidence = p[5].uppercase()
        )
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
